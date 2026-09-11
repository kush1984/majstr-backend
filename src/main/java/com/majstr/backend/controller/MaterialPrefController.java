package com.majstr.backend.controller;

import com.majstr.backend.dto.MaterialPrefsRequest;
import com.majstr.backend.dto.MaterialPrefsResponse;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.MaterialPrefService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The master's remembered answers for the material calculator — which sheet he buys, how many
 * coats he paints. Habits only; a property of the OBJECT never lives here.
 */
@RestController
@RequestMapping("/api/me/material-prefs")
@RequiredArgsConstructor
@Tag(name = "Material preferences", description = "The master's habitual calculator parameters")
@SecurityRequirement(name = "bearer-jwt")
public class MaterialPrefController {

    private final MaterialPrefService prefService;

    @Operation(summary = "Read the master's material parameters")
    @GetMapping
    public MaterialPrefsResponse get(@AuthenticationPrincipal UserPrincipal principal) {
        return prefService.get(principal.id());
    }

    @Operation(summary = "Upsert material parameters; a blank value removes one")
    @PutMapping
    public MaterialPrefsResponse save(@Valid @RequestBody MaterialPrefsRequest req,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return prefService.save(principal.id(), req);
    }
}
