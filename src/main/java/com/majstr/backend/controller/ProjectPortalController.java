package com.majstr.backend.controller;

import com.majstr.backend.dto.PortalStateResponse;
import com.majstr.backend.dto.PortalUpdateRequest;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ProjectPortalService;
import io.swagger.v3.oas.annotations.Operation;
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

    @Operation(summary = "Publish the portal: set the visible estimates, mint/reuse the link")
    @PutMapping
    public PortalStateResponse update(@PathVariable UUID projectId,
                                      @Valid @RequestBody PortalUpdateRequest req,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return portalService.update(projectId, req.estimateIds(),
                Boolean.TRUE.equals(req.paymentsVisible()), principal.id());
    }

    @Operation(summary = "Email the portal link to the object's client")
    @PostMapping("/send-email")
    public PortalStateResponse sendEmail(@PathVariable UUID projectId,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        return portalService.sendEmail(projectId, principal.id());
    }
}
