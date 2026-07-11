package com.majstr.backend.controller;

import com.majstr.backend.dto.EstimateImportCommitRequest;
import com.majstr.backend.dto.EstimateImportCommitResponse;
import com.majstr.backend.dto.EstimateImportParseResponse;
import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.importer.EstimateImportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * Import a ready estimate onto an object from an Excel/CSV file or a photo via LLM
 * extraction (PRO-gated — {@code Feature.ESTIMATE_IMPORT}). {@code /parse} returns a
 * review proposal (never writes; the file is parsed then discarded); {@code /commit}
 * creates the estimate on the target project and upserts the ticked positions into
 * the master's catalog. Feature gate + ownership enforced in the service layer.
 */
@RestController
@RequestMapping("/api/estimates/import")
@RequiredArgsConstructor
@Tag(name = "Estimate import", description = "Import a ready estimate from Excel/CSV or a photo (LLM)")
@SecurityRequirement(name = "bearer-jwt")
public class EstimateImportController {

    private final EstimateImportService importService;

    @Operation(summary = "Parse an uploaded estimate (Excel/CSV/photo) — returns a review proposal")
    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public EstimateImportParseResponse parse(@RequestParam("file") MultipartFile file,
                                             @AuthenticationPrincipal UserPrincipal principal) {
        if (file == null || file.isEmpty()) {
            throw new CatalogImportException("error.import.empty");
        }
        try {
            return importService.parse(principal.id(), file.getOriginalFilename(),
                    file.getContentType(), file.getBytes());
        } catch (IOException e) {
            throw new CatalogImportException("error.import.unreadable");
        }
    }

    @Operation(summary = "Commit the confirmed import — creates the estimate + upserts the catalog")
    @PostMapping("/commit")
    public EstimateImportCommitResponse commit(@Valid @RequestBody EstimateImportCommitRequest req,
                                               @AuthenticationPrincipal UserPrincipal principal) {
        return importService.commit(principal.id(), req);
    }
}
