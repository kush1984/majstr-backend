package com.majstr.backend.controller;

import com.majstr.backend.dto.PublicEstimateView;
import com.majstr.backend.dto.PublicPortalView;
import com.majstr.backend.service.ProjectPhotoService;
import com.majstr.backend.service.PublicEstimateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

/**
 * The master's «як побачить клієнт» passes — READS only: no sign, question or PDF route exists under
 * either. {@code /preview/{token}} is one estimate (the {@code ?t=} view); {@code /preview-portal/{token}}
 * is the object's SIGNATURE portal with the ticked estimates. The token sits where every public link's
 * does ({@code /api/public/<kind>/<token>}), so the rate limiter and log masking treat it alike.
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
@Tag(name = "Public portal", description = "Read-only and minimal-write endpoints reachable by share link, no authentication")
public class PublicPreviewController {

    private final PublicEstimateService publicService;

    @Operation(summary = "The client's view of an estimate, through the master's preview pass")
    @GetMapping("/preview/{token}")
    public PublicEstimateView view(@PathVariable String token) {
        return publicService.viewPreview(token);
    }

    @Operation(summary = "Stream a photo the master shared with the client, through the preview pass")
    @GetMapping("/preview/{token}/photos/{photoId}/file")
    public ResponseEntity<Resource> sharedPhoto(@PathVariable String token,
                                              @PathVariable UUID photoId) throws IOException {
        return photo(publicService.readPreviewPhoto(token, photoId));
    }

    @Operation(summary = "The client's view of the object's portal with the ticked estimates, through the master's preview pass")
    @GetMapping("/preview-portal/{token}")
    public PublicPortalView portal(@PathVariable String token) {
        return publicService.viewPortalPreview(token);
    }

    @Operation(summary = "Stream a photo the master shared with the client, through the portal preview pass")
    @GetMapping("/preview-portal/{token}/photos/{photoId}/file")
    public ResponseEntity<Resource> portalPhoto(@PathVariable String token,
                                                @PathVariable UUID photoId) throws IOException {
        return photo(publicService.readPortalPreviewPhoto(token, photoId));
    }

    private static ResponseEntity<Resource> photo(ProjectPhotoService.PhotoFile file) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(file.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(new InputStreamResource(file.content()));
    }
}
