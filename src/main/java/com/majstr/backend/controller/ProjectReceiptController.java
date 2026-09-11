package com.majstr.backend.controller;

import com.majstr.backend.dto.FiscalQrRequest;
import com.majstr.backend.dto.ProjectReceiptRequest;
import com.majstr.backend.dto.ProjectReceiptResponse;
import com.majstr.backend.dto.ProjectReceiptsResponse;
import com.majstr.backend.dto.ReceiptRecognizeResponse;
import com.majstr.backend.exception.TooManyRequestsException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ProjectPhotoService.PhotoFile;
import com.majstr.backend.service.ProjectReceiptService;
import com.majstr.backend.service.QrScanRateLimiter;
import com.majstr.backend.service.ReceiptScanRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.UUID;

/**
 * «Чеки обʼєкта» (V129) — the paper the master photographs at the till, filed against the object.
 * FREE on every plan: filing a receipt photo already was, and so is reading its footer (the same
 * rate limiters bound both, per account).
 *
 * <p>Endpoints mirror the act's receipts one for one, minus the not-signed guard an object has no
 * equivalent of — see {@link ProjectReceiptService} for why the two tables stay apart.</p>
 */
@RestController
@RequestMapping("/api/projects/{id}/receipts")
@RequiredArgsConstructor
@Tag(name = "Object receipts", description = "Receipts photographed at the till, filed per object")
@SecurityRequirement(name = "bearer-jwt")
public class ProjectReceiptController {

    private final ProjectReceiptService receiptService;
    private final ReceiptScanRateLimiter receiptScanRateLimiter;
    private final QrScanRateLimiter qrScanRateLimiter;

    @Operation(summary = "The object's receipts plus the receivable / own-cost totals")
    @GetMapping
    public ProjectReceiptsResponse list(@PathVariable UUID id,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return receiptService.list(id, principal.id());
    }

    @Operation(summary = "Attach a receipt: a MANDATORY photo, plus a label, amount and date that "
            + "may all still be unknown — a batch of photos is saved first and priced afterwards. "
            + "A blank label is named «Чек №N» by the server. Send a client-generated UUID in "
            + "X-Entity-Uuid to make the create idempotent: a retried upload over a weak connection "
            + "must not bill the same material twice")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProjectReceiptResponse> add(
            @PathVariable UUID id,
            @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "label", required = false) String label,
            @RequestParam(value = "amount", required = false) BigDecimal amount,
            @RequestParam(value = "issuedAt", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate issuedAt,
            @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(receiptService.add(id, principal.id(), entityId, file, label, amount, issuedAt));
    }

    @Operation(summary = "Read a receipt from its printed fiscal QR — label, date, total AND the "
            + "paper's fiscal identity, read locally with no model call and no tax-service lookup. "
            + "A code we cannot read is a soft recognized=false. Rate-limited per account")
    @PostMapping("/qr")
    public ReceiptRecognizeResponse readQr(@PathVariable UUID id,
                                           @Valid @RequestBody FiscalQrRequest req,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        // Its OWN counter, not the recognition one: a QR read spends no model call and fires
        // automatically on every photo of a batch, so sharing the bucket would let one shopping
        // trip eat the budget for the pass that actually costs money.
        ReceiptScanRateLimiter.ConsumeResult probe = qrScanRateLimiter.tryConsume(principal.id());
        if (!probe.allowed()) {
            throw new TooManyRequestsException("error.rate.qr-scan", probe.retryAfterSeconds());
        }
        return receiptService.readQr(id, principal.id(), req.payload());
    }

    @Operation(summary = "Recognize a receipt that is already stored — reads the photo uploaded "
            + "with it, so a slow read never re-uploads and survives a page reload. Persists "
            + "nothing; the client applies the prefill and PATCHes. Rate-limited per account")
    @PostMapping("/{receiptId}/recognize")
    public ReceiptRecognizeResponse recognize(@PathVariable UUID id,
                                              @PathVariable UUID receiptId,
                                              @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        ReceiptScanRateLimiter.ConsumeResult probe = receiptScanRateLimiter.tryConsume(principal.id());
        if (!probe.allowed()) {
            throw new TooManyRequestsException("error.rate.receipt-scan", probe.retryAfterSeconds());
        }
        return receiptService.recognize(id, receiptId, principal.id());
    }

    @Operation(summary = "Edit a receipt's label / amount / date, and flip «клієнт відшкодовує» to "
            + "«це моя витрата». The flag is three-valued: omit it and it is left alone; false "
            + "posts a MATERIALS/RECEIPT expense for this receipt, true removes it again")
    @PatchMapping("/{receiptId}")
    public ProjectReceiptResponse update(@PathVariable UUID id,
                                         @PathVariable UUID receiptId,
                                         @Valid @RequestBody ProjectReceiptRequest req,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        return receiptService.update(id, receiptId, principal.id(), req);
    }

    @Operation(summary = "Delete a receipt (and the expense it posted, if any)")
    @DeleteMapping("/{receiptId}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @PathVariable UUID receiptId,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        receiptService.delete(id, receiptId, principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Stream a receipt photo (authenticated owner)")
    @GetMapping("/{receiptId}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID id,
                                       @PathVariable UUID receiptId,
                                       @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        PhotoFile f = receiptService.readOwnedFile(id, receiptId, principal.id());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(f.bytes());
    }
}
