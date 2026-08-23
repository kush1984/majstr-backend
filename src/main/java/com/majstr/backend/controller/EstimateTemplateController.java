package com.majstr.backend.controller;

import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.EstimateTemplateDetail;
import com.majstr.backend.dto.EstimateTemplateSummary;
import com.majstr.backend.dto.SaveAsTemplateRequest;
import com.majstr.backend.dto.TemplateItemRequest;
import com.majstr.backend.dto.TemplateItemsOrderRequest;
import com.majstr.backend.dto.TemplateTradeRequest;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.EstimateTemplateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Tag(name = "Estimate templates", description = "Ready-made bundles of works for a typical job")
@SecurityRequirement(name = "bearer-jwt")
public class EstimateTemplateController {

    private final EstimateTemplateService templateService;
    private final UserRepository userRepository;

    @Operation(summary = "List templates for the picker — system defaults relevant to my "
            + "trades (+ general) plus my own saved templates")
    @GetMapping("/api/estimate-templates")
    public List<EstimateTemplateSummary> list(@AuthenticationPrincipal UserPrincipal principal) {
        // Eager-fetch trades (open-in-view off) — the default filter reads them.
        var user = userRepository.findWithTradesById(principal.id())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + principal.id()));
        return templateService.listForUser(user);
    }

    @Operation(summary = "Preview a template's composition (its positions)")
    @GetMapping("/api/estimate-templates/{id}")
    public EstimateTemplateDetail get(@PathVariable UUID id,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return templateService.get(id, principal.id());
    }

    @Operation(summary = "Rename a template",
            description = "Editing a SYSTEM DEFAULT forks it into my own editable copy and hides "
                    + "the original for me alone — the response carries the copy's id, so follow it.")
    @PatchMapping("/api/estimate-templates/{id}")
    public EstimateTemplateSummary rename(@PathVariable UUID id,
                                          @Valid @RequestBody SaveAsTemplateRequest req,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        return templateService.rename(id, req.name(), principal.id());
    }

    @Operation(summary = "File a template under a trade — my own filing; on a system default "
            + "it is stored per-master and stays invisible to everyone else")
    @PatchMapping("/api/estimate-templates/{id}/trade")
    public EstimateTemplateSummary setTrade(@PathVariable UUID id,
                                            @Valid @RequestBody TemplateTradeRequest req,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        return templateService.setTrade(id, req.trade(), req.customTradeId(), principal.id());
    }

    @Operation(summary = "Delete a template",
            description = "My own template is deleted; a SYSTEM DEFAULT is shared by every master, "
                    + "so it is hidden for me alone and can be brought back with restore-defaults.")
    @DeleteMapping("/api/estimate-templates/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        templateService.delete(id, principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Bring back every system default I hid (my own copies are left alone)")
    @PostMapping("/api/estimate-templates/restore-defaults")
    public List<EstimateTemplateSummary> restoreDefaults(@AuthenticationPrincipal UserPrincipal principal) {
        templateService.restoreDefaults(principal.id());
        var user = userRepository.findWithTradesById(principal.id())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + principal.id()));
        return templateService.listForUser(user);
    }

    @Operation(summary = "Add a position to a template (a system default is forked on write)",
            description = "Offline-authored adds may send a client-generated UUID in the "
                    + "X-Entity-Uuid header — the add is then idempotent on replay.")
    @PostMapping("/api/estimate-templates/{id}/items")
    public EstimateTemplateDetail addItem(
            @PathVariable UUID id,
            @Valid @RequestBody TemplateItemRequest req,
            @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
            @AuthenticationPrincipal UserPrincipal principal) {
        return templateService.addItem(id, req, principal.id(), entityId);
    }

    @Operation(summary = "Remove a position from a template (a system default is forked on write)")
    @DeleteMapping("/api/estimate-templates/{id}/items/{itemId}")
    public EstimateTemplateDetail removeItem(@PathVariable UUID id,
                                             @PathVariable UUID itemId,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        return templateService.removeItem(id, itemId, principal.id());
    }

    @Operation(summary = "Edit a position in place — name / type / unit "
            + "(a system default is forked on write)")
    @PatchMapping("/api/estimate-templates/{id}/items/{itemId}")
    public EstimateTemplateDetail updateItem(@PathVariable UUID id,
                                             @PathVariable UUID itemId,
                                             @Valid @RequestBody TemplateItemRequest req,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        return templateService.updateItem(id, itemId, req, principal.id());
    }

    @Operation(summary = "Rearrange a template's positions",
            description = "The full order, not a move — idempotent on an offline replay. A bundle "
                    + "is a sequence (what is done after what), so this is real content, not "
                    + "decoration. A system default is forked on write.")
    @PutMapping("/api/estimate-templates/{id}/items/order")
    public EstimateTemplateDetail reorderItems(@PathVariable UUID id,
                                               @Valid @RequestBody TemplateItemsOrderRequest req,
                                               @AuthenticationPrincipal UserPrincipal principal) {
        return templateService.reorderItems(id, req, principal.id());
    }

    @Operation(summary = "Save the current estimate as my own reusable template "
            + "(quantities/prices dropped, names+units kept)")
    @PostMapping("/api/estimates/{id}/save-as-template")
    public ResponseEntity<EstimateTemplateSummary> saveAsTemplate(
            @PathVariable UUID id,
            @Valid @RequestBody SaveAsTemplateRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        EstimateTemplateSummary saved =
                templateService.saveFromEstimate(id, req.name(), req.trade(), req.customTradeId(), principal.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @Operation(summary = "Create a new estimate in the project from a template — a normal, "
            + "fully editable estimate (quantities empty, prices from my catalog)")
    @PostMapping("/api/projects/{projectId}/estimates/from-template/{templateId}")
    public ResponseEntity<EstimateResponse> createFromTemplate(
            @PathVariable UUID projectId,
            @PathVariable UUID templateId,
            @Valid @RequestBody EstimateCreateRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        EstimateResponse created = templateService.applyToProject(projectId, templateId, req, principal.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @Operation(summary = "Create ONE new estimate in the project from SEVERAL templates",
            description = "Positions are concatenated in the given order and de-duplicated by "
                    + "name, so overlapping bundles never bill the same work twice. Counts as a "
                    + "single estimate against the plan limit.")
    @PostMapping("/api/projects/{projectId}/estimates/from-templates")
    public ResponseEntity<EstimateResponse> createFromTemplates(
            @PathVariable UUID projectId,
            @RequestParam("ids") List<UUID> templateIds,
            @Valid @RequestBody EstimateCreateRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        EstimateResponse created = templateService.applyToProject(projectId, templateIds, req, principal.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }
}
