package com.majstr.backend.controller;

import com.lowagie.text.DocumentException;
import com.majstr.backend.dto.PublicEstimateView;
import com.majstr.backend.dto.QuestionRequest;
import com.majstr.backend.dto.QuestionResponse;
import com.majstr.backend.dto.SignRequest;
import com.majstr.backend.exception.TooManyRequestsException;
import com.majstr.backend.service.ProjectPhotoService;
import com.majstr.backend.service.PublicEstimateService;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

@RestController
@RequestMapping("/api/public/estimates")
@RequiredArgsConstructor
@Tag(name = "Public portal", description = "Read-only and minimal-write endpoints reachable by share link, no authentication")
public class PublicEstimateController {

    private final PublicEstimateService publicService;
    private final QuestionRateLimiter questionRateLimiter;

    @Operation(summary = "Get the estimate behind a share link")
    @GetMapping("/{token}")
    public PublicEstimateView view(@PathVariable String token) {
        return publicService.view(token);
    }

    @Operation(summary = "Client signs the estimate")
    @PostMapping("/{token}/sign")
    public PublicEstimateView sign(@PathVariable String token,
                                   @Valid @RequestBody SignRequest req,
                                   HttpServletRequest request) {
        return publicService.sign(token, req, clientIp(request));
    }

    @Operation(summary = "Client leaves a question or comment")
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
                .body(publicService.askQuestion(token, req, ip));
    }

    @Operation(summary = "Download the estimate PDF (public)")
    @GetMapping(value = "/{token}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable String token) throws IOException, DocumentException {
        byte[] body = publicService.renderPdf(token);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"estimate.pdf\"")
                .body(body);
    }

    @Operation(summary = "Stream a photo the master shared with the client (SHARED only)")
    @GetMapping("/{token}/photos/{photoId}/file")
    public ResponseEntity<byte[]> sharedPhoto(@PathVariable String token,
                                              @PathVariable UUID photoId) throws IOException {
        ProjectPhotoService.PhotoFile file = publicService.readSharedPhoto(token, photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(file.bytes());
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
