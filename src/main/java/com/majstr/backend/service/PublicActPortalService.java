package com.majstr.backend.service;

import com.lowagie.text.DocumentException;
import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.PublicActView;
import com.majstr.backend.dto.QuestionRequest;
import com.majstr.backend.dto.QuestionResponse;
import com.majstr.backend.dto.SignRequest;
import com.majstr.backend.entity.Client;
import com.majstr.backend.entity.ProjectMessage;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkAct;
import com.majstr.backend.entity.WorkActItem;
import com.majstr.backend.entity.WorkActReceipt;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.exception.WorkActSignedException;
import com.majstr.backend.exception.WorkActValidationException;
import com.majstr.backend.push.PushService;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.ProjectMessageRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import com.majstr.backend.repository.WorkActItemRepository;
import com.majstr.backend.repository.WorkActReceiptRepository;
import com.majstr.backend.repository.WorkActRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * The public work-act portal ({@code ?a=} token): view / sign / question / pdf for ONE act. A
 * document the client reviews and personally SIGNS — that is why acts get their own link kind and
 * are NOT folded into the read-only ECONOMY portal (a deliberate 1.16.0 boundary). Defense-in-depth
 * on read: an act is served only in status SENT or SIGNED — never a DRAFT, regardless of the token.
 */
@Service
@RequiredArgsConstructor
public class PublicActPortalService {

    private static final int MONEY_SCALE = 2;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final ProjectShareLinkRepository linkRepository;
    private final WorkActRepository actRepository;
    private final WorkActItemRepository itemRepository;
    private final WorkActReceiptRepository receiptRepository;
    private final EstimateRepository estimateRepository;
    private final ProjectMessageRepository messageRepository;
    private final WorkActPdfService pdfService;
    private final ActCumulativeCalculator cumulativeCalculator;
    private final ActAddendumCreator addendumCreator;
    private final ActSignedCopyService signedCopy;
    private final WorkActReceiptService receiptService;
    private final PushService pushService;
    private final MessageSource messages;

    @Transactional(readOnly = true)
    public PublicActView view(String token) {
        return toView(resolveShareableAct(token));
    }

    /**
     * The client confirms acceptance of the works — a simple electronic signature (NOT a qualified
     * one): honest wording is enforced in the UI, never «юридично рівнозначно власноручному». Records
     * signer name/phone/ip/user-agent, stamps the act SIGNED, computes a {@code doc_hash} (SHA-256 of
     * the canonical PDF) as tamper evidence, notifies the master (push, fail-soft) and emails the
     * client a PDF copy (independent external trace, fail-soft).
     */
    @Transactional
    public PublicActView sign(String token, SignRequest req, String clientIp, String userAgent)
            throws IOException, DocumentException {
        WorkAct act = resolveActByToken(token);
        if (act.getStatus() == WorkActStatus.SIGNED) {
            throw new WorkActSignedException(); // 409 — already accepted, cannot sign twice
        }
        if (act.getStatus() != WorkActStatus.SENT) {
            throw new ResourceNotFoundException("Act not available");
        }
        // Belt-and-braces (review fix): share and offline-sign already refuse an empty act, but the
        // owner can still empty a SENT act via PUT /items — never let that emptiness become SIGNED
        // (immutable, undeletable).
        List<WorkActItem> items = itemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(act.getId());
        if (items.isEmpty() && !receiptRepository.existsByWorkActId(act.getId())) {
            throw new WorkActValidationException("error.work-act.empty", "WORK_ACT_EMPTY");
        }
        // Roll any additional (off-estimate) works into a SIGNED ADDENDUM estimate FIRST — so «За
        // договором» absorbs them and «Прийнято актами» can never exceed it (acts-fix; the portal
        // path used to skip this, unlike the offline path).
        addendumCreator.createIfNeeded(act);
        act.setStatus(WorkActStatus.SIGNED);
        act.setSignedAt(Instant.now());
        act.setSignerName(req.clientName().trim());
        act.setSignerPhone(req.clientPhone().trim());
        act.setSignerIp(clientIp);
        act.setSignerUserAgent(truncate(userAgent, 512));
        act.setSignedOffline(false);

        // Tamper stamp + client copy — the SAME artifacts the offline path produces (review fix:
        // extracted into ActSignedCopyService so neither path can drift from the other).
        List<WorkActReceipt> receipts = receiptRepository.findByWorkActIdOrderBySortOrderAscCreatedAtAsc(act.getId());
        List<WorkActPdfService.ReceiptRow> receiptRows = receipts.stream()
                .map(WorkActPdfService.ReceiptRow::from).toList();
        act.setDocHash(signedCopy.computeDocHash(act, items, receiptRows));
        notifyContractor(act, items);
        signedCopy.emailClientCopy(act, items, receiptRows);
        return toView(act, items, receipts);
    }

    @Transactional
    public QuestionResponse question(String token, QuestionRequest req, String clientIp) {
        WorkAct act = resolveShareableAct(token);
        Project project = act.getProject();
        ProjectMessage saved = messageRepository.save(ProjectMessage.builder()
                .project(project)
                .authorName(blankToNull(req.authorName()))
                .authorPhone(blankToNull(req.authorPhone()))
                .message(req.message().trim())
                .authorIp(clientIp)
                .build());
        User contractor = project.getOwner();
        pushService.sendToUser(contractor,
                messages.getMessage("push.question.title", null, LocalizationConfig.UKRAINIAN),
                "«Акт № " + act.getNumber() + "»: " + saved.getMessage(),
                "/projects/" + project.getId());
        return QuestionResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public byte[] pdf(String token) throws IOException, DocumentException {
        WorkAct act = resolveShareableAct(token);
        List<WorkActItem> items = itemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(act.getId());
        List<WorkActPdfService.ReceiptRow> receipts = receiptRepository
                .findByWorkActIdOrderBySortOrderAscCreatedAtAsc(act.getId()).stream()
                .map(WorkActPdfService.ReceiptRow::from).toList();
        return pdfService.render(model(act, items, receipts, act.getDocHash(),
                cumulativeCalculator.forDownload(act, items, receiptRepository.sumByWorkActId(act.getId()))));
    }

    /** The photo of one receipt, for the portal's «Чеки та рахунки» block. Same defense-in-depth as
     *  every other public read: the act must be SENT/SIGNED, and the receipt must belong to it. */
    @Transactional(readOnly = true)
    public ProjectPhotoService.PhotoFile receiptFile(String token, UUID receiptId) throws IOException {
        WorkAct act = resolveShareableAct(token);
        return receiptService.readPublicFile(act.getId(), receiptId);
    }

    // ---- token resolution -------------------------------------------------

    /** ACT token → the act, or a neutral 404 on every failure (unknown/revoked/expired token). */
    private WorkAct resolveActByToken(String token) {
        if (token == null || token.isBlank()) {
            throw new ResourceNotFoundException("Share link not found");
        }
        ProjectShareLink link = linkRepository.findByTokenAndKind(token, ShareLinkKind.ACT).orElse(null);
        if (link == null || !link.isUsable(Instant.now()) || link.getWorkAct() == null) {
            throw new ResourceNotFoundException("Share link not found");
        }
        return actRepository.findById(link.getWorkAct().getId())
                .orElseThrow(() -> new ResourceNotFoundException("Act not found"));
    }

    /** As above, but also requires the act be in a client-visible status (SENT/SIGNED) — a DRAFT is
     *  a 404 even with a valid token (the token existing is never sufficient; see {@code economyVisible}). */
    private WorkAct resolveShareableAct(String token) {
        WorkAct act = resolveActByToken(token);
        if (act.getStatus() != WorkActStatus.SENT && act.getStatus() != WorkActStatus.SIGNED) {
            throw new ResourceNotFoundException("Act not available");
        }
        return act;
    }

    // ---- view building ----------------------------------------------------

    private PublicActView toView(WorkAct act) {
        return toView(act, itemRepository.findByWorkActIdOrderBySortOrderAscIdAsc(act.getId()),
                receiptRepository.findByWorkActIdOrderBySortOrderAscCreatedAtAsc(act.getId()));
    }

    private PublicActView toView(WorkAct act, List<WorkActItem> items, List<WorkActReceipt> receipts) {
        Map<UUID, String> names = estimateNames(items);
        BigDecimal total = BigDecimal.ZERO.setScale(MONEY_SCALE, ROUNDING);
        List<PublicActView.Item> itemViews = new ArrayList<>(items.size());
        for (WorkActItem it : items) {
            total = total.add(it.getLineTotal());
            itemViews.add(new PublicActView.Item(
                    it.getName(), it.getCategory(),
                    it.getEstimateId() == null ? null : names.get(it.getEstimateId()),
                    UnitLabel.ua(it.getUnit()), it.getQuantity(), it.getUnitPrice(), it.getLineTotal()));
        }
        BigDecimal receiptsTotal = BigDecimal.ZERO.setScale(MONEY_SCALE, ROUNDING);
        List<PublicActView.Receipt> receiptViews = new ArrayList<>(receipts.size());
        for (WorkActReceipt r : receipts) {
            if (!r.isItemized()) {
                receiptsTotal = receiptsTotal.add(r.getAmount()); // itemized = billed by act lines
            }
            receiptViews.add(new PublicActView.Receipt(r.getId(), r.getLabel(), r.getIssuedAt(),
                    r.getAmount(), r.getStorageKey() != null, r.isItemized()));
        }
        BigDecimal advance = act.getAdvanceOffset() == null
                ? BigDecimal.ZERO.setScale(MONEY_SCALE, ROUNDING) : act.getAdvanceOffset();
        // The client owes the works plus the re-billed receipts, less any advance already paid.
        BigDecimal payable = total.add(receiptsTotal).subtract(advance)
                .max(BigDecimal.ZERO).setScale(MONEY_SCALE, ROUNDING);
        Project project = act.getProject();
        User owner = project.getOwner();
        Client client = project.getClient();
        return new PublicActView(
                act.getNumber(), act.getTitle(), act.getKind().name(), act.getStatus().name(),
                act.getIssuedAt(), act.getPeriodFrom(), act.getPeriodTo(), act.getContractRef(),
                project.getName(), project.getAddress(),
                contractorName(owner), clientName(client),
                itemViews, receiptViews, total, receiptsTotal, act.getAdvanceOffset(), payable,
                HryvniaInWords.format(payable),
                act.getSignedAt(), act.getSignerName());
    }

    private Map<UUID, String> estimateNames(List<WorkActItem> items) {
        Map<UUID, String> names = new HashMap<>();
        items.stream().map(WorkActItem::getEstimateId).filter(Objects::nonNull).distinct().forEach(id ->
                estimateRepository.findById(id).ifPresent(e ->
                        names.put(id, e.getName() == null || e.getName().isBlank() ? "Кошторис" : e.getName().trim())));
        return names;
    }

    private WorkActPdfService.PdfModel model(WorkAct act, List<WorkActItem> items,
                                             List<WorkActPdfService.ReceiptRow> receipts, String docHash,
                                             WorkActPdfService.CumulativeReference cumulative) {
        Project project = act.getProject();
        return new WorkActPdfService.PdfModel(
                project.getOwner(), project, project.getClient(), act, items, receipts,
                estimateNames(items), docHash, cumulative);
    }

    // ---- side effects -----------------------------------------------------

    private void notifyContractor(WorkAct act, List<WorkActItem> items) {
        BigDecimal total = items.stream().map(WorkActItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String title = messages.getMessage("push.act-signed",
                new Object[]{act.getSignerName(), money(total)}, LocalizationConfig.UKRAINIAN);
        pushService.sendToUser(act.getProject().getOwner(), title,
                "Акт № " + act.getNumber(), "/projects/" + act.getProject().getId());
    }

    // ---- helpers ----------------------------------------------------------

    private static String contractorName(User owner) {
        if (notBlank(owner.getCompanyName())) return owner.getCompanyName().trim();
        if (notBlank(owner.getLegalName())) return owner.getLegalName().trim();
        return owner.getFullName();
    }

    private static String clientName(Client client) {
        if (client == null) return "";
        if (notBlank(client.getLegalName())) return client.getLegalName().trim();
        return client.getFullName();
    }

    private static String money(BigDecimal amount) {
        return amount.setScale(MONEY_SCALE, ROUNDING).toPlainString() + " грн";
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
