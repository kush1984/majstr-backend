package com.majstr.backend.controller;

import com.majstr.backend.dto.DictationCommitRequest;
import com.majstr.backend.dto.DictationParseRequest;
import com.majstr.backend.dto.DictationParseResponse;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.exception.TooManyRequestsException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.DictationRateLimiter;
import com.majstr.backend.service.ReceiptScanRateLimiter;
import com.majstr.backend.service.importer.DictationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Add positions to an open estimate from text the master typed or dictated with his own keyboard's
 * microphone. {@code /parse} returns a review proposal, matched against his catalog (nothing is
 * written, the text is discarded); {@code /commit} appends the confirmed lines (SIGNED → 409).
 *
 * <p>No feature gate in this cut — the per-account hourly counter is what bounds the model calls.
 * Ownership and the not-signed check are enforced in the service.</p>
 */
@RestController
@RequestMapping("/api/estimates/{id}/dictation")
@RequiredArgsConstructor
@Tag(name = "Dictation", description = "Add estimate positions from dictated or typed text (LLM)")
@SecurityRequirement(name = "bearer-jwt")
public class DictationController {

    private final DictationService dictationService;
    private final DictationRateLimiter dictationRateLimiter;

    @Operation(summary = "Parse dictated text into positions matched against the master's catalog "
            + "— returns a review proposal (no write)")
    @PostMapping("/parse")
    public DictationParseResponse parse(@PathVariable UUID id,
                                        @Valid @RequestBody DictationParseRequest req,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        ReceiptScanRateLimiter.ConsumeResult probe = dictationRateLimiter.tryConsume(principal.id());
        if (!probe.allowed()) {
            throw new TooManyRequestsException("error.rate.dictation", probe.retryAfterSeconds());
        }
        return dictationService.parse(principal.id(), id, req.text());
    }

    @Operation(summary = "Commit the confirmed dictated lines — appends them to the estimate")
    @PostMapping("/commit")
    public EstimateResponse commit(@PathVariable UUID id,
                                   @Valid @RequestBody DictationCommitRequest req,
                                   @AuthenticationPrincipal UserPrincipal principal) {
        return dictationService.commit(principal.id(), id, req);
    }
}
