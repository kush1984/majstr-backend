package com.majstr.backend.controller;

import com.majstr.backend.dto.AppReviewPrompt;
import com.majstr.backend.dto.AppReviewRequest;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.AppReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A master rates Majstr itself (V218). */
@RestController
@RequestMapping("/api/app-reviews")
@RequiredArgsConstructor
@Tag(name = "App reviews", description = "Masters rate Majstr — feedback, and with consent proof on majstr.pro")
@SecurityRequirement(name = "bearer-jwt")
public class AppReviewController {

    private final AppReviewService appReviewService;

    @Operation(summary = "Should the home screen ask him to rate Majstr now")
    @GetMapping("/prompt")
    public AppReviewPrompt prompt(@AuthenticationPrincipal UserPrincipal principal) {
        return appReviewService.prompt(principal.id());
    }

    @Operation(summary = "«Не зараз» on the home screen — not asked again for a while")
    @PostMapping("/prompt/dismiss")
    public ResponseEntity<Void> dismiss(@AuthenticationPrincipal UserPrincipal principal) {
        appReviewService.dismissPrompt(principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Rate Majstr: 1–5 stars, words, and (at 4–5) consent to show it on majstr.pro")
    @PostMapping
    public ResponseEntity<Void> submit(@Valid @RequestBody AppReviewRequest req,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        appReviewService.submit(principal.id(), req);
        return ResponseEntity.noContent().build();
    }
}
