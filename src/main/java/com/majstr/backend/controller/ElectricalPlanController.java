package com.majstr.backend.controller;

import com.majstr.backend.dto.ElectricalPlanParseResponse;
import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.measurement.ElectricalPlanService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * Count electrical points off a plan (PDF/photo) with LLM vision — PRO-gated via the
 * measurements feature. Parse only: it returns a review proposal and persists nothing (the
 * file is discarded). The confirmed counts are committed through the ordinary
 * "add measurement element" endpoint as an {@code ELECTRICAL_POINTS} element, so there is no
 * second write path to keep in sync.
 */
@RestController
@RequestMapping("/api/projects/{id}/measurements/electrical")
@RequiredArgsConstructor
@Tag(name = "Electrical plan", description = "Count points off an electrical plan (LLM, PRO)")
@SecurityRequirement(name = "bearer-jwt")
public class ElectricalPlanController {

    private final ElectricalPlanService electricalPlanService;

    @Operation(summary = "Parse an electrical plan — returns counted points for review (no write)")
    @PostMapping(value = "/plan/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ElectricalPlanParseResponse parse(@PathVariable UUID id,
                                             @RequestParam("file") MultipartFile file,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        if (file == null || file.isEmpty()) {
            throw new CatalogImportException("error.import.empty");
        }
        try {
            return electricalPlanService.parse(principal.id(), id, file.getOriginalFilename(),
                    file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new CatalogImportException("error.import.unreadable");
        }
    }
}
