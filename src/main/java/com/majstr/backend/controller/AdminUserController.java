package com.majstr.backend.controller;

import com.majstr.backend.dto.AdminUserDetail;
import com.majstr.backend.dto.AdminUserSummary;
import com.majstr.backend.dto.PageResponse;
import com.majstr.backend.dto.PlanUpdateRequest;
import com.majstr.backend.dto.ReferralSourceUpdateRequest;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.function.Function;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Tag(name = "Admin users", description = "User listing and plan management (ROLE_ADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
public class AdminUserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;
    private final AdminUserService adminUserService;

    @Operation(summary = "Search users with pagination + per-user activity counts "
            + "(email verified, clients, projects, estimates, signed)")
    @GetMapping
    public PageResponse<AdminUserSummary> list(
            @RequestParam(required = false) Plan plan,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        // Unsorted — the ordering (active-right-now first, then createdAt DESC) is the query's
        // own ORDER BY now (UserRepository#searchAdminByPattern), not something Pageable adds.
        var pageable = PageRequest.of(Math.max(page, 0), safeSize);
        return PageResponse.of(
                adminUserService.search(plan, blankToNull(source), blankToNull(search), pageable),
                Function.identity());
    }

    @Operation(summary = "Full activity detail / activation funnel for one master")
    @GetMapping("/{id}")
    public AdminUserDetail detail(@PathVariable UUID id) {
        return adminUserService.detail(id);
    }

    @Operation(summary = "Change a user's plan (manual upgrade until billing lands)")
    @PatchMapping("/{id}/plan")
    @Transactional
    public AdminUserSummary changePlan(@PathVariable UUID id, @Valid @RequestBody PlanUpdateRequest req) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + id));
        user.setPlan(req.plan());
        return AdminUserSummary.from(user);
    }

    @Operation(summary = "Manually set a master's referral source (conflicts / survey leads)")
    @PatchMapping("/{id}/referral-source")
    public AdminUserDetail changeReferralSource(@PathVariable UUID id,
                                                @Valid @RequestBody ReferralSourceUpdateRequest req,
                                                @AuthenticationPrincipal UserPrincipal principal) {
        adminUserService.updateReferralSource(id, req.source(), principal.email());
        return adminUserService.detail(id);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
