package com.majstr.backend.controller;

import com.majstr.backend.dto.PaymentReceiptEditRequest;
import com.majstr.backend.dto.PaymentReceiptRequest;
import com.majstr.backend.dto.PaymentReceiptResponse;
import com.majstr.backend.dto.PaymentSplitPreviewResponse;
import com.majstr.backend.dto.PaymentSplitRequest;
import com.majstr.backend.dto.PaymentSurplusTransferRequest;
import com.majstr.backend.dto.PaymentsSummaryResponse;
import com.majstr.backend.dto.ProjectPaymentRequest;
import com.majstr.backend.dto.ProjectPaymentResponse;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Object-level payments (завдаток + графік виплат) — FREE and PRO alike, unlike
 * {@code ObjectExpenseController} (PRO-only internal economy). Owner-scoped only.
 */
@RestController
@RequestMapping("/api/projects/{id}/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Object-level payment schedule (завдаток + графік), FREE + PRO")
@SecurityRequirement(name = "bearer-jwt")
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "List the object's payments (schedule order)")
    @GetMapping
    public List<ProjectPaymentResponse> list(@PathVariable UUID id,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        return paymentService.list(id, principal.id());
    }

    @Operation(summary = "Contracted / received / remaining + the payment list — the FREE money summary")
    @GetMapping("/summary")
    public PaymentsSummaryResponse summary(@PathVariable UUID id,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        return paymentService.summary(id, principal.id());
    }

    @Operation(summary = "Add a payment row (manual, or a завдаток already received)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectPaymentResponse add(@PathVariable UUID id,
                                      @Valid @RequestBody ProjectPaymentRequest req,
                                      @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return paymentService.add(id, principal.id(), req, entityId);
    }

    @Operation(summary = "Edit a payment row (amount, due date, next stage, or mark received)")
    @PatchMapping("/{paymentId}")
    public ProjectPaymentResponse update(@PathVariable UUID id,
                                         @PathVariable UUID paymentId,
                                         @Valid @RequestBody ProjectPaymentRequest req,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        return paymentService.update(id, paymentId, principal.id(), req);
    }

    @Operation(summary = "Delete a payment row")
    @DeleteMapping("/{paymentId}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @PathVariable UUID paymentId,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        paymentService.delete(id, paymentId, principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Preview a \"Розбити на частки\" split before saving — nothing is written")
    @PostMapping("/split/preview")
    public PaymentSplitPreviewResponse previewSplit(@PathVariable UUID id,
                                                     @Valid @RequestBody PaymentSplitRequest req,
                                                     @AuthenticationPrincipal UserPrincipal principal) {
        return paymentService.previewSplit(id, principal.id(), req);
    }

    @Operation(summary = "Confirm the split — persists the same rows the preview showed")
    @PostMapping("/split/commit")
    @ResponseStatus(HttpStatus.CREATED)
    public List<ProjectPaymentResponse> commitSplit(@PathVariable UUID id,
                                                     @Valid @RequestBody PaymentSplitRequest req,
                                                     @AuthenticationPrincipal UserPrincipal principal) {
        return paymentService.commitSplit(id, principal.id(), req);
    }

    @Operation(summary = "Register a received payment ('Отриманий платіж') against a plan stage, or unplanned")
    @PostMapping("/receipts")
    @ResponseStatus(HttpStatus.CREATED)
    public List<PaymentReceiptResponse> addReceipt(@PathVariable UUID id,
                                                    @Valid @RequestBody PaymentReceiptRequest req,
                                                    @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        return paymentService.addReceipt(id, principal.id(), req, entityId);
    }

    @Operation(summary = "Edit a receipt's amount/date/label")
    @PatchMapping("/receipts/{receiptId}")
    public PaymentReceiptResponse editReceipt(@PathVariable UUID id,
                                              @PathVariable UUID receiptId,
                                              @Valid @RequestBody PaymentReceiptEditRequest req,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        return paymentService.editReceipt(id, receiptId, principal.id(), req);
    }

    @Operation(summary = "Delete a receipt — its stage recomputes on the next read")
    @DeleteMapping("/receipts/{receiptId}")
    public ResponseEntity<Void> deleteReceipt(@PathVariable UUID id,
                                              @PathVariable UUID receiptId,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        paymentService.deleteReceipt(id, receiptId, principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Move an over-received stage's surplus onto another stage as a partial receipt")
    @PostMapping("/receipts/transfer-surplus")
    public List<ProjectPaymentResponse> transferSurplus(@PathVariable UUID id,
                                                         @Valid @RequestBody PaymentSurplusTransferRequest req,
                                                         @AuthenticationPrincipal UserPrincipal principal) {
        return paymentService.transferSurplus(id, principal.id(), req);
    }
}
