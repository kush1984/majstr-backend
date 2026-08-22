package com.majstr.backend.service;

import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.ProjectPhotoFolderResponse;
import com.majstr.backend.dto.ProjectPhotoResponse;
import com.majstr.backend.entity.PhotoSource;
import com.majstr.backend.entity.PhotoVisibility;
import com.majstr.backend.entity.ProjectPhoto;
import com.majstr.backend.entity.ProjectPhotoFolder;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.PhotoFolderInUseException;
import com.majstr.backend.exception.PhotoFolderValidationException;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.feature.LimitService;
import com.majstr.backend.repository.ProjectPhotoFolderRepository;
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
import org.springframework.transaction.annotation.Propagation;
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
 * owner-scoped via {@link ProjectService#loadOwned}. Two sources: RECEIPT (linked to the
 * estimate whose lines were parsed) and MANUAL (progress photo) — both start PRIVATE and are
 * toggleable to SHARED for the client portal (payments-economy-portal iteration lifted the old
 * "receipts are always PRIVATE" rule; a master now explicitly shares a receipt, per-photo, same
 * flow as a progress photo). Files go through {@link StorageService} (magic-byte validated like
 * the logo) and are served only through authenticated / token-gated endpoints — the storage key
 * is never exposed. Deleting the object cascades (FK).
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
    private final ProjectPhotoFolderRepository folderRepository;
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
                                       PhotoSource source, String caption, UUID estimateId,
                                       String folder) throws IOException {
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
                // Folder routing (photo-folders): an upload made from INSIDE a folder says so, and
                // that wins. With nothing said, receipts from ANY flow land in «Чеки» and everything
                // else in «Інше» (null) — no photo is ever left outside a folder.
                .folder(resolveUploadFolder(projectId, source, folder))
                .build());
        return ProjectPhotoResponse.from(photo);
    }

    /**
     * Where an upload lands. {@code null} (the param absent) = "nothing said" → the source's own
     * default folder; anything else is an explicit target, blank included ("" = «Інше»), so the
     * Фото tab can upload straight into the folder the master is standing in.
     */
    private String resolveUploadFolder(UUID projectId, PhotoSource source, String requested) {
        if (requested == null) {
            return source == PhotoSource.RECEIPT ? ProjectPhoto.FOLDER_RECEIPTS : null;
        }
        String value = normalizeFolder(requested);
        if (value != null && !ProjectPhoto.FOLDER_RECEIPTS.equals(value)) {
            ensureFolderExists(projectId, value); // uploading into a new name creates the folder
        }
        return value;
    }

    /**
     * Move a photo between the Фото tab's folders (photo-folders): the reserved
     * {@link ProjectPhoto#FOLDER_RECEIPTS} = «Чеки», null/blank = «Інше», anything else = a
     * master-invented name (created simply by being used). Internal organization only — the
     * client portal never sees folders.
     */
    @Transactional
    public ProjectPhotoResponse setFolder(UUID projectId, UUID photoId, UUID ownerId, String folder) {
        requirePhotos(projectId, ownerId);
        ProjectPhoto photo = loadPhoto(projectId, photoId);
        String value = normalizeFolder(folder);
        if (value != null && !ProjectPhoto.FOLDER_RECEIPTS.equals(value)) {
            ensureFolderExists(projectId, value); // moving into a new name creates the folder
        }
        photo.setFolder(value);
        return ProjectPhotoResponse.from(photo);
    }

    /** The object's CUSTOM folders (persisted so empty ones survive); the two defaults —
     *  «Чеки»/«Інше» — are virtual and rendered by the PWA unconditionally. */
    @Transactional(readOnly = true)
    public List<ProjectPhotoFolderResponse> listFolders(UUID projectId, UUID ownerId) {
        requirePhotos(projectId, ownerId);
        return folderRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(f -> new ProjectPhotoFolderResponse(f.getId(), f.getName()))
                .toList();
    }

    /** Create an EMPTY folder ahead of its photos (master decision). Idempotent on the name. */
    @Transactional
    public ProjectPhotoFolderResponse createFolder(UUID projectId, UUID ownerId, String name) {
        requirePhotos(projectId, ownerId);
        String value = normalizeFolder(name);
        if (value == null || ProjectPhoto.FOLDER_RECEIPTS.equals(value)) {
            throw new PhotoFolderValidationException("error.photos.folder-name-invalid");
        }
        ProjectPhotoFolder folder = ensureFolderExists(projectId, value);
        return new ProjectPhotoFolderResponse(folder.getId(), folder.getName());
    }

    /** Delete a custom folder — only while EMPTY: photos reference folders by name, and a delete
     *  must never silently re-file someone's photos. */
    @Transactional
    public void deleteFolder(UUID projectId, UUID folderId, UUID ownerId) {
        requirePhotos(projectId, ownerId);
        ProjectPhotoFolder folder = folderRepository.findByIdAndProjectId(folderId, projectId)
                .orElseThrow(() -> new ResourceNotFoundException("Folder not found: " + folderId));
        if (photoRepository.existsByProjectIdAndFolder(projectId, folder.getName())) {
            throw new PhotoFolderInUseException("error.photos.folder-not-empty");
        }
        folderRepository.delete(folder);
    }

    private ProjectPhotoFolder ensureFolderExists(UUID projectId, String name) {
        return folderRepository.findByProjectIdAndName(projectId, name)
                .orElseGet(() -> folderRepository.save(ProjectPhotoFolder.builder()
                        .projectId(projectId).name(name).build()));
    }

    /** Trim; blank → null («Інше»); the localized default names map onto the system values so a
     *  master typing «Чеки»/«Інше» by hand cannot create a confusing twin folder. */
    private static String normalizeFolder(String folder) {
        String value = blankToNull(folder);
        if (value == null) {
            return null;
        }
        if (value.length() > 100) {
            throw new PhotoFolderValidationException("error.photos.folder-too-long");
        }
        if (value.equalsIgnoreCase(ProjectPhoto.FOLDER_RECEIPTS) || matchesAlias(value, ProjectPhoto.RECEIPTS_FOLDER_ALIASES)) {
            return ProjectPhoto.FOLDER_RECEIPTS;
        }
        if (matchesAlias(value, ProjectPhoto.DEFAULT_FOLDER_ALIASES)) {
            return null;
        }
        return value;
    }

    private static boolean matchesAlias(String value, List<String> aliases) {
        return aliases.stream().anyMatch(a -> a.equalsIgnoreCase(value));
    }

    /**
     * File an act receipt's photo into the Фото tab as its own copy («Чеки» folder) — the act keeps
     * ITS copy untouched, so deleting this one can never change a signed act (frozen-copy rule).
     * <p>REQUIRES_NEW on purpose: the caller (an act receipt add) is mid-transaction, and a failed
     * repository write inside a JOINED transaction poisons that persistence context — the copy is a
     * convenience, it must never cost the master the receipt. The caller swallows what escapes.</p>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void saveReceiptCopy(UUID projectId, UUID ownerId, byte[] content, ImageKind kind,
                                String caption) throws IOException {
        limitService.requireCanAddPhoto(ownerId, projectId, PhotoSource.RECEIPT);
        StoredObject stored = storage.store(
                new ByteArrayInputStream(content), content.length,
                PHOTO_PREFIX, kind.extension, kind.contentType);
        photoRepository.save(ProjectPhoto.builder()
                .projectId(projectId)
                .storageKey(stored.key())
                .source(PhotoSource.RECEIPT)
                .visibility(PhotoVisibility.PRIVATE)
                .caption(blankToNull(caption))
                .folder(ProjectPhoto.FOLDER_RECEIPTS)
                .build());
    }

    /**
     * Show / hide a photo from the client portal — RECEIPT and MANUAL alike (payments-economy-
     * portal iteration: sharing a receipt is a deliberate master action, "show the client proof
     * of what was bought", the same trust/transparency reasoning as sharing a progress photo).
     */
    @Transactional
    public ProjectPhotoResponse setVisibility(UUID projectId, UUID photoId, UUID ownerId,
                                              PhotoVisibility visibility) {
        requirePhotos(projectId, ownerId);
        ProjectPhoto photo = loadPhoto(projectId, photoId);
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
