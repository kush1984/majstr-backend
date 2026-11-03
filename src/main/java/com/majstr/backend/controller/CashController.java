package com.majstr.backend.controller;

import com.majstr.backend.dto.CashEntryKind;
import com.majstr.backend.dto.CashEntryRequest;
import com.majstr.backend.dto.CashFlowResponse;
import com.majstr.backend.dto.CashSummaryResponse;
import com.majstr.backend.dto.FiscalQrRequest;
import com.majstr.backend.dto.ReceiptRecognizeResponse;
import com.majstr.backend.exception.PaymentValidationException;
import com.majstr.backend.exception.TooManyRequestsException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.CashFlowService;
import com.majstr.backend.service.CashScope;
import com.majstr.backend.service.ProjectPhotoService;
import com.majstr.backend.service.QrScanRateLimiter;
import com.majstr.backend.service.ReceiptScanRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

import java.time.LocalDate;
import java.util.UUID;

/**
 * «Мої гроші» (V135) — the master's own cash movement, not any one object's.
 *
 * <p>Owner-scoped throughout and ungated: it ships FREE alongside the rest of the economy. Nothing
 * here is object-addressed, which makes it the first surface in this API that is about the MASTER
 * rather than about a job.</p>
 */
@RestController
@RequestMapping("/api/cash")
@RequiredArgsConstructor
@Tag(name = "Cash flow", description = "The master's own income and spending across all objects")
@SecurityRequirement(name = "bearer-jwt")
public class CashController {

    private final CashFlowService cashService;
    private final QrScanRateLimiter qrScanRateLimiter;

    /**
     * One period's movement. Both bounds are inclusive and optional — absent means the current
     * calendar month in {@code Europe/Kyiv}, so the app never has to agree with the server about
     * what «сьогодні» is.
     *
     * @param monthly the YEAR view: per-month totals instead of a flat list
     * @param object  absent = every row; {@code none} = only the rows that belong to no object; an
     *                object id = that object's rows (404 when it is not his)
     */
    @GetMapping
    @Operation(summary = "Income and spending for a period, across all objects and beside them")
    public CashFlowResponse flow(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "false") boolean monthly,
            @RequestParam(required = false) String object,
            @AuthenticationPrincipal UserPrincipal principal) {
        return cashService.flow(principal.id(), from, to, monthly, scopeOf(object));
    }

    /** {@code none}, a canonical object id, or nothing. {@code UUID.fromString} is LENIENT below
     *  36 characters, so the length is checked first — a mangled id is a 400, never another row. */
    static CashScope scopeOf(String object) {
        if (object == null || object.isBlank()) {
            return CashScope.ALL;
        }
        String value = object.trim();
        if ("none".equalsIgnoreCase(value)) {
            return CashScope.OFF_OBJECT;
        }
        if (value.length() != 36) {
            throw new PaymentValidationException("error.cash.object-invalid");
        }
        try {
            return CashScope.object(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            throw new PaymentValidationException("error.cash.object-invalid");
        }
    }

    /** The home strip: this MONTH in three numbers — the window the tap then opens the screen on. */
    @GetMapping("/summary")
    @Operation(summary = "This month's totals for the home screen")
    public CashSummaryResponse summary(@AuthenticationPrincipal UserPrincipal principal) {
        return cashService.summary(principal.id());
    }

    /**
     * Add income or spending — always the master's OWN row.
     *
     * <p>It deliberately asks nothing about an object: money that belongs to one is already in that
     * object's journal and shows up on the read path by itself. What lands here is what no object
     * knows about.</p>
     *
     * <p>{@code X-Entity-Uuid} makes the create idempotent, like every other offline-capable create
     * here: a replayed queue entry must never bill the same money twice.</p>
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add an income or an expense of the master's own")
    public CashFlowResponse.Entry create(
            @Valid @RequestBody CashEntryRequest req,
            @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return cashService.create(principal.id(), req, entityId);
    }

    /**
     * Edit ANY row of the feed — his own, or an object's payment or expense.
     *
     * <p>{@code kind} in the body says which table the row lives in; the three id spaces are
     * separate, and the client always knows because the feed told it. An object row is written
     * through that object's own service, so its rules still apply — notably the refusal to edit an
     * expense a V129 till receipt owns (400, «правити його треба на чеку»).</p>
     */
    @PatchMapping("/{id}")
    @Operation(summary = "Edit a cash entry — personal, or an object's own payment/expense")
    public CashFlowResponse.Entry update(@PathVariable UUID id,
                                         @Valid @RequestBody CashEntryRequest req,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        return cashService.update(principal.id(), id, req);
    }

    /**
     * Idempotent for every kind: a row already gone is a no-op, so a replayed queue op is harmless.
     *
     * <p>{@code kind} is a query parameter rather than a body field because a DELETE carries none;
     * absent it means PERSONAL, which is what an older client could only ever have meant.</p>
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a cash entry — personal, or an object's own payment/expense")
    public void delete(@PathVariable UUID id,
                       @RequestParam(required = false) CashEntryKind kind,
                       @AuthenticationPrincipal UserPrincipal principal) {
        cashService.delete(principal.id(), id, kind);
    }

    /**
     * Read a receipt's fiscal QR to pre-fill one of his own rows. Free and local; on the same
     * per-account counter as the object and act receipts' QR reads.
     */
    @PostMapping("/receipt/qr")
    @Operation(summary = "Read a receipt's fiscal QR for a personal row — persists nothing")
    public ReceiptRecognizeResponse readQr(@Valid @RequestBody FiscalQrRequest req,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        ReceiptScanRateLimiter.ConsumeResult probe = qrScanRateLimiter.tryConsume(principal.id());
        if (!probe.allowed()) {
            throw new TooManyRequestsException("error.rate.qr-scan", probe.retryAfterSeconds());
        }
        return cashService.readQr(req.payload());
    }

    /** Attach or replace the photo of the paper on one of his OWN rows. */
    @PostMapping(value = "/{id}/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Attach a receipt photo to a personal cash entry")
    public CashFlowResponse.Entry attachPhoto(@PathVariable UUID id,
                                              @RequestParam("file") MultipartFile file,
                                              @AuthenticationPrincipal UserPrincipal principal)
            throws IOException {
        return cashService.attachPhoto(principal.id(), id, file);
    }

    @DeleteMapping("/{id}/photo")
    @Operation(summary = "Remove the receipt photo from a personal cash entry")
    public CashFlowResponse.Entry removePhoto(@PathVariable UUID id,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        return cashService.removePhoto(principal.id(), id);
    }

    @GetMapping("/{id}/photo")
    @Operation(summary = "Stream a personal cash entry's receipt photo (authenticated owner)")
    public ResponseEntity<Resource> photo(@PathVariable UUID id,
                                          @AuthenticationPrincipal UserPrincipal principal)
            throws IOException {
        ProjectPhotoService.PhotoFile f = cashService.readPhoto(principal.id(), id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(new InputStreamResource(f.content()));
    }
}
