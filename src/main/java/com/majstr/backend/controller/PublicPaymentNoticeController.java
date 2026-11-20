package com.majstr.backend.controller;

import com.majstr.backend.dto.PaymentNoticeRequest;
import com.majstr.backend.dto.QuestionResponse;
import com.majstr.backend.exception.TooManyRequestsException;
import com.majstr.backend.security.ClientIp;
import com.majstr.backend.service.PaymentNoticeService;
import com.majstr.backend.service.QuestionRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * «Я оплатив» off any client portal (V217). Under {@code /api/public/}, so the portal's own rate
 * limit filter already caps it per IP and link; the question limiter on top is the one every client
 * write on a portal shares — a notice also pushes to the master's phone.
 */
@RestController
@RequestMapping("/api/public/payment-notice")
@RequiredArgsConstructor
@Tag(name = "Public payment notice", description = "The client tells the master he paid, with the receipt")
public class PublicPaymentNoticeController {

    private final PaymentNoticeService paymentNoticeService;
    private final QuestionRateLimiter rateLimiter;

    /**
     * Multipart, as the message link is: the fields are a JSON part named {@code data} so
     * {@code @Valid} does the validating, and the receipt rides along as {@code files}.
     */
    @Operation(summary = "Tell the master a payment was sent, with the receipt (public, rate-limited)")
    @PostMapping(path = "/{token}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<QuestionResponse> submit(
            @PathVariable String token,
            @Valid @RequestPart("data") PaymentNoticeRequest req,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            HttpServletRequest http) {
        String ip = ClientIp.of(http);
        QuestionRateLimiter.ConsumeResult probe = rateLimiter.tryConsume(ip + "|" + token);
        if (!probe.allowed()) {
            throw new TooManyRequestsException("error.rate.message-link", probe.retryAfterSeconds());
        }
        return ResponseEntity.ok(paymentNoticeService.submit(token, req, files, ip));
    }

    /** A PNG, not an SVG: a bank app's «scan from a photo» reads the one the client saved to his gallery. */
    @Operation(summary = "The master's NBU payment QR for this link, as a PNG (public)")
    @GetMapping(path = "/{token}/qr.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> qr(@PathVariable String token) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"oplata-qr.png\"")
                .body(paymentNoticeService.qrPng(token));
    }
}
