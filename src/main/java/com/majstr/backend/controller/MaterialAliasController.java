package com.majstr.backend.controller;

import com.majstr.backend.dto.MaterialAliasRequest;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.MaterialAliasService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;


/**
 * «Це те саме, що …?» (V204): the master's own wording of a position, counted as a library position or
 * as his own work whose materials are not counted. Owner-scoped by the token alone: no id in any path.
 */
@RestController
@Tag(name = "Materials", description = "Material calculation from an estimate")
@SecurityRequirement(name = "bearer-jwt")
@RequiredArgsConstructor
public class MaterialAliasController {

    private final MaterialAliasService aliasService;

    @PutMapping("/api/me/material-aliases")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Count this wording as a library position from now on, or as consuming nothing")
    public void save(@Valid @RequestBody MaterialAliasRequest req, @AuthenticationPrincipal UserPrincipal principal) {
        aliasService.save(principal.id(), req);
    }

    @DeleteMapping("/api/me/material-aliases")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Forget the answer for this wording — the line is asked about again. Idempotent")
    public void delete(@RequestParam String name, @RequestParam Unit unit,
                       @AuthenticationPrincipal UserPrincipal principal) {
        aliasService.delete(principal.id(), name, unit);
    }
}
