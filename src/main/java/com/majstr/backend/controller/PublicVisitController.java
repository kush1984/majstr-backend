package com.majstr.backend.controller;

import com.majstr.backend.dto.VisitRequest;
import com.majstr.backend.security.ClientIp;
import com.majstr.backend.service.SiteVisitService;
import com.majstr.backend.service.VisitRateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The visit beacon of the public pages. Always 204: the page never reads the answer, and a visitor
 * over the limit or a bot is dropped without telling it so.
 */
@RestController
@RequestMapping("/api/public/visit")
@RequiredArgsConstructor
@Tag(name = "Public visits", description = "Anonymous visit counter for the landing and the SEO pages")
public class PublicVisitController {

    private final SiteVisitService visitService;
    private final VisitRateLimiter rateLimiter;

    @Operation(summary = "Count one view of a public page. Stores no address and sets no cookie")
    @PostMapping
    public ResponseEntity<Void> visit(@Valid @RequestBody VisitRequest req, HttpServletRequest http) {
        String ip = ClientIp.of(http);
        if (rateLimiter.tryConsume(ip)) {
            visitService.record(req, ip, http.getHeader(HttpHeaders.USER_AGENT));
        }
        return ResponseEntity.noContent().build();
    }
}
