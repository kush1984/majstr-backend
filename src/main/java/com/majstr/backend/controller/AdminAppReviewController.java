package com.majstr.backend.controller;

import com.majstr.backend.dto.AppReviewAdminView;
import com.majstr.backend.entity.AppReviewStatus;
import com.majstr.backend.service.AppReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** The admin reads masters' ratings of Majstr and picks what goes on majstr.pro (V218). */
@RestController
@RequestMapping("/api/admin/app-reviews")
@RequiredArgsConstructor
@Tag(name = "Admin app reviews", description = "Masters' ratings of Majstr (ROLE_ADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
public class AdminAppReviewController {

    private final AppReviewService appReviewService;

    @Operation(summary = "All ratings, unread first, with who wrote them")
    @GetMapping
    public List<AppReviewAdminView> list() {
        return appReviewService.adminList();
    }

    @Operation(summary = "Publish (only with his consent), hide, or mark unread")
    @PostMapping("/{id}/status")
    public ResponseEntity<Void> setStatus(@PathVariable UUID id, @RequestParam AppReviewStatus value) {
        appReviewService.setStatus(id, value);
        return ResponseEntity.noContent().build();
    }
}
