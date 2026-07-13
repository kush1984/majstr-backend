package com.majstr.backend.service;

import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.ProjectPhotoResponse;
import com.majstr.backend.entity.PhotoSource;
import com.majstr.backend.entity.PhotoVisibility;
import com.majstr.backend.entity.ProjectPhoto;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.feature.LimitService;
import com.majstr.backend.repository.ProjectPhotoRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.service.ImageContentTypeDetector.ImageKind;
import com.majstr.backend.storage.StorageService;
import com.majstr.backend.storage.StoredObject;
import com.majstr.backend.storage.UnsupportedMediaTypeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Object photos («Фото» tab), gated by {@code Feature.PHOTO_REPORTS} (all plans today) and
 * owner-scoped via {@link ProjectService#loadOwned}. Two sources: RECEIPT (always PRIVATE,
 * linked to the estimate whose lines were parsed) and MANUAL (progress photo, PRIVATE by
 * default, toggleable to SHARED for the client portal). Files go through {@link StorageService}
 * (magic-byte validated like the logo) and are served only through authenticated / token-gated
 * endpoints — the storage key is never exposed. Deleting the object cascades (FK).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectPhotoService {

    private static final String PHOTO_PREFIX = "photos";
    private static final int HEADER_PEEK_BYTES = 16;
    /** Server-side hard cap. The PWA downscales to ~2048px first, so real uploads are far
     *  smaller; this only rejects a pathologically large original. */
    private static final long MAX_PHOTO_BYTES = 8L * 1024 * 1024;

    private final ProjectPhotoRepository photoRepository;
    private final ProjectService projectService;
    private final EstimateService estimateService;
    private final UserRepository userRepository;
    private final FeatureGuard featureGuard;
    private final LimitService limitService;
    private final StorageService storage;

    /** The bytes + content type of a stored photo, for streaming responses. */
    public record PhotoFile(byte[] bytes, String contentType) {}

    @Transactional(readOnly = true)
    public List<ProjectPhotoResponse> list(UUID projectId, UUID ownerId) {
        requirePhotos(projectId, ownerId);
        return photoRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(ProjectPhotoResponse::from)
                .toList();
    }

    @Transactional
    public ProjectPhotoResponse upload(UUID projectId, UUID ownerId, MultipartFile file,
                                       PhotoSource source, String caption, UUID estimateId) throws IOException {
        requirePhotos(projectId, ownerId);
        // Per-object cap (progress vs receipt have separate budgets); FREE < PRO.
        limitService.requireCanAddPhoto(ownerId, projectId, source);
        if (file == null || file.isEmpty()) {
            throw new UnsupportedMediaTypeException("error.upload.empty");
        }
        byte[] content = file.getBytes();
        if (content.length < 4) {
            throw new UnsupportedMediaTypeException("error.upload.empty");
        }
        if (content.length > MAX_PHOTO_BYTES) {
            throw new UnsupportedMediaTypeException("error.upload.too-large");
        }
        byte[] header = Arrays.copyOf(content, Math.min(HEADER_PEEK_BYTES, content.length));
        ImageKind kind = ImageContentTypeDetector.detect(header);

        // A receipt photo is always private and linked to its estimate (snapshot the name so
        // the label survives the estimate's deletion); a manual photo starts private.
        String estimateName = null;
        UUID linkedEstimateId = null;
        if (source == PhotoSource.RECEIPT && estimateId != null) {
            EstimateResponse estimate = estimateService.get(estimateId, ownerId);
            if (!estimate.projectId().equals(projectId)) {
                throw new AccessDeniedException("Estimate does not belong to project " + projectId);
            }
            linkedEstimateId = estimateId;
            estimateName = estimate.name();
        }

        StoredObject stored = storage.store(
                new ByteArrayInputStream(content), content.length,
                PHOTO_PREFIX, kind.extension, kind.contentType);

        ProjectPhoto photo = photoRepository.save(ProjectPhoto.builder()
                .projectId(projectId)
                .storageKey(stored.key())
                .source(source)
                .visibility(PhotoVisibility.PRIVATE)
                .caption(blankToNull(caption))
                .estimateId(linkedEstimateId)
                .estimateNameSnapshot(estimateName)
                .build());
        return ProjectPhotoResponse.from(photo);
    }

    /**
     * Show / hide a photo from the client portal. Only MANUAL photos can be shared — a
     * RECEIPT photo is financial and stays PRIVATE (a request to share it is rejected).
     */
    @Transactional
    public ProjectPhotoResponse setVisibility(UUID projectId, UUID photoId, UUID ownerId,
                                              PhotoVisibility visibility) {
        requirePhotos(projectId, ownerId);
        ProjectPhoto photo = loadPhoto(projectId, photoId);
        if (photo.getSource() == PhotoSource.RECEIPT && visibility == PhotoVisibility.SHARED) {
            throw new AccessDeniedException("Receipt photos cannot be shared with the client");
        }
        photo.setVisibility(visibility);
        return ProjectPhotoResponse.from(photo);
    }

    @Transactional
    public void delete(UUID projectId, UUID photoId, UUID ownerId) {
        requirePhotos(projectId, ownerId);
        ProjectPhoto photo = loadPhoto(projectId, photoId);
        tryDelete(photo.getStorageKey());
        photoRepository.delete(photo);
    }

    /** Read a photo for the authenticated owner (any visibility). */
    @Transactional(readOnly = true)
    public PhotoFile readOwnedFile(UUID projectId, UUID photoId, UUID ownerId) throws IOException {
        requirePhotos(projectId, ownerId);
        return readFile(loadPhoto(projectId, photoId));
    }

    /**
     * Read a SHARED photo of a project for the public portal. The caller (portal) has already
     * validated the share token maps to this project; here we only serve photos that are
     * actually SHARED (never a private / receipt photo). Missing / private → 404.
     */
    @Transactional(readOnly = true)
    public PhotoFile readSharedFile(UUID projectId, UUID photoId) throws IOException {
        ProjectPhoto photo = loadPhoto(projectId, photoId);
        if (photo.getVisibility() != PhotoVisibility.SHARED) {
            throw new ResourceNotFoundException("Photo not found");
        }
        return readFile(photo);
    }

    /** The object's SHARED photos, for the portal view (id + caption only). */
    @Transactional(readOnly = true)
    public List<ProjectPhoto> sharedPhotos(UUID projectId) {
        return photoRepository.findByProjectIdAndVisibilityOrderByCreatedAtDesc(projectId, PhotoVisibility.SHARED);
    }

    // ---- helpers --------------------------------------------------------------

    private PhotoFile readFile(ProjectPhoto photo) throws IOException {
        byte[] bytes;
        try (InputStream in = storage.open(photo.getStorageKey())
                .orElseThrow(() -> new ResourceNotFoundException("Photo file not found"))) {
            bytes = in.readAllBytes();
        }
        String contentType = storage.contentType(photo.getStorageKey()).orElse("application/octet-stream");
        return new PhotoFile(bytes, contentType);
    }

    private void requirePhotos(UUID projectId, UUID ownerId) {
        featureGuard.requireFeature(loadUser(ownerId), Feature.PHOTO_REPORTS);
        projectService.loadOwned(projectId, ownerId); // 403 on a foreign object, 404 if unknown
    }

    private ProjectPhoto loadPhoto(UUID projectId, UUID photoId) {
        return photoRepository.findByIdAndProjectId(photoId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Photo not found: " + photoId));
    }

    private User loadUser(UUID ownerId) {
        return userRepository.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + ownerId));
    }

    private void tryDelete(String key) {
        try {
            storage.delete(key);
        } catch (IOException e) {
            log.warn("Could not delete stored photo {}: {}", key, e.getMessage());
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
