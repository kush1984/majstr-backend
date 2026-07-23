package com.majstr.backend.controller;

import com.lowagie.text.DocumentException;
import com.majstr.backend.dto.PublicPortalView;
import com.majstr.backend.dto.QuestionRequest;
import com.majstr.backend.dto.QuestionResponse;
import com.majstr.backend.dto.SignRequest;
import com.majstr.backend.service.ProjectPhotoService;
import com.majstr.backend.service.PublicEstimateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * The object-level portal (?p= token): one page, a section per estimate the
 * master chose to show; sign / question / PDF are addressed per estimate under
 * the same token. The legacy per-estimate endpoints live in
 * {@link PublicEstimateController} and stay for already-sent URLs.
 */
@RestController
@RequestMapping("/api/public/portal")
@RequiredArgsConstructor
@Tag(name = "Public portal (project)", description = "Object-level portal endpoints reachable by the project share link, no authentication")
public class PublicPortalController {

    private final PublicEstimateService publicService;

    @Operation(summary = "Get the object's portal: a section per shared estimate")
    @GetMapping("/{token}")
    public PublicPortalView view(@PathVariable String token) {
        return publicService.viewPortal(token);
    }

    @Operation(summary = "Client signs one estimate of the portal")
    @PostMapping("/{token}/estimates/{estimateId}/sign")
    public PublicPortalView sign(@PathVariable String token,
                                 @PathVariable UUID estimateId,
                                 @Valid @RequestBody SignRequest req,
                                 HttpServletRequest request) {
        return publicService.signPortal(token, estimateId, req, clientIp(request));
    }

    @Operation(summary = "Client asks a question about one estimate of the portal")
    @PostMapping("/{token}/estimates/{estimateId}/question")
    public ResponseEntity<QuestionResponse> ask(@PathVariable String token,
                                                @PathVariable UUID estimateId,
                                                @Valid @RequestBody QuestionRequest req,
                                                HttpServletRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(publicService.askPortalQuestion(token, estimateId, req, clientIp(request)));
    }

    @Operation(summary = "Download one estimate's PDF (public)")
    @GetMapping(value = "/{token}/estimates/{estimateId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> pdf(@PathVariable String token,
                                      @PathVariable UUID estimateId) throws IOException, DocumentException {
        byte[] body = publicService.renderPortalPdf(token, estimateId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"estimate.pdf\"")
                .body(body);
    }

    @Operation(summary = "Stream a photo the master shared with the client (SHARED only)")
    @GetMapping("/{token}/photos/{photoId}/file")
    public ResponseEntity<byte[]> sharedPhoto(@PathVariable String token,
                                              @PathVariable UUID photoId) throws IOException {
        ProjectPhotoService.PhotoFile file = publicService.readPortalSharedPhoto(token, photoId);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(file.bytes());
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
