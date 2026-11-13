package com.majstr.backend.controller;

import com.lowagie.text.DocumentException;
import com.majstr.backend.dto.ClientPreviewResponse;
import com.majstr.backend.dto.PriceListRequest;
import com.majstr.backend.dto.PriceListResponse;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.PriceListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/price-list")
@RequiredArgsConstructor
@Tag(name = "Price list", description = "«Поділитися прайсом»: the master's ticked works for a client (V205)")
@SecurityRequirement(name = "bearer-jwt")
public class PriceListController {

    private final PriceListService priceListService;

    @Operation(summary = "The ticks, the live link and which positions still carry the library's price")
    @GetMapping
    public PriceListResponse get(@AuthenticationPrincipal UserPrincipal principal) {
        return priceListService.state(principal.id());
    }

    @Operation(summary = "Save the ticked positions and make sure a live link exists",
            description = "Only the caller's own priced works are ticked; any other id is ignored.")
    @PutMapping
    public PriceListResponse save(@Valid @RequestBody PriceListRequest request,
                                  @AuthenticationPrincipal UserPrincipal principal) {
        return priceListService.save(principal.id(), request);
    }

    @Operation(summary = "Save the ticks and open the client's page on a ten-minute preview pass — no link is minted")
    @PostMapping("/preview")
    public ClientPreviewResponse preview(@Valid @RequestBody PriceListRequest request,
                                         @AuthenticationPrincipal UserPrincipal principal) {
        return priceListService.preview(principal.id(), request);
    }

    @Operation(summary = "Close the link: the client's page stops opening; the next share mints a new one")
    @DeleteMapping("/link")
    public PriceListResponse revoke(@AuthenticationPrincipal UserPrincipal principal) {
        return priceListService.revoke(principal.id());
    }

    @Operation(summary = "The saved price list as a PDF")
    @GetMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@AuthenticationPrincipal UserPrincipal principal)
            throws IOException, DocumentException {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"price-list.pdf\"")
                .body(priceListService.renderPdf(principal.id()));
    }
}
