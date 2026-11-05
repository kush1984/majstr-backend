package com.majstr.backend.service;

import com.majstr.backend.dto.PaymentReceiptEditRequest;
import com.majstr.backend.dto.PaymentReceiptRequest;
import com.majstr.backend.dto.PaymentReceiptResponse;
import com.majstr.backend.dto.PaymentsSummaryResponse;
import com.majstr.backend.entity.PaymentReceipt;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.PaymentValidationException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.EstimateRepository;
import com.majstr.backend.repository.PaymentReceiptRepository;
import com.majstr.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Money the client paid on an object ({@link PaymentReceipt}, V100). {@link #addReceipt} is the one
 * path money enters through.
 *
 * <p><b>The payment plan is gone</b> (2026-11-05): no stages, no «Розбити на частки», no overflow
 * resolution, no surplus transfer. Prod held eight stages on 150 objects, every one a «завдаток».
 * The {@code project_payment} rows that exist stay, read-only: a receipt recorded against one has no
 * label of its own and is still named after it ({@link PaymentReceiptResponse#from}).</p>
 *
 * <p>Every MUTATION requires {@link Feature#OBJECT_ECONOMY}; {@link #summary} is left ungated — the
 * "data survives, only the gate closes" rule {@link ObjectExpenseService} documents for expenses.</p>
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentReceiptRepository receiptRepository;
    private final EstimateRepository estimateRepository;
    private final MaterialRefundCalculator refundCalculator;
    private final ProjectService projectService;
    private final UserRepository userRepository;
    private final FeatureGuard featureGuard;

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

    private PaymentsSummaryResponse buildSummary(UUID objectId) {
        BigDecimal contracted = estimateRepository.sumIncomeCounted(objectId);
        List<PaymentReceiptResponse> receipts = receiptRepository
                .findByProjectIdOrderByReceivedAtAscCreatedAtAsc(objectId)
                .stream().map(PaymentReceiptResponse::from).toList();
        BigDecimal totalReceived = receipts.stream().map(PaymentReceiptResponse::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // A refund pays off MATERIAL, never work (B-65). Measuring «За договором» against the gross
        // received let a 2 000 ₴ reimbursement close 2 000 ₴ of unpaid work and announce «Усе
        // сплачено» over it, while the materials card went on asking for the money it had just been
        // handed. Since the object-money iteration ALL material money stays material, including
        // what the client handed over before any receipt existed.
        MaterialRefundSplit split = refundCalculator.forObject(objectId);
        BigDecimal workPaid = split.workPaid(totalReceived);
        BigDecimal remaining = contracted.subtract(workPaid).max(BigDecimal.ZERO);
        BigDecimal overpaid = workPaid.subtract(contracted).max(BigDecimal.ZERO);
        return new PaymentsSummaryResponse(contracted, totalReceived, remaining, split.refunds(),
                split.applied(), workPaid, overpaid, receipts);
    }

    /** Register a received payment. Answers a one-element list — the wire shape from when a TRANSFER
     *  overflow wrote two rows, kept so an app build already on a phone still reads the answer. */
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
        PaymentReceipt receipt = PaymentReceipt.builder()
                .id(requestedId).project(object).amount(req.amount())
                .receivedAt(req.receivedAt()).materialRefund(req.materialRefund())
                .label(requireLabel(req.label())).build();
        return List.of(PaymentReceiptResponse.from(receiptRepository.save(receipt)));
    }

    /** Amount/date/label. A receipt recorded against an old plan stage keeps the stage's name — it
     *  has no label of its own to edit. */
    @Transactional
    public PaymentReceiptResponse editReceipt(UUID objectId, UUID receiptId, UUID ownerId,
                                               PaymentReceiptEditRequest req) {
        requireEconomy(objectId, ownerId);
        PaymentReceipt receipt = loadReceipt(objectId, receiptId);
        if (receipt.getPlanPayment() == null) {
            receipt.setLabel(requireLabel(req.label()));
        }
        requireNoRefundOnStage(Boolean.TRUE.equals(req.materialRefund()), receipt.getPlanPayment() != null);
        receipt.setAmount(req.amount());
        receipt.setReceivedAt(req.receivedAt());
        // null = leave it alone: the object economy's edit sheet never asks about a material
        // refund, so only the caller that owns the switch («Мої гроші») may move it.
        if (req.materialRefund() != null) {
            receipt.setMaterialRefund(req.materialRefund());
        }
        return PaymentReceiptResponse.from(receipt);
    }

    /**
     * A material refund is never a payment for a planned stage (review B-102, owner ruling: refuse).
     * A stage is a share of the WORK; counted against it, 2 000 paid back for tiles closed a 6 800
     * stage with 2 000 of work still owed. Only a receipt recorded against an old stage can hit this.
     */
    private static void requireNoRefundOnStage(boolean materialRefund, boolean onStage) {
        if (materialRefund && onStage) {
            throw new PaymentValidationException("error.payment.refund-on-stage");
        }
    }

    /** Idempotent: deleting an already-gone receipt is a no-op. */
    @Transactional
    public void deleteReceipt(UUID objectId, UUID receiptId, UUID ownerId) {
        requireEconomy(objectId, ownerId);
        receiptRepository.findByIdAndProjectId(receiptId, objectId).ifPresent(receiptRepository::delete);
    }

    private static String requireLabel(String label) {
        String trimmed = label == null ? "" : label.trim();
        if (trimmed.isEmpty()) {
            throw new PaymentValidationException("error.payment.label-required");
        }
        return trimmed;
    }

    private PaymentReceipt loadReceipt(UUID objectId, UUID receiptId) {
        return receiptRepository.findByIdAndProjectId(receiptId, objectId)
                .orElseThrow(() -> new ResourceNotFoundException("Receipt not found: " + receiptId));
    }

    /** Plan gate THEN ownership, same order {@link ObjectExpenseService#requireEconomy} uses for
     *  the expense journal. Gate is {@code Feature.OBJECT_ECONOMY} — currently granted to every
     *  plan including FREE (TEMPORARY, see the comment on {@code Plan.FREE} in
     *  {@link com.majstr.backend.feature.PlanConfig}), so this passes for everyone right now; the
     *  check stays wired so reverting the plan matrix alone re-enables the block. */
    private Project requireEconomy(UUID objectId, UUID ownerId) {
        User user = userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
        featureGuard.requireFeature(user, Feature.OBJECT_ECONOMY);
        return projectService.loadOwned(objectId, ownerId);
    }
}
