package com.majstr.backend.controller;

import com.majstr.backend.dto.MessageLinkInfo;
import com.majstr.backend.dto.MessageLinkRequest;
import com.majstr.backend.dto.MessageLinkState;
import com.majstr.backend.dto.QuestionResponse;
import com.majstr.backend.exception.TooManyRequestsException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.MessageLinkRateLimiter;
import com.majstr.backend.service.MessageLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * The master's message link — both ends of it.
 *
 * <p>The owner side mints and revokes; the public side is what whoever received the link talks to. The
 * public half lives under {@code /api/public/}, which {@code PublicPortalRateLimitFilter} already caps
 * per IP; the POST additionally passes a tighter, link-scoped limit, since it writes.</p>
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Message link", description = "A per-object link that opens a message form and nothing else")
public class MessageLinkController {

    private final MessageLinkService messageLinkService;
    private final MessageLinkRateLimiter rateLimiter;

    // ---- the master ----------------------------------------------------------------------------

    @Operation(summary = "The object's message link, minted on first ask")
    @GetMapping("/api/projects/{projectId}/message-link")
    public MessageLinkState state(@PathVariable UUID projectId,
                                  @AuthenticationPrincipal UserPrincipal principal) {
        return messageLinkService.state(projectId, principal.id());
    }

    @Operation(summary = "Revoke the link — anyone still holding the URL stops being able to write")
    @DeleteMapping("/api/projects/{projectId}/message-link")
    public ResponseEntity<Void> revoke(@PathVariable UUID projectId,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        messageLinkService.revoke(projectId, principal.id());
        return ResponseEntity.noContent().build();
    }

    // ---- whoever got the link ------------------------------------------------------------------

    @Operation(summary = "Who and what the form is writing to (public — no prices, no client)")
    @GetMapping("/api/public/message-link/{token}")
    public MessageLinkInfo info(@PathVariable String token) {
        return messageLinkService.info(token);
    }

    /**
     * Multipart rather than JSON so the text and its attachments arrive together. The fields stay a
     * JSON part named {@code data} instead of becoming form fields, which keeps {@code @Valid} on the
     * record doing the validating — form-field binding would have moved those rules into this method.
     */
    @Operation(summary = "Send a message through the link, with attachments (public, rate-limited)")
    @PostMapping(path = "/api/public/message-link/{token}",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<QuestionResponse> submit(
            @PathVariable String token,
            @Valid @RequestPart("data") MessageLinkRequest req,
            @RequestPart(value = "files", required = false) List<MultipartFile> files,
            HttpServletRequest http) {
        String ip = clientIp(http);
        // Keyed on the PAIR: one address cannot spray every link a master has, and one leaked link
        // cannot be filled from a hundred addresses either. Consumed before the write, and before any
        // bytes are stored.
        MessageLinkRateLimiter.ConsumeResult probe = rateLimiter.tryConsume(ip + "|" + token);
        if (!probe.allowed()) {
            throw new TooManyRequestsException("error.rate.message-link", probe.retryAfterSeconds());
        }
        return ResponseEntity.ok(messageLinkService.submit(token, req, files, ip));
    }

    /** Behind a proxy the socket address is the proxy's; the first XFF hop is the real sender. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
