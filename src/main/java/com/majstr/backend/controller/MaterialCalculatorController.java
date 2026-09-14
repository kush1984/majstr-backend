package com.majstr.backend.controller;

import com.majstr.backend.dto.MaterialApplyRequest;
import com.majstr.backend.dto.MaterialAvailabilityResponse;
import com.majstr.backend.dto.MaterialCalculationResponse;
import com.majstr.backend.dto.ShoppingListResponse;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.MaterialCalculatorService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * The material calculator: one estimate's works turned into what to buy (V127).
 *
 * <p>FREE on every plan, like the shopping list it feeds. Knowing how much material a job needs is
 * not a premium question — it is the question a master answers standing in a builders' merchant
 * with the client waiting.</p>
 *
 * <p>The calculation is a GET because it stores nothing: it is a view of the estimate, recomputed
 * from the current lines every time. The POST is where the master's decision becomes durable, and
 * it carries the numbers HE left on the screen — every one of them is editable there, so the
 * server does not re-derive what it already showed him.</p>
 *
 * <p>The two figures the estimate cannot carry ride the query string for that reason. {@code
 * perimeter} is one number for the whole estimate; {@code sections} is per POSITION and arrives as
 * one compact scalar — «uuid:0,4,uuid:0,55» — rather than a repeated parameter or a request body,
 * so asking for a короб's переріз does not turn the calculation into a POST. See {@code
 * MaterialCalculatorService.parseSections} for why a malformed entry is ignored, not rejected.</p>
 *
 * <p>The answer lands in the shopping list and nowhere else. Adding the materials to the estimate
 * as MATERIAL lines was offered once and removed: the calculation exists so the master knows what
 * to buy, and a line in a client-facing estimate is a different decision he did not ask for.</p>
 */
@RestController
@RequestMapping("/api/estimates/{estimateId}/materials")
@Tag(name = "Materials", description = "Material calculation from an estimate")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class MaterialCalculatorController {

    private final MaterialCalculatorService calculatorService;

    @GetMapping
    @Operation(summary = "Calculate materials for an estimate, with a coverage report")
    public MaterialCalculationResponse calculate(
            @PathVariable UUID estimateId,
            @RequestParam(required = false) BigDecimal wastePercent,
            @RequestParam(required = false) BigDecimal perimeter,
            @RequestParam(required = false) String sections,
            @AuthenticationPrincipal UserPrincipal principal) {
        return calculatorService.calculate(
                estimateId, principal.id(), wastePercent, perimeter, sections);
    }

    @GetMapping("/availability")
    @Operation(summary = "Whether this estimate can be answered at all — the PWA hides the "
            + "materials entry point when it cannot, rather than opening an empty screen")
    public MaterialAvailabilityResponse availability(
            @PathVariable UUID estimateId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return calculatorService.availability(estimateId, principal.id());
    }

    @PostMapping("/shopping-list")
    @Operation(summary = "Send the calculated materials to the object's shopping list")
    public ShoppingListResponse toShoppingList(@PathVariable UUID estimateId,
                                              @Valid @RequestBody MaterialApplyRequest req,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        return calculatorService.toShoppingList(estimateId, principal.id(), req);
    }
}
