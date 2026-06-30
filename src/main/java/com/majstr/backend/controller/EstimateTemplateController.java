package com.majstr.backend.controller;

import com.majstr.backend.dto.EstimateCreateRequest;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.EstimateTemplateDetail;
import com.majstr.backend.dto.EstimateTemplateSummary;
import com.majstr.backend.dto.SaveAsTemplateRequest;
import com.majstr.backend.dto.TemplateItemRequest;
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
import org.springframework.web.bind.annotation.RequestBody;
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

    @Operation(summary = "Rename my own template (system defaults are read-only)")
    @PatchMapping("/api/estimate-templates/{id}")
    public EstimateTemplateSummary rename(@PathVariable UUID id,
                                          @Valid @RequestBody SaveAsTemplateRequest req,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        return templateService.rename(id, req.name(), principal.id());
    }

    @Operation(summary = "Delete my own template (system defaults are read-only)")
    @DeleteMapping("/api/estimate-templates/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        templateService.delete(id, principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Add a position to my own template (system defaults are read-only)")
    @PostMapping("/api/estimate-templates/{id}/items")
    public EstimateTemplateDetail addItem(@PathVariable UUID id,
                                          @Valid @RequestBody TemplateItemRequest req,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        return templateService.addItem(id, req, principal.id());
    }

    @Operation(summary = "Remove a position from my own template (system defaults are read-only)")
    @DeleteMapping("/api/estimate-templates/{id}/items/{itemId}")
    public EstimateTemplateDetail removeItem(@PathVariable UUID id,
                                             @PathVariable UUID itemId,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        return templateService.removeItem(id, itemId, principal.id());
    }

    @Operation(summary = "Save the current estimate as my own reusable template "
            + "(quantities/prices dropped, names+units kept)")
    @PostMapping("/api/estimates/{id}/save-as-template")
    public ResponseEntity<EstimateTemplateSummary> saveAsTemplate(
            @PathVariable UUID id,
            @Valid @RequestBody SaveAsTemplateRequest req,
            @AuthenticationPrincipal UserPrincipal principal) {
        EstimateTemplateSummary saved = templateService.saveFromEstimate(id, req.name(), principal.id());
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
}
