package com.majstr.backend.service;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.MessageView;
import com.majstr.backend.dto.PayTo;
import com.majstr.backend.dto.PaymentNoticeRequest;
import com.majstr.backend.dto.PaymentReceiptRequest;
import com.majstr.backend.dto.QuestionResponse;
import com.majstr.backend.entity.EstimateShareLink;
import com.majstr.backend.entity.MessageKind;
import com.majstr.backend.entity.PaymentNoticeStatus;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectMessage;
import com.majstr.backend.entity.ProjectShareLink;
import com.majstr.backend.entity.ShareLinkKind;
import com.majstr.backend.entity.User;
import com.majstr.backend.entity.WorkActStatus;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.EstimateShareLinkRepository;
import com.majstr.backend.repository.ProjectMessageRepository;
import com.majstr.backend.repository.ProjectShareLinkRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * «Я оплатив» (V217): the client tells the master, from any of his portals, that he sent money —
 * usually with the bank's receipt, because a transfer can take a day to land and the master wants to
 * know it was sent.
 *
 * <p>Stored as a {@link MessageKind#PAYMENT} project message, so it reaches the master the way every
 * client message does — the object's messages, the push, the attachment retention.</p>
 *
 * <p><b>The receipt is checked, and a match writes the money</b> (owner ruling: a master who has
 * switched notifications off must still find the payment in the object's money, so recording it
 * cannot hang on a tap in a notification). {@link PaymentReceiptReader} reads the receipt,
 * {@link PaymentReceiptCheck} decides; a match goes through {@link PaymentService#addReceipt}, the
 * one door money enters by, and the notice is {@link PaymentNoticeStatus#RECORDED}. Anything else —
 * no receipt, unreadable, a different sum or recipient — waits as {@link PaymentNoticeStatus#PENDING}
 * with the reason, for the master's «Кошти прийшли» ({@link #confirm}) or «Не надійшло»
 * ({@link #dismiss}). The push only tells; it carries no button.</p>
 *
 * <p>Any client-facing link of the object opens it — the signing portal (a deposit is paid there),
 * the economy one, an act's, the single-estimate one — but never the MESSAGE link, which can be sent
 * to anyone, and only while the master shows a way to pay at all.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentNoticeService {

    private static final List<ShareLinkKind> CLIENT_KINDS =
            List.of(ShareLinkKind.PORTAL, ShareLinkKind.ECONOMY, ShareLinkKind.ACT);

    private final ProjectShareLinkRepository projectLinks;
    private final EstimateShareLinkRepository estimateLinks;
    private final ProjectMessageRepository messageRepository;
    private final MessageFileService messageFileService;
    private final MasterNotifier masterNotifier;
    private final PaymentService paymentService;
    private final PaymentReceiptReader receiptReader;
    private final ProjectService projectService;
    private final PlatformTransactionManager transactionManager;

    /** The messages screen's grace period, for the view this answers with ({@link MessageService}). */
    @Value("${app.message-files.grace-days:14}")
    private int graceDays;

    /** The details a receipt is compared with: the master's, as the portal offered them. */
    private record Expected(String iban, String card) {}

    private record Saved(QuestionResponse response, UUID messageId, UUID projectId, User owner,
                         String projectName, String author, int attached) {}

    /**
     * The receipt goes to storage first, outside the transaction (review B-121), and is removed again
     * if the notice cannot be saved: the client then sees a failure and retries, rather than a
     * «надіслано» for a receipt that never arrived. Reading it is a model call, also outside any
     * transaction; it never fails the notice — an unread receipt is a PENDING one.
     */
    public QuestionResponse submit(String token, PaymentNoticeRequest req, List<MultipartFile> files,
                                   String senderIp) {
        Expected expected = Transactions.read(transactionManager, () -> {
            Project project = resolve(token);
            PayTo payTo = PayoutDetails.of(project.getOwner(), project);
            return new Expected(payTo.iban(), payTo.card());
        });
        List<byte[]> contents = contentsOf(files);
        List<MessageFileService.StoredUpload> uploads = messageFileService.store(files);
        PaymentReceiptCheck.Outcome outcome = checkReceipts(req.amount(), uploads, contents, expected);
        Saved saved;
        try {
            saved = Transactions.write(transactionManager, () -> save(token, req, uploads, senderIp, outcome));
        } catch (RuntimeException e) {
            messageFileService.discard(uploads);
            throw e;
        }
        boolean recorded = outcome.matched()
                && record(saved, req.amount(), outcome.receivedAt(), "Оплата з порталу · квитанція звірена");
        push(saved, req.amount(), recorded);
        return saved.response();
    }

    /**
     * «Кошти прийшли»: a PENDING notice becomes a payment, through the one door (which also checks
     * the economy is open to him). A notice already settled is answered as it is, so a double tap
     * records once.
     */
    @Transactional
    public MessageView confirm(UUID projectId, UUID messageId, UUID ownerId) {
        ProjectMessage notice = lockedNotice(projectId, messageId, ownerId);
        if (notice.getPaymentStatus() == PaymentNoticeStatus.PENDING) {
            recordInto(notice, ownerId, notice.getPaymentAmount(),
                    LocalDate.now(LocalizationConfig.ZONE), "Оплата з порталу");
        }
        notice.setRead(true);
        return MessageView.from(notice, graceDays);
    }

    /** «Не надійшло»: a PENDING notice is closed, nothing written. Settled ones are left alone. */
    @Transactional
    public MessageView dismiss(UUID projectId, UUID messageId, UUID ownerId) {
        ProjectMessage notice = lockedNotice(projectId, messageId, ownerId);
        if (notice.getPaymentStatus() == PaymentNoticeStatus.PENDING) {
            notice.setPaymentStatus(PaymentNoticeStatus.DISMISSED);
        }
        notice.setRead(true);
        return MessageView.from(notice, graceDays);
    }

    /**
     * The NBU payment QR of the master behind this link, as a PNG — or a neutral 404 when the link
     * offers no way to pay, or the master's requisites make no valid QR (no РНОКПП, say).
     */
    public byte[] qrPng(String token) {
        String qrUrl = Transactions.read(transactionManager, () -> {
            Project project = resolve(token);
            return PayoutDetails.of(project.getOwner(), project).qrUrl();
        });
        if (qrUrl == null) {
            throw new ResourceNotFoundException("Payment QR not available");
        }
        return QrPng.of(qrUrl);
    }

    /** The first receipt that matches wins; otherwise the first reason a receipt did not. */
    private PaymentReceiptCheck.Outcome checkReceipts(BigDecimal claimed,
                                                      List<MessageFileService.StoredUpload> uploads,
                                                      List<byte[]> contents, Expected expected) {
        if (uploads.isEmpty()) {
            return PaymentReceiptCheck.Outcome.no("Клієнт не додав квитанцію.");
        }
        LocalDate today = LocalDate.now(LocalizationConfig.ZONE);
        PaymentReceiptCheck.Outcome firstNo = null;
        for (int i = 0; i < uploads.size() && i < contents.size(); i++) {
            if (contents.get(i) == null) {
                continue;
            }
            try {
                PaymentReceiptReader.Read read =
                        receiptReader.read(uploads.get(i).contentType(), contents.get(i));
                PaymentReceiptCheck.Outcome outcome =
                        PaymentReceiptCheck.check(read, claimed, expected.iban(), expected.card(), today);
                if (outcome.matched()) {
                    return outcome;
                }
                if (firstNo == null) {
                    firstNo = outcome;
                }
            } catch (RuntimeException e) {
                log.warn("Payment receipt could not be read: {}", e.getMessage());
            }
        }
        return firstNo != null ? firstNo : PaymentReceiptCheck.Outcome.no("Не вдалося прочитати квитанцію.");
    }

    /** The bytes of the files {@link MessageFileService#store} keeps, in its order (the non-empty ones). */
    private static List<byte[]> contentsOf(List<MultipartFile> files) {
        List<byte[]> contents = new ArrayList<>();
        if (files == null) {
            return contents;
        }
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            try {
                contents.add(file.getBytes());
            } catch (IOException e) {
                contents.add(null);
            }
        }
        return contents;
    }

    private Saved save(String token, PaymentNoticeRequest req,
                       List<MessageFileService.StoredUpload> uploads, String senderIp,
                       PaymentReceiptCheck.Outcome outcome) {
        Project project = resolve(token);
        String author = blankToNull(req.authorName());
        if (author == null && project.getClient() != null) {
            author = blankToNull(project.getClient().getFullName());
        }
        String comment = blankToNull(req.comment());
        ProjectMessage message = messageRepository.save(ProjectMessage.builder()
                .project(project)
                .kind(MessageKind.PAYMENT)
                .paymentAmount(req.amount())
                .paymentStatus(PaymentNoticeStatus.PENDING)
                .paymentNote(outcome.note())
                .authorName(author)
                .message(comment == null ? "Оплату здійснено" : comment)
                .authorIp(senderIp)
                .build());
        int attached = messageFileService.attach(message, uploads).size();
        return new Saved(QuestionResponse.from(message), message.getId(), project.getId(),
                project.getOwner(), project.getName(), author, attached);
    }

    /**
     * The payment, through the one door, and the notice linked to it — in a transaction of its own,
     * so a refusal (the economy gate, say) leaves the notice PENDING for the master instead of losing
     * it. Answers whether the money is now recorded.
     */
    private boolean record(Saved saved, BigDecimal amount, LocalDate receivedAt, String label) {
        try {
            Transactions.write(transactionManager, () -> {
                ProjectMessage notice = messageRepository.findForUpdate(saved.messageId(), saved.projectId())
                        .orElseThrow(() -> new ResourceNotFoundException("Message not found"));
                recordInto(notice, saved.owner().getId(), amount, receivedAt, label);
                return null;
            });
            return true;
        } catch (RuntimeException e) {
            log.warn("Payment notice {} matched its receipt but could not be recorded: {}",
                    saved.messageId(), e.getMessage());
            return false;
        }
    }

    private void recordInto(ProjectMessage notice, UUID ownerId, BigDecimal amount, LocalDate receivedAt,
                            String label) {
        UUID receiptId = paymentService.addReceipt(notice.getProject().getId(), ownerId,
                new PaymentReceiptRequest(label, amount, receivedAt, false), null).getFirst().id();
        notice.setPaymentStatus(PaymentNoticeStatus.RECORDED);
        notice.setPaymentReceiptId(receiptId);
    }

    /** Fail-soft, as every client message is: the notice stands whether or not the push goes out. */
    private void push(Saved saved, BigDecimal amount, boolean recorded) {
        try {
            String who = saved.author() == null ? "Клієнт" : saved.author();
            String body = who + ": " + hryvnias(amount)
                    + (saved.attached() > 0 ? " · квитанція 📎" + saved.attached() : "")
                    + (recorded ? ". Квитанція збігається — записано в гроші об’єкта."
                                : ". Перевірте, чи прийшли кошти, і підтвердьте в грошах об’єкта.");
            // The amount is the client's own figure; the bot leaves out who sent it — the app shows that.
            masterNotifier.notify(saved.owner(), new MasterNotifier.Notice(
                    "💰 Повідомлення про оплату · " + saved.projectName(),
                    body,
                    "/projects/" + saved.projectId(),
                    "💰 Клієнт повідомив про оплату " + hryvnias(amount)
                            + "\nОбʼєкт: " + saved.projectName()
                            + (recorded ? "\nКвитанція збігається — записано в гроші обʼєкта."
                                        : "\nПеревірте, чи прийшли кошти, і підтвердьте в застосунку.")));
        } catch (RuntimeException e) {
            log.warn("Push for payment notice {} on project {} failed: {}",
                    saved.messageId(), saved.projectId(), e.getMessage());
        }
    }

    /** The object is his (404/403), and the message is a payment notice OF that object (else one 404). */
    private ProjectMessage lockedNotice(UUID projectId, UUID messageId, UUID ownerId) {
        projectService.loadOwned(projectId, ownerId);
        return messageRepository.findForUpdate(messageId, projectId)
                .filter(m -> m.getKind() == MessageKind.PAYMENT)
                .orElseThrow(() -> new ResourceNotFoundException("Payment notice not found: " + messageId));
    }

    /** The object behind a client-facing link that offers a way to pay, or one neutral 404. */
    private Project resolve(String token) {
        Project project = projectOf(token);
        if (project == null || PayoutDetails.of(project.getOwner(), project) == null) {
            throw new ResourceNotFoundException("Share link not found");
        }
        return project;
    }

    private Project projectOf(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        Instant now = Instant.now();
        // By token AND kind, one kind at a time (the repository's rule): a MESSAGE token is never
        // among them, so the form a supplier was sent can never post a payment notice.
        for (ShareLinkKind kind : CLIENT_KINDS) {
            ProjectShareLink link = projectLinks.findByTokenAndKind(token, kind).orElse(null);
            if (link == null) {
                continue;
            }
            if (!link.isUsable(now)) {
                return null;
            }
            // An act's link opens only while the act is on the client's side, as its page does.
            if (kind == ShareLinkKind.ACT && (link.getWorkAct() == null
                    || (link.getWorkAct().getStatus() != WorkActStatus.SENT
                        && link.getWorkAct().getStatus() != WorkActStatus.SIGNED))) {
                return null;
            }
            return link.getProject();
        }
        EstimateShareLink single = estimateLinks.findByToken(token).orElse(null);
        return single != null && single.isUsable(now) ? single.getEstimate().getProject() : null;
    }

    static String hryvnias(BigDecimal amount) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        return new DecimalFormat("#,##0.##", symbols).format(amount) + " ₴";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
