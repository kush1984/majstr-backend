package com.majstr.backend.controller;

import com.majstr.backend.dto.CatalogImportParseResponse;
import com.majstr.backend.dto.TransferCommitRequest;
import com.majstr.backend.dto.TransferCommitResponse;
import com.majstr.backend.dto.TransferParseResponse;
import com.majstr.backend.exception.CatalogImportException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.importer.TransferService;
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
 * «Перенести в Majstr» — bring existing estimates and price lists in, many files at once.
 * {@code /parse} reads ONE file and proposes (never writes; the file is discarded);
 * {@code /commit} writes the confirmed review in one transaction.
 */
@RestController
@RequestMapping("/api/transfer")
@RequiredArgsConstructor
@Tag(name = "Transfer", description = "Move a master's existing estimates and price lists into Majstr")
@SecurityRequirement(name = "bearer-jwt")
public class TransferController {

    private final TransferService transferService;

    @Operation(summary = "Read one file (PDF, Excel, CSV or a photo): estimate or price list, its object and positions")
    @PostMapping(value = "/parse", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TransferParseResponse parse(@RequestParam("file") MultipartFile file,
                                       // A table's columns the master re-picked; all absent = guess.
                                       @RequestParam(value = "nameCol", required = false) Integer nameCol,
                                       @RequestParam(value = "priceCol", required = false) Integer priceCol,
                                       @RequestParam(value = "unitCol", required = false) Integer unitCol,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        if (file == null || file.isEmpty()) {
            throw new CatalogImportException("error.import.empty");
        }
        CatalogImportParseResponse.Mapping chosen = nameCol == null && priceCol == null && unitCol == null
                ? null : new CatalogImportParseResponse.Mapping(nameCol, priceCol, unitCol);
        try {
            return transferService.parse(principal.id(), file.getOriginalFilename(), file.getContentType(),
                    file.getBytes(), chosen);
        } catch (IOException e) {
            throw new CatalogImportException("error.import.unreadable");
        }
    }

    @Operation(summary = "Write the confirmed transfer: price lists to the catalog, estimates to objects")
    @PostMapping("/commit")
    public TransferCommitResponse commit(@Valid @RequestBody TransferCommitRequest req,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        return transferService.commit(principal.id(), req);
    }
}
