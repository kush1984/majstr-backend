package com.majstr.backend.controller;

import com.lowagie.text.DocumentException;
import com.majstr.backend.dto.PublicPriceListView;
import com.majstr.backend.service.PriceListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
@Tag(name = "Public price list", description = "The client's view of a master's price list (V205)")
public class PublicPriceListController {

    private final PriceListService priceListService;

    @Operation(summary = "The master's price list behind a live link (404 once closed)")
    @GetMapping("/price-list/{token}")
    public PublicPriceListView get(@PathVariable String token) {
        return priceListService.publicView(token);
    }

    @Operation(summary = "The master's own look at his price list, through a ten-minute preview pass (no PDF)")
    @GetMapping("/price-list-preview/{pass}")
    public PublicPriceListView preview(@PathVariable String pass) {
        return priceListService.previewView(pass);
    }

    @Operation(summary = "The same price list as a PDF")
    @GetMapping(value = "/price-list/{token}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable String token) throws IOException, DocumentException {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"price-list.pdf\"")
                .body(priceListService.renderPublicPdf(token));
    }
}
