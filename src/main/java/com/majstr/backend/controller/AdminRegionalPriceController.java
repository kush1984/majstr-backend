package com.majstr.backend.controller;

import com.majstr.backend.dto.RegionalPriceSummary;
import com.majstr.backend.service.AdminRegionalPriceService;
import com.majstr.backend.service.RegionalPriceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Prices by oblast, step 1 (V222): how much data each oblast has. Nothing here reaches a master. */
@RestController
@RequestMapping("/api/admin/regional-prices")
@RequiredArgsConstructor
@Tag(name = "Admin regional prices", description = "Prices by oblast, data so far (ROLE_ADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
public class AdminRegionalPriceController {

    private final AdminRegionalPriceService adminRegionalPriceService;
    private final RegionalPriceService regionalPriceService;

    @Operation(summary = "Masters, positions with an oblast median, and the oblast factor — every oblast")
    @GetMapping
    public RegionalPriceSummary summary() {
        return adminRegionalPriceService.summary();
    }

    @Operation(summary = "Recompute now instead of waiting for Saturday's run")
    @PostMapping("/refresh")
    public RegionalPriceSummary refresh() {
        regionalPriceService.weeklyRefresh();
        return adminRegionalPriceService.summary();
    }
}
