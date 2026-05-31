package com.majstr.backend.controller;

import com.majstr.backend.dto.AdminUserSummary;
import com.majstr.backend.dto.PageResponse;
import com.majstr.backend.dto.PlanUpdateRequest;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.UserRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Tag(name = "Admin users", description = "User listing and plan management (ROLE_ADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
public class AdminUserController {

    private static final int MAX_PAGE_SIZE = 100;

    private final UserRepository userRepository;

    @Operation(summary = "Search users with pagination; filter by plan or free-text search")
    @GetMapping
    @Transactional(readOnly = true) // map lazy trades within a session (open-in-view is off)
    public PageResponse<AdminUserSummary> list(
            @RequestParam(required = false) Plan plan,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        var pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "createdAt"));
        return PageResponse.of(
                userRepository.searchAdmin(plan, blankToNull(search), pageable),
                AdminUserSummary::from
        );
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

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
