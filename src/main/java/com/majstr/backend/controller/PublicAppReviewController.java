package com.majstr.backend.controller;

import com.majstr.backend.dto.PublicAppReview;
import com.majstr.backend.service.AppReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/** Masters' published ratings of Majstr, for majstr.pro (V218). */
@RestController
@RequestMapping("/api/public/app-reviews")
@RequiredArgsConstructor
@Tag(name = "Public app reviews", description = "What masters say about Majstr, as the landing page shows it")
public class PublicAppReviewController {

    private final AppReviewService appReviewService;

    @Operation(summary = "Published ratings of Majstr, newest first")
    @GetMapping
    public ResponseEntity<List<PublicAppReview>> list() {
        // The landing page asks on every visit; a few minutes stale is fine for a review.
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(appReviewService.published());
    }
}
