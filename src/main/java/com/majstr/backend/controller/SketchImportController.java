package com.majstr.backend.controller;

import com.majstr.backend.dto.MeasurementsResponse;
import com.majstr.backend.dto.SketchCommitRequest;
import com.majstr.backend.dto.SketchParseResponse;
import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.measurement.SketchImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

/**
 * Recognise a hand-drawn room sketch photo into a DRAFT of measurement rooms/elements via LLM
 * vision (PRO-gated — {@code Feature.SKETCH_IMPORT}). {@code /parse} returns a review proposal
 * (the image is parsed then discarded); {@code /commit} creates the master-confirmed set. The
 * feature gate + ownership are enforced in the service; result is recomputed server-side.
 */
@RestController
@RequestMapping("/api/projects/{id}/measurements/sketch")
@RequiredArgsConstructor
@Tag(name = "Sketch import", description = "Recognise a room sketch photo into measurements (LLM, PRO)")
@SecurityRequirement(name = "bearer-jwt")
public class SketchImportController {

    private final SketchImportService sketchService;

    @Operation(summary = "Parse a room-sketch photo — returns a review proposal (no write)")
    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public SketchParseResponse parse(@PathVariable UUID id,
                                     @RequestParam("file") MultipartFile file,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        if (file == null || file.isEmpty()) {
            throw new CatalogImportException("error.import.empty");
        }
        try {
            return sketchService.parse(principal.id(), id, file.getOriginalFilename(),
                    file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new CatalogImportException("error.import.unreadable");
        }
    }

    @Operation(summary = "Commit the confirmed sketch — creates rooms + measured elements")
    @PostMapping("/commit")
    public MeasurementsResponse commit(@PathVariable UUID id,
                                       @Valid @RequestBody SketchCommitRequest req,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        return sketchService.commit(principal.id(), id, req);
    }
}
