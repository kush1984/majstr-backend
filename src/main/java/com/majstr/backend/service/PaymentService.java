package com.majstr.backend.service;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.PaymentReceiptEditRequest;
import com.majstr.backend.dto.PaymentReceiptRequest;
import com.majstr.backend.dto.PaymentReceiptResponse;
import com.majstr.backend.dto.PaymentSplitPreviewResponse;
import com.majstr.backend.dto.PaymentSplitRequest;
import com.majstr.backend.dto.PaymentSplitRow;
import com.majstr.backend.dto.PaymentSurplusTransferRequest;
import com.majstr.backend.dto.PaymentsSummaryResponse;
import com.majstr.backend.dto.ProjectPaymentRequest;
import com.majstr.backend.dto.ProjectPaymentResponse;
import com.majstr.backend.entity.PaymentOverflowResolution;
import com.majstr.backend.entity.PaymentReceipt;
import com.majstr.backend.entity.PaymentSplitPreset;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectPayment;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.PaymentSplitException;
import com.majstr.backend.exception.PaymentValidationException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.PaymentReceiptRepository;
import com.majstr.backend.repository.ProjectPaymentRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Object-level payments (завдаток + графік виплат) — see {@link ProjectPayment} (PLAN) and
 * {@link PaymentReceipt} (FACT, V100).
 *
 * <p><b>Economy-polish iteration:</b> every MUTATION here (create/edit/delete a plan row, a
 * receipt, or a split) requires {@link Feature#OBJECT_ECONOMY}, same as the expense journal — a
 * FREE master can no longer build a payment schedule he can't otherwise see (the object-economy
 * response now nulls its embedded {@code payments} for FREE too, see
 * {@link ObjectExpenseService#economy}). {@link #list}/{@link #summary} are left ungated — an
 * existing PRO-created schedule stays readable after a downgrade, the same "data survives, only
 * the gate closes" rule {@link ObjectExpenseService} documents for expenses.</p>
 *
 * <p><b>Payments PLAN/FACT split (V100).</b> A plan stage ({@link ProjectPayment}) can now be
 * closed by several {@link PaymentReceipt}s (2 000, then 3 000 = closed) instead of the old single
 * {@code paidAmount}/{@code paidAt} pair. {@link #addReceipt} is the one path money enters through
 * — "mark received" and the old "Вже отримано" one-step create both route through it now. An
 * overpayment (receipt amount exceeds the targeted stage's remaining balance) requires the caller
 * to say how to resolve it ({@link PaymentOverflowResolution}); the PWA computes the overflow
 * itself (it already holds the summary) and shows the choice before ever calling this, so reaching
 * the "resolution required" error here means stale client state, not the normal flow.</p>
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final Map<PaymentSplitPreset, List<BigDecimal>> PRESET_PERCENTS = Map.of(
            PaymentSplitPreset.FIFTY_FIFTY, List.of(bd(50), bd(50)),
            PaymentSplitPreset.THIRTY_FORTY_THIRTY, List.of(bd(30), bd(40), bd(30)),
            PaymentSplitPreset.THIRTY_THIRTY_FORTY, List.of(bd(30), bd(30), bd(40))
    );

    private static final Map<PaymentSplitPreset, List<String>> PRESET_PURPOSES = Map.of(
            PaymentSplitPreset.FIFTY_FIFTY, List.of("Аванс", "Фінал"),
            PaymentSplitPreset.THIRTY_FORTY_THIRTY, List.of("Аванс", "Після чорнових", "Фінал"),
            PaymentSplitPreset.THIRTY_THIRTY_FORTY, List.of("Аванс", "Після чорнових", "Фінал")
    );

    private final ProjectPaymentRepository paymentRepository;
    private final PaymentReceiptRepository receiptRepository;
    private final EstimateRepository estimateRepository;
    private final ProjectService projectService;
    private final UserRepository userRepository;
    private final FeatureGuard featureGuard;

    @Transactional(readOnly = true)
    public List<ProjectPaymentResponse> list(UUID objectId, UUID ownerId) {
        projectService.loadOwned(objectId, ownerId);
        return buildSummary(objectId).payments();
    }

    @Transactional(readOnly = true)
    public PaymentsSummaryResponse summary(UUID objectId, UUID ownerId) {
        projectService.loadOwned(objectId, ownerId);
        return buildSummary(objectId);
    }

    /** Skips the ownership check — for callers (e.g. {@code ObjectExpenseService.economy}) that
     *  already loaded and verified the object themselves, to avoid a redundant query. */
    @Transactional(readOnly = true)
    public PaymentsSummaryResponse summaryUnchecked(UUID objectId) {
        return buildSummary(objectId);
    }

    /** One receipts query, grouped in memory by plan stage (or left unplanned) — building the
     *  whole summary this way is O(2) queries regardless of how many stages/receipts exist. */
    private PaymentsSummaryResponse buildSummary(UUID objectId) {
        BigDecimal contracted = estimateRepository.sumIncomeCounted(objectId);
        LocalDate today = today();
        List<ProjectPayment> plans = paymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(objectId);
        List<PaymentReceipt> allReceipts = receiptRepository.findByProjectIdOrderByReceivedAtAscCreatedAtAsc(objectId);

        Map<UUID, List<PaymentReceiptResponse>> byPlan = new LinkedHashMap<>();
        List<PaymentReceiptResponse> unplanned = new ArrayList<>();
        BigDecimal totalReceived = BigDecimal.ZERO;
        for (PaymentReceipt r : allReceipts) {
            PaymentReceiptResponse dto = PaymentReceiptResponse.from(r);
            totalReceived = totalReceived.add(r.getAmount());
            if (r.getPlanPayment() != null) {
                byPlan.computeIfAbsent(r.getPlanPayment().getId(), k -> new ArrayList<>()).add(dto);
            } else {
                unplanned.add(dto);
            }
        }

        List<ProjectPaymentResponse> payments = new ArrayList<>();
        for (ProjectPayment p : plans) {
            List<PaymentReceiptResponse> receipts = byPlan.getOrDefault(p.getId(), List.of());
            BigDecimal received = sum(receipts);
            payments.add(ProjectPaymentResponse.from(p, today, received, receipts));
        }

        BigDecimal remaining = contracted.subtract(totalReceived).max(BigDecimal.ZERO);
        return new PaymentsSummaryResponse(contracted, totalReceived, remaining, payments, unplanned);
    }

    @Transactional
    public ProjectPaymentResponse add(UUID objectId, UUID ownerId, ProjectPaymentRequest req, UUID requestedId) {
        Project object = requireEconomy(objectId, ownerId);
        if (requestedId != null) {
            var existing = paymentRepository.findById(requestedId);
            if (existing.isPresent()) {
                if (!existing.get().getProject().getId().equals(objectId)) {
                    throw new AccessDeniedException("Payment belongs to a different object");
                }
                return responseFor(existing.get(), today()); // idempotent replay
            }
        }
        ProjectPayment payment = ProjectPayment.builder()
                .id(requestedId)
                .project(object)
                .amount(req.amount())
                .dueDate(req.dueDate())
                .nextStage(trimToNull(req.nextStage()))
                .purpose(req.purpose().trim())
                .sortOrder(paymentRepository.nextSortOrder(objectId))
                .build();
        return responseFor(paymentRepository.save(payment), today());
    }

    @Transactional
    public ProjectPaymentResponse update(UUID objectId, UUID paymentId, UUID ownerId, ProjectPaymentRequest req) {
        requireEconomy(objectId, ownerId);
        ProjectPayment payment = loadPayment(objectId, paymentId);
        payment.setAmount(req.amount());
        payment.setDueDate(req.dueDate());
        payment.setNextStage(trimToNull(req.nextStage()));
        payment.setPurpose(req.purpose().trim());
        return responseFor(payment, today());
    }

    /** Idempotent: a replayed offline delete of an already-gone payment is a no-op, not a 404.
     *  Its receipts are NOT deleted (V100 FK is ON DELETE SET NULL) — real received money must
     *  survive the removal of the plan row it was closing; it becomes an unplanned receipt. */
    @Transactional
    public void delete(UUID objectId, UUID paymentId, UUID ownerId) {
        requireEconomy(objectId, ownerId);
        paymentRepository.findByIdAndProjectId(paymentId, objectId).ifPresent(paymentRepository::delete);
    }

    private ProjectPaymentResponse responseFor(ProjectPayment p, LocalDate today) {
        List<PaymentReceiptResponse> receipts = receiptRepository
                .findByPlanPaymentIdOrderByReceivedAtAscCreatedAtAsc(p.getId())
                .stream().map(PaymentReceiptResponse::from).toList();
        return ProjectPaymentResponse.from(p, today, sum(receipts), receipts);
    }

    // -----------------------------------------------------------------------------------------
    // FACT — payment_receipt (V100). The one path money enters through.
    // -----------------------------------------------------------------------------------------

    /** Register a received payment. Closes {@code req.planPaymentId()} (partially or fully), or
     *  stands alone as "Своє" when it's null. Returns one row normally, two for a TRANSFER
     *  overflow (the closing receipt on this stage, then the surplus on the next one). */
    @Transactional
    public List<PaymentReceiptResponse> addReceipt(UUID objectId, UUID ownerId, PaymentReceiptRequest req,
                                                     UUID requestedId) {
        Project object = requireEconomy(objectId, ownerId);
        if (requestedId != null) {
            var existing = receiptRepository.findById(requestedId);
            if (existing.isPresent()) {
                if (!existing.get().getProject().getId().equals(objectId)) {
                    throw new AccessDeniedException("Receipt belongs to a different object");
                }
                return List.of(PaymentReceiptResponse.from(existing.get())); // idempotent replay
            }
        }

        if (req.planPaymentId() == null) {
            String label = requireLabel(req.label());
            validateUnplannedLabel(objectId, label);
            PaymentReceipt receipt = PaymentReceipt.builder()
                    .id(requestedId).project(object).amount(req.amount()).receivedAt(req.receivedAt())
                    .label(label).build();
            return List.of(PaymentReceiptResponse.from(receiptRepository.save(receipt)));
        }

        ProjectPayment stage = loadPayment(objectId, req.planPaymentId());
        BigDecimal receivedSoFar = receiptRepository.sumByPlanPaymentId(stage.getId());
        BigDecimal remaining = stage.getAmount().subtract(receivedSoFar).max(BigDecimal.ZERO);
        BigDecimal overflow = req.amount().subtract(remaining);

        if (overflow.signum() <= 0) {
            PaymentReceipt receipt = PaymentReceipt.builder()
                    .id(requestedId).project(object).planPayment(stage).amount(req.amount())
                    .receivedAt(req.receivedAt()).build();
            return List.of(PaymentReceiptResponse.from(receiptRepository.save(receipt)));
        }

        if (req.resolution() == null) {
            throw new PaymentValidationException("error.payment.overflow-resolution-required");
        }
        return switch (req.resolution()) {
            case RESERVE -> {
                PaymentReceipt receipt = PaymentReceipt.builder()
                        .id(requestedId).project(object).planPayment(stage).amount(req.amount())
                        .receivedAt(req.receivedAt()).build();
                yield List.of(PaymentReceiptResponse.from(receiptRepository.save(receipt)));
            }
            case INCREASE -> {
                stage.setAmount(receivedSoFar.add(req.amount()));
                PaymentReceipt receipt = PaymentReceipt.builder()
                        .id(requestedId).project(object).planPayment(stage).amount(req.amount())
                        .receivedAt(req.receivedAt()).build();
                yield List.of(PaymentReceiptResponse.from(receiptRepository.save(receipt)));
            }
            case TRANSFER -> {
                ProjectPayment next = findNextOpenStage(objectId, stage)
                        .orElseThrow(() -> new PaymentValidationException("error.payment.no-next-stage"));
                List<PaymentReceiptResponse> result = new ArrayList<>();
                if (remaining.signum() > 0) {
                    PaymentReceipt closing = PaymentReceipt.builder()
                            .id(requestedId).project(object).planPayment(stage).amount(remaining)
                            .receivedAt(req.receivedAt()).build();
                    result.add(PaymentReceiptResponse.from(receiptRepository.save(closing)));
                }
                PaymentReceipt surplus = PaymentReceipt.builder()
                        .project(object).planPayment(next).amount(overflow)
                        .receivedAt(req.receivedAt()).build();
                result.add(PaymentReceiptResponse.from(receiptRepository.save(surplus)));
                yield result;
            }
        };
    }

    /** Amount/date/label only — which stage a receipt closes is fixed at creation. */
    @Transactional
    public PaymentReceiptResponse editReceipt(UUID objectId, UUID receiptId, UUID ownerId,
                                               PaymentReceiptEditRequest req) {
        requireEconomy(objectId, ownerId);
        PaymentReceipt receipt = loadReceipt(objectId, receiptId);
        if (receipt.getPlanPayment() == null) {
            String label = requireLabel(req.label());
            validateUnplannedLabel(objectId, label);
            receipt.setLabel(label);
        }
        receipt.setAmount(req.amount());
        receipt.setReceivedAt(req.receivedAt());
        return PaymentReceiptResponse.from(receipt);
    }

    /** Idempotent: deleting an already-gone receipt is a no-op. The stage it closed recomputes
     *  its status/received on the next read — nothing to update explicitly (derived, not stored). */
    @Transactional
    public void deleteReceipt(UUID objectId, UUID receiptId, UUID ownerId) {
        requireEconomy(objectId, ownerId);
        receiptRepository.findByIdAndProjectId(receiptId, objectId).ifPresent(receiptRepository::delete);
    }

    /** "На «{from.purpose}» отримано більше на {надлишок} — перенести сюди як частково оплачену?"
     *  — the follow-up prompt for a RESERVE overpayment, offered when the master creates a new
     *  plan stage while another one is sitting over-received. Reduces {@code from}'s receipts
     *  from most-recently-created backwards until the surplus is covered (deleting one outright
     *  if it's fully consumed), then posts one new receipt of that surplus onto {@code to}. */
    @Transactional
    public List<ProjectPaymentResponse> transferSurplus(UUID objectId, UUID ownerId,
                                                          PaymentSurplusTransferRequest req) {
        requireEconomy(objectId, ownerId);
        ProjectPayment from = loadPayment(objectId, req.fromPaymentId());
        ProjectPayment to = loadPayment(objectId, req.toPaymentId());
        List<PaymentReceipt> fromReceipts = receiptRepository
                .findByPlanPaymentIdOrderByReceivedAtAscCreatedAtAsc(from.getId());
        BigDecimal receivedSoFar = fromReceipts.stream().map(PaymentReceipt::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal surplus = receivedSoFar.subtract(from.getAmount());
        if (surplus.signum() <= 0) {
            throw new PaymentValidationException("error.payment.no-surplus");
        }

        BigDecimal remainingSurplus = surplus;
        for (int i = fromReceipts.size() - 1; i >= 0 && remainingSurplus.signum() > 0; i--) {
            PaymentReceipt r = fromReceipts.get(i);
            BigDecimal take = r.getAmount().min(remainingSurplus);
            if (take.compareTo(r.getAmount()) == 0) {
                receiptRepository.delete(r);
            } else {
                r.setAmount(r.getAmount().subtract(take));
            }
            remainingSurplus = remainingSurplus.subtract(take);
        }

        PaymentReceipt surplusReceipt = PaymentReceipt.builder()
                .project(from.getProject()).planPayment(to).amount(surplus).receivedAt(today()).build();
        receiptRepository.save(surplusReceipt);

        LocalDate today = today();
        return List.of(responseFor(from, today), responseFor(to, today));
    }

    /** The next plan stage (by schedule order) that isn't fully received yet — where a TRANSFER
     *  overflow lands. A project realistically holds a handful of stages, so one query per
     *  candidate is simpler than a bulk grouped query for this rare path. */
    private Optional<ProjectPayment> findNextOpenStage(UUID objectId, ProjectPayment current) {
        List<ProjectPayment> ordered = paymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(objectId);
        boolean pastCurrent = false;
        for (ProjectPayment candidate : ordered) {
            if (!pastCurrent) {
                pastCurrent = candidate.getId().equals(current.getId());
                continue;
            }
            BigDecimal received = receiptRepository.sumByPlanPaymentId(candidate.getId());
            if (received.compareTo(candidate.getAmount()) < 0) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /** An unplanned receipt's name must not read as one of the object's own plan stages — that
     *  is exactly the "Аванс/Аванс" duplicate confusion this whole model exists to remove. */
    private void validateUnplannedLabel(UUID objectId, String label) {
        boolean conflict = paymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(objectId).stream()
                .anyMatch(p -> p.getPurpose().equalsIgnoreCase(label));
        if (conflict) {
            throw new PaymentValidationException("error.payment.label-conflict");
        }
    }

    private static String requireLabel(String label) {
        String trimmed = trimToNull(label);
        if (trimmed == null) {
            throw new PaymentValidationException("error.payment.label-required");
        }
        return trimmed;
    }

    private PaymentReceipt loadReceipt(UUID objectId, UUID receiptId) {
        return receiptRepository.findByIdAndProjectId(receiptId, objectId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found: " + receiptId));
    }

    private static BigDecimal sum(List<PaymentReceiptResponse> receipts) {
        return receipts.stream().map(PaymentReceiptResponse::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Pure calculation — nothing saved, so the master can preview before committing. */
    @Transactional(readOnly = true)
    public PaymentSplitPreviewResponse previewSplit(UUID objectId, UUID ownerId, PaymentSplitRequest req) {
        requireEconomy(objectId, ownerId);
        BigDecimal contracted = estimateRepository.sumIncomeCounted(objectId);
        return new PaymentSplitPreviewResponse(contracted, computeSplitRows(contracted, req));
    }

    /** Persists exactly what {@link #previewSplit} would show — same computation, so the master
     *  never sees one set of numbers and gets another. Dates/next-stage are left blank; the
     *  master fills those in per row afterwards (a share is a guess, a due date is a promise). */
    @Transactional
    public List<ProjectPaymentResponse> commitSplit(UUID objectId, UUID ownerId, PaymentSplitRequest req) {
        Project object = requireEconomy(objectId, ownerId);
        BigDecimal contracted = estimateRepository.sumIncomeCounted(objectId);
        List<PaymentSplitRow> rows = computeSplitRows(contracted, req);
        int nextSort = paymentRepository.nextSortOrder(objectId);
        LocalDate today = today();
        List<ProjectPaymentResponse> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            PaymentSplitRow row = rows.get(i);
            ProjectPayment payment = ProjectPayment.builder()
                    .project(object)
                    .amount(row.amount())
                    .purpose(row.purpose())
                    .sortOrder(nextSort + i)
                    .build();
            result.add(ProjectPaymentResponse.from(paymentRepository.save(payment), today, BigDecimal.ZERO, List.of()));
        }
        return result;
    }

    private List<PaymentSplitRow> computeSplitRows(BigDecimal contracted, PaymentSplitRequest req) {
        List<BigDecimal> percents;
        List<String> purposes;
        if (req.preset() == PaymentSplitPreset.CUSTOM) {
            percents = req.customPercents() == null ? List.of() : req.customPercents();
            if (percents.isEmpty() || percents.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                    .compareTo(BigDecimal.valueOf(100)) != 0) {
                throw new PaymentSplitException("error.payment.split-percents");
            }
            purposes = purposesFor(percents.size());
        } else {
            percents = PRESET_PERCENTS.get(req.preset());
            purposes = PRESET_PURPOSES.get(req.preset());
        }
        List<PaymentSplitRow> rows = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < percents.size(); i++) {
            BigDecimal amount;
            if (i == percents.size() - 1) {
                // The last row absorbs the rounding remainder so Σ rows == contracted exactly.
                amount = contracted.subtract(allocated).setScale(2, RoundingMode.HALF_UP);
            } else {
                amount = contracted.multiply(percents.get(i))
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                allocated = allocated.add(amount);
            }
            rows.add(new PaymentSplitRow(purposes.get(i), amount));
        }
        return rows;
    }

    /** «Аванс» first, «Фінал» last, anything in between a numbered «Проміжний платіж». */
    private static List<String> purposesFor(int count) {
        if (count == 1) {
            return List.of("Повна сума");
        }
        List<String> purposes = new ArrayList<>();
        purposes.add("Аванс");
        for (int i = 1; i < count - 1; i++) {
            purposes.add("Проміжний платіж " + i);
        }
        purposes.add("Фінал");
        return purposes;
    }

    private ProjectPayment loadPayment(UUID objectId, UUID paymentId) {
        return paymentRepository.findByIdAndProjectId(paymentId, objectId)
                .orElseThrow(() -> new ResourceNotFoundException("Payment not found: " + paymentId));
    }

    /** Plan gate (PRO+) THEN ownership — a FREE master is refused before any object read, same
     *  order {@link ObjectExpenseService#requireEconomy} uses for the expense journal. */
    private Project requireEconomy(UUID objectId, UUID ownerId) {
        User user = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
        featureGuard.requireFeature(user, Feature.OBJECT_ECONOMY);
        return projectService.loadOwned(objectId, ownerId);
    }

    private static LocalDate today() {
        return LocalDate.now(LocalizationConfig.ZONE);
    }

    private static BigDecimal bd(int v) {
        return BigDecimal.valueOf(v);
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
