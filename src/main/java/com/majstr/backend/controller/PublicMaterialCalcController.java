package com.majstr.backend.controller;

import com.majstr.backend.dto.PublicMaterialCalcRequest;
import com.majstr.backend.dto.PublicMaterialCalcResponse;
import com.majstr.backend.exception.TooManyRequestsException;
import com.majstr.backend.security.ClientIp;
import com.majstr.backend.service.PortalRateLimiter;
import com.majstr.backend.service.PublicCalcRateLimiter;
import com.majstr.backend.service.PublicMaterialCalculatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public calculators on the site — no account, no token, nothing stored. Under
 * {@code /api/public/**}, which the security chain already permits; rate-limited per IP on a
 * counter of its own.
 */
@RestController
@RequestMapping("/api/public/materials")
@RequiredArgsConstructor
@Tag(name = "Public calculators", description = "Anonymous material calculators for the site's SEO pages")
public class PublicMaterialCalcController {

    private final PublicMaterialCalculatorService calculatorService;
    private final PublicCalcRateLimiter rateLimiter;

    @Operation(summary = "Materials for a TILE, DRYWALL, PLASTER or SCREED job, by the app's own norms. Stores and "
            + "logs nothing; rate-limited per IP")
    @PostMapping("/calc")
    public PublicMaterialCalcResponse calc(@Valid @RequestBody PublicMaterialCalcRequest req,
                                           HttpServletRequest http) {
        PortalRateLimiter.ConsumeResult probe = rateLimiter.tryConsume(ClientIp.of(http));
        if (!probe.allowed()) {
            throw new TooManyRequestsException("error.rate.public-calc", probe.retryAfterSeconds());
        }
        return calculatorService.calculate(req);
    }
}
