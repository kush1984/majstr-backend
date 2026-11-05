package com.majstr.backend.controller;

import com.majstr.backend.dto.PaymentReceiptEditRequest;
import com.majstr.backend.dto.PaymentReceiptRequest;
import com.majstr.backend.dto.PaymentReceiptResponse;
import com.majstr.backend.dto.PaymentsSummaryResponse;
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
 * Money the client paid on an object — FREE and PRO alike. Owner-scoped only. The payment plan
 * (stages, «Розбити на частки», moving a surplus between stages) is gone: a payment is recorded,
 * edited and deleted, nothing else.
 */
@RestController
@RequestMapping("/api/projects/{id}/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Money the client paid on an object, FREE + PRO")
@SecurityRequirement(name = "bearer-jwt")
public class PaymentController {

    private final PaymentService paymentService;

    @Operation(summary = "Contracted / received / remaining + the received payments")
    @GetMapping("/summary")
    public PaymentsSummaryResponse summary(@PathVariable UUID id,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        return paymentService.summary(id, principal.id());
    }

    @Operation(summary = "Register a received payment ('Отриманий платіж')")
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

    @Operation(summary = "Delete a receipt")
    @DeleteMapping("/receipts/{receiptId}")
    public ResponseEntity<Void> deleteReceipt(@PathVariable UUID id,
                                              @PathVariable UUID receiptId,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        paymentService.deleteReceipt(id, receiptId, principal.id());
        return ResponseEntity.noContent().build();
    }

}
