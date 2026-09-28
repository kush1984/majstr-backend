package com.majstr.backend.controller;

import com.majstr.backend.dto.MaterialApplyRequest;
import com.majstr.backend.dto.MaterialAvailabilityResponse;
import com.majstr.backend.dto.MaterialCalculationResponse;
import com.majstr.backend.dto.MaterialParamsRequest;
import com.majstr.backend.dto.ShoppingListResponse;
import com.majstr.backend.dto.StoredMaterialParams;
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
import org.springframework.web.bind.annotation.PutMapping;
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
 * <p>The three figures the estimate cannot carry are the exception to «stores nothing» — they are
 * ANSWERS, derived from nothing at all, and since V142 they are kept on the estimate rather than in
 * the browser that asked: he answered 5 mm on his phone, opened the same estimate on his laptop and
 * the card asked again with our suggestion back in the field, so one estimate had two shopping
 * lists. {@code PUT …/params} is the one door they are written through, and it is a PATCH: each card
 * on the screen owns its own «Порахувати» and sends only what that button answers.</p>
 *
 * <p>They still ride the query string on the GET, and that is not a second source of truth: the
 * query string is what the SCREEN is holding right now and wins per question, the stored set fills
 * every question the request is silent about, and {@code answers} comes back so the fields can open
 * with his own figures on a device that has never seen this estimate. {@code
 * perimeter} is one number for the whole estimate; {@code sections} (a короб's розгортка, in
 * metres) and {@code thicknesses} (a layer's thickness, in MILLIMETRES — V137) are per POSITION and
 * each arrives as one compact scalar — «uuid:0,4,uuid:0,55» — rather than a repeated parameter or a
 * request body, so asking for a переріз does not turn the calculation into a POST. Two parameters
 * and not one map: a single line can carry both questions, and a shared map would answer one of
 * them with the other's number. See {@code MaterialCalculatorService.parsePerPosition} for why a
 * malformed entry is ignored, not rejected.</p>
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
            @RequestParam(required = false) String thicknesses,
            @AuthenticationPrincipal UserPrincipal principal) {
        return calculatorService.calculate(
                estimateId, principal.id(), wastePercent, perimeter, sections, thicknesses);
    }

    @PutMapping("/params")
    @Operation(summary = "Remember one of the three figures the calculation has to ask for — a PATCH: "
            + "an omitted question is left alone, a zero forgets the answer")
    public StoredMaterialParams saveParams(@PathVariable UUID estimateId,
                                           @Valid @RequestBody MaterialParamsRequest req,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        return calculatorService.saveParams(estimateId, principal.id(), req);
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
