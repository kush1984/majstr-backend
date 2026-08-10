package com.majstr.backend.service;

import com.majstr.backend.config.LocalizationConfig;
import com.majstr.backend.dto.PaymentSplitPreviewResponse;
import com.majstr.backend.dto.PaymentSplitRequest;
import com.majstr.backend.dto.PaymentSplitRow;
import com.majstr.backend.dto.PaymentsSummaryResponse;
import com.majstr.backend.dto.ProjectPaymentRequest;
import com.majstr.backend.dto.ProjectPaymentResponse;
import com.majstr.backend.entity.PaymentSplitPreset;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectPayment;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.PaymentSplitException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.repository.EstimateRepository;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Object-level payments (завдаток + графік виплат) — see {@link ProjectPayment}.
 *
 * <p><b>Economy-polish iteration:</b> every MUTATION here (create a row, edit/mark-received,
 * delete, split) now requires {@link Feature#OBJECT_ECONOMY}, same as the expense journal — a
 * FREE master can no longer build a payment schedule he can't otherwise see (the object-economy
 * response now nulls its embedded {@code payments} for FREE too, see
 * {@link ObjectExpenseService#economy}). {@link #list}/{@link #summary} are left ungated — an
 * existing PRO-created schedule stays readable after a downgrade, the same "data survives, only
 * the gate closes" rule {@link ObjectExpenseService} documents for expenses.</p>
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
    private final EstimateRepository estimateRepository;
    private final ProjectService projectService;
    private final UserRepository userRepository;
    private final FeatureGuard featureGuard;

    @Transactional(readOnly = true)
    public List<ProjectPaymentResponse> list(UUID objectId, UUID ownerId) {
        projectService.loadOwned(objectId, ownerId);
        LocalDate today = today();
        return paymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(objectId).stream()
                .map(p -> ProjectPaymentResponse.from(p, today))
                .toList();
    }

    @Transactional(readOnly = true)
    public PaymentsSummaryResponse summary(UUID objectId, UUID ownerId) {
        projectService.loadOwned(objectId, ownerId);
        return summaryUnchecked(objectId);
    }

    /** Skips the ownership check — for callers (e.g. {@code ObjectExpenseService.economy}) that
     *  already loaded and verified the object themselves, to avoid a redundant query. */
    @Transactional(readOnly = true)
    public PaymentsSummaryResponse summaryUnchecked(UUID objectId) {
        BigDecimal contracted = estimateRepository.sumIncomeCounted(objectId);
        BigDecimal received = paymentRepository.sumPaidByProjectId(objectId);
        BigDecimal remaining = contracted.subtract(received).max(BigDecimal.ZERO);
        LocalDate today = today();
        List<ProjectPaymentResponse> payments = paymentRepository.findByProjectIdOrderBySortOrderAscIdAsc(objectId)
                .stream().map(p -> ProjectPaymentResponse.from(p, today)).toList();
        return new PaymentsSummaryResponse(contracted, received, remaining, payments);
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
                return ProjectPaymentResponse.from(existing.get(), today()); // idempotent replay
            }
        }
        ProjectPayment payment = ProjectPayment.builder()
                .id(requestedId)
                .project(object)
                .amount(req.amount())
                .dueDate(req.dueDate())
                .nextStage(trimToNull(req.nextStage()))
                .purpose(req.purpose().trim())
                .paidAmount(req.paidAmount())
                .paidAt(req.paidAt())
                .sortOrder(paymentRepository.nextSortOrder(objectId))
                .build();
        return ProjectPaymentResponse.from(paymentRepository.save(payment), today());
    }

    @Transactional
    public ProjectPaymentResponse update(UUID objectId, UUID paymentId, UUID ownerId, ProjectPaymentRequest req) {
        requireEconomy(objectId, ownerId);
        ProjectPayment payment = loadPayment(objectId, paymentId);
        payment.setAmount(req.amount());
        payment.setDueDate(req.dueDate());
        payment.setNextStage(trimToNull(req.nextStage()));
        payment.setPurpose(req.purpose().trim());
        payment.setPaidAmount(req.paidAmount());
        payment.setPaidAt(req.paidAt());
        return ProjectPaymentResponse.from(payment, today());
    }

    /** Idempotent: a replayed offline delete of an already-gone payment is a no-op, not a 404. */
    @Transactional
    public void delete(UUID objectId, UUID paymentId, UUID ownerId) {
        requireEconomy(objectId, ownerId);
        paymentRepository.findByIdAndProjectId(paymentId, objectId).ifPresent(paymentRepository::delete);
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
            result.add(ProjectPaymentResponse.from(paymentRepository.save(payment), today));
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
