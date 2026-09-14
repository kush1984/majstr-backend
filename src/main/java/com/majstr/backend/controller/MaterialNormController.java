package com.majstr.backend.controller;

import com.majstr.backend.dto.MaterialNormResponse;
import com.majstr.backend.dto.MaterialNormUpdateRequest;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.MaterialNormService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * A master's own consumption norms — the «× 0,12» in the calculator's arithmetic, corrected.
 *
 * <p>Norms are addressed on their own path rather than under the estimate that happened to show
 * them: the correction outlives that estimate and applies to every future calculation. FREE on
 * every plan, like the calculator and the shopping list it feeds.</p>
 */
@RestController
@RequestMapping("/api/material-norms/{normId}")
@Tag(name = "Materials", description = "Material calculation from an estimate")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class MaterialNormController {

    private final MaterialNormService normService;

    @PutMapping
    @Operation(summary = "Save the master's own coefficient for this norm (forks a shipped one)")
    public MaterialNormResponse saveOwn(@PathVariable UUID normId,
                                        @Valid @RequestBody MaterialNormUpdateRequest req,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return normService.saveOwn(normId, principal.id(), req.qtyPerUnit());
    }

    @DeleteMapping
    @Operation(summary = "Drop the master's own coefficient and fall back to the shipped norm")
    public ResponseEntity<Void> restoreDefault(@PathVariable UUID normId,
                                               @AuthenticationPrincipal UserPrincipal principal) {
        normService.restoreDefault(normId, principal.id());
        return ResponseEntity.noContent().build();
    }
}
