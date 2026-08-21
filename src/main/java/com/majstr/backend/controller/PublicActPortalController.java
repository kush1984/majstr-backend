package com.majstr.backend.controller;

import com.lowagie.text.DocumentException;
import com.majstr.backend.dto.PublicActView;
import com.majstr.backend.dto.QuestionRequest;
import com.majstr.backend.dto.QuestionResponse;
import com.majstr.backend.dto.SignRequest;
import com.majstr.backend.exception.TooManyRequestsException;
import com.majstr.backend.service.ProjectPhotoService.PhotoFile;
import com.majstr.backend.service.PublicActPortalService;
import com.majstr.backend.service.QuestionRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * The public work-act portal ({@code ?a=} token): view / sign / question / pdf for ONE act. Unlike
 * the read-only ECONOMY portal, this one CAN sign — an act is accepted by the client personally.
 * No authentication; every failure collapses to a neutral 404 so a token cannot be probed.
 */
@RestController
@RequestMapping("/api/public/act")
@RequiredArgsConstructor
@Tag(name = "Public portal (act)", description = "Work-act portal endpoints reachable by the act share link, no authentication")
public class PublicActPortalController {

    private final PublicActPortalService service;
    private final QuestionRateLimiter questionRateLimiter;

    @Operation(summary = "Get one work act by its share token")
    @GetMapping("/{token}")
    public PublicActView view(@PathVariable String token) {
        return service.view(token);
    }

    @Operation(summary = "Client confirms acceptance of the works (signs the act)")
    @PostMapping("/{token}/sign")
    public PublicActView sign(@PathVariable String token,
                             @Valid @RequestBody SignRequest req,
                             @RequestHeader(value = "User-Agent", required = false) String userAgent,
                             HttpServletRequest request) throws IOException, DocumentException {
        return service.sign(token, req, clientIp(request), userAgent);
    }

    @Operation(summary = "Client asks a question about the act")
    @PostMapping("/{token}/question")
    public ResponseEntity<QuestionResponse> ask(@PathVariable String token,
                                               @Valid @RequestBody QuestionRequest req,
                                               HttpServletRequest request) {
        String ip = clientIp(request);
        // A question WRITES (stored message + push to the master's phone) — tighter limit than the
        // blanket public-read one, keyed IP+token like the message link (review fix).
        QuestionRateLimiter.ConsumeResult probe = questionRateLimiter.tryConsume(ip + "|" + token);
        if (!probe.allowed()) {
            throw new TooManyRequestsException("error.rate.question", probe.retryAfterSeconds());
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.question(token, req, ip));
    }

    @Operation(summary = "Download the act PDF (public)")
    @GetMapping(value = "/{token}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable String token) throws IOException, DocumentException {
        byte[] body = service.pdf(token);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"act.pdf\"")
                .body(body);
    }

    @Operation(summary = "Stream a receipt photo attached to the act (public, token-scoped)")
    @GetMapping("/{token}/receipts/{receiptId}/file")
    public ResponseEntity<byte[]> receiptFile(@PathVariable String token,
                                              @PathVariable UUID receiptId) throws IOException {
        PhotoFile f = service.receiptFile(token, receiptId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(f.bytes());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
