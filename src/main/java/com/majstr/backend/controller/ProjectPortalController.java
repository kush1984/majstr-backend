package com.majstr.backend.controller;

import com.majstr.backend.dto.EconomyUpdateRequest;
import com.majstr.backend.dto.PortalStateResponse;
import com.majstr.backend.dto.PortalUpdateRequest;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ProjectPortalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/projects/{projectId}/portal")
@RequiredArgsConstructor
@Tag(name = "Project portal", description = "Owner-side control of the object's client portal (which estimates are visible, the share URL)")
public class ProjectPortalController {

    private final ProjectPortalService portalService;

    @Operation(summary = "Current portal state: share URL (if published) + per-estimate visibility")
    @GetMapping
    public PortalStateResponse state(@PathVariable UUID projectId,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        return portalService.state(projectId, principal.id());
    }

    @Operation(summary = "Publish the SIGNATURE portal: set the visible estimates, mint/reuse the link")
    @PutMapping
    public PortalStateResponse update(@PathVariable UUID projectId,
                                      @Valid @RequestBody PortalUpdateRequest req,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return portalService.update(projectId, req.estimateIds(), principal.id());
    }

    @Operation(summary = "Email the SIGNATURE portal link to the object's client")
    @PostMapping("/send-email")
    public PortalStateResponse sendEmail(@PathVariable UUID projectId,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        return portalService.sendEmail(projectId, principal.id());
    }

    @Operation(summary = "Current ECONOMY portal state: share URL (if published) + per-estimate visibility")
    @GetMapping("/economy")
    public PortalStateResponse economyState(@PathVariable UUID projectId,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        return portalService.economyState(projectId, principal.id());
    }

    @Operation(summary = "Publish the ECONOMY portal: payments toggle, mint/reuse the link (shows every signed, counted estimate)")
    @PutMapping("/economy")
    public PortalStateResponse updateEconomy(@PathVariable UUID projectId,
                                             @Valid @RequestBody EconomyUpdateRequest req,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        return portalService.updateEconomy(projectId, Boolean.TRUE.equals(req.paymentsVisible()), principal.id());
    }

    @Operation(summary = "Close the ECONOMY portal link — the client's URL stops working; the next publish mints a new one")
    @DeleteMapping("/economy")
    public PortalStateResponse revokeEconomy(@PathVariable UUID projectId,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        return portalService.revokeEconomy(projectId, principal.id());
    }

    @Operation(summary = "Email the ECONOMY portal link to the object's client")
    @PostMapping("/economy/send-email")
    public PortalStateResponse sendEconomyEmail(@PathVariable UUID projectId,
                                                @AuthenticationPrincipal UserPrincipal principal) {
        return portalService.sendEconomyEmail(projectId, principal.id());
    }
}
