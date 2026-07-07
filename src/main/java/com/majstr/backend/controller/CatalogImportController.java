package com.majstr.backend.controller;

import com.majstr.backend.dto.CatalogImportCommitRequest;
import com.majstr.backend.dto.CatalogImportCommitResponse;
import com.majstr.backend.dto.CatalogImportParseResponse;
import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.importer.CatalogImportParser;
import com.majstr.backend.service.importer.CatalogImportService;
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
 * "Import my price list" into the caller's catalog. {@code /parse} returns a review
 * proposal (never writes); {@code /commit} persists the master-confirmed list with
 * dedup. Two parse entry points share the parser: a file upload (.xlsx/.xls/.csv) and
 * pasted tab-separated text. The uploaded file is parsed in memory and discarded.
 */
@RestController
@RequestMapping("/api/catalog/import")
@RequiredArgsConstructor
@Tag(name = "Catalog import", description = "Import a master's own price list into the catalog")
@SecurityRequirement(name = "bearer-jwt")
public class CatalogImportController {

    private final CatalogImportParser parser;
    private final CatalogImportService importService;

    @Operation(summary = "Parse an uploaded price list (.xlsx/.xls/.csv) — returns a review proposal")
    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CatalogImportParseResponse parseFile(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new CatalogImportException("error.import.empty");
        }
        try {
            return parser.parseFile(file.getOriginalFilename(), file.getBytes());
        } catch (IOException e) {
            throw new CatalogImportException("error.import.unreadable");
        }
    }

    @Operation(summary = "Parse pasted tab-separated rows — returns a review proposal")
    @PostMapping(value = "/parse", consumes = MediaType.TEXT_PLAIN_VALUE)
    public CatalogImportParseResponse parseText(@RequestBody String text) {
        return parser.parseText(text);
    }

    @Operation(summary = "Commit the confirmed import into the catalog (dedup update/skip)")
    @PostMapping("/commit")
    public CatalogImportCommitResponse commit(@Valid @RequestBody CatalogImportCommitRequest req,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        return importService.commit(principal.id(), req);
    }
}
