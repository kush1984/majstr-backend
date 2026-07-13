package com.majstr.backend.controller;

import com.majstr.backend.dto.PhotoVisibilityRequest;
import com.majstr.backend.dto.ProjectPhotoResponse;
import com.majstr.backend.entity.PhotoSource;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ProjectPhotoService;
import com.majstr.backend.service.ProjectPhotoService.PhotoFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Object photos («Фото» tab), PHOTO_REPORTS-gated + owner-scoped in the service. Files are
 * served here through an <b>authenticated</b> stream (never the public {@code /api/files/**}):
 * receipt photos are private financial data, and manual photos are private until the master
 * shares them with the client (the portal has its own token-gated stream for SHARED photos).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/photos")
@RequiredArgsConstructor
@Tag(name = "Object photos", description = "Per-object photos: private receipts + shareable progress photos")
@SecurityRequirement(name = "bearer-jwt")
public class ProjectPhotoController {

    private final ProjectPhotoService photoService;

    @Operation(summary = "List the object's photos (owner)")
    @GetMapping
    public List<ProjectPhotoResponse> list(@PathVariable UUID projectId,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        return photoService.list(projectId, principal.id());
    }

    @Operation(summary = "Upload a photo (MANUAL progress photo, or a RECEIPT linked to an estimate)")
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProjectPhotoResponse> upload(
            @PathVariable UUID projectId,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "source", defaultValue = "MANUAL") PhotoSource source,
            @RequestParam(value = "caption", required = false) String caption,
            @RequestParam(value = "estimateId", required = false) UUID estimateId,
            @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(photoService.upload(projectId, principal.id(), file, source, caption, estimateId));
    }

    @Operation(summary = "Show / hide a photo from the client portal (MANUAL only)")
    @PatchMapping("/{photoId}")
    public ProjectPhotoResponse setVisibility(@PathVariable UUID projectId,
                                              @PathVariable UUID photoId,
                                              @Valid @RequestBody PhotoVisibilityRequest req,
                                              @AuthenticationPrincipal UserPrincipal principal) {
        return photoService.setVisibility(projectId, photoId, principal.id(), req.visibility());
    }

    @Operation(summary = "Delete a photo")
    @DeleteMapping("/{photoId}")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId,
                                       @PathVariable UUID photoId,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        photoService.delete(projectId, photoId, principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Stream a photo file (authenticated owner)")
    @GetMapping("/{photoId}/file")
    public ResponseEntity<byte[]> file(@PathVariable UUID projectId,
                                       @PathVariable UUID photoId,
                                       @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        PhotoFile f = photoService.readOwnedFile(projectId, photoId, principal.id());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline")
                .body(f.bytes());
    }
}
