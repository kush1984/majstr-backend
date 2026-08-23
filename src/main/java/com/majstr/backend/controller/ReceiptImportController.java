package com.majstr.backend.controller;

import com.majstr.backend.dto.EstimateImportParseResponse;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.FiscalQrRequest;
import com.majstr.backend.dto.ReceiptItemsCommitRequest;
import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.importer.ReceiptImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * Add line items to an open estimate from a receipt photo via LLM vision (PRO-gated —
 * {@code Feature.RECEIPT_IMPORT}). {@code /parse} returns a review proposal (the image is
 * parsed then discarded); {@code /commit} appends the confirmed lines to the estimate
 * (SIGNED → 409). No catalog side-effect. Feature gate + ownership enforced in the service.
 */
@RestController
@RequestMapping("/api/estimates/{id}/receipt-items")
@RequiredArgsConstructor
@Tag(name = "Receipt import", description = "Add items to an estimate from a receipt photo (LLM)")
@SecurityRequirement(name = "bearer-jwt")
public class ReceiptImportController {

    private final ReceiptImportService receiptService;

    @Operation(summary = "Parse a receipt photo — returns a review proposal (no write)")
    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EstimateImportParseResponse parse(@PathVariable UUID id,
                                             @RequestParam("file") MultipartFile file,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        if (file == null || file.isEmpty()) {
            throw new CatalogImportException("error.import.empty");
        }
        try {
            return receiptService.parse(principal.id(), id, file.getOriginalFilename(),
                    file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new CatalogImportException("error.import.unreadable");
        }
    }

    @Operation(summary = "Read a receipt from its printed fiscal QR code — returns the same "
            + "review proposal as /parse, from the tax service's own record instead of a model. "
            + "Free (no feature gate); 400 when the code is not readable as a fiscal receipt")
    @PostMapping("/qr")
    public EstimateImportParseResponse parseQr(@PathVariable UUID id,
                                               @Valid @RequestBody FiscalQrRequest req,
                                               @AuthenticationPrincipal UserPrincipal principal) {
        return receiptService.parseQr(principal.id(), id, req.payload());
    }

    @Operation(summary = "Commit the confirmed receipt lines — appends them to the estimate")
    @PostMapping("/commit")
    public EstimateResponse commit(@PathVariable UUID id,
                                   @Valid @RequestBody ReceiptItemsCommitRequest req,
                                   @AuthenticationPrincipal UserPrincipal principal) {
        return receiptService.commit(principal.id(), id, req);
    }
}
