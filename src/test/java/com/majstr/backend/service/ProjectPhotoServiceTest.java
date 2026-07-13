package com.majstr.backend.service;

import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.ProjectPhotoResponse;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.PhotoSource;
import com.majstr.backend.entity.PhotoVisibility;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectPhoto;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.LimitExceededException;
import com.majstr.backend.feature.Feature;
import com.majstr.backend.feature.FeatureGuard;
import com.majstr.backend.feature.FeatureNotAvailableException;
import com.majstr.backend.feature.Limit;
import com.majstr.backend.feature.LimitService;
import com.majstr.backend.repository.ProjectPhotoRepository;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.storage.StorageService;
import com.majstr.backend.storage.StoredObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.access.AccessDeniedException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProjectPhotoServiceTest {

    @Mock private ProjectPhotoRepository photoRepository;
    @Mock private ProjectService projectService;
    @Mock private EstimateService estimateService;
    @Mock private UserRepository userRepository;
    @Mock private FeatureGuard featureGuard;
    @Mock private LimitService limitService;
    @Mock private StorageService storage;

    private ProjectPhotoService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID photoId = UUID.randomUUID();
    private final UUID estimateId = UUID.randomUUID();

    // Minimal valid JPEG magic header (FF D8 FF …).
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};

    @BeforeEach
    void setUp() {
        service = new ProjectPhotoService(photoRepository, projectService, estimateService,
                userRepository, featureGuard, limitService, storage);
    }

    @Test
    void list_isBlockedForFreePlan() {
        given(userRepository.findById(ownerId)).willReturn(Optional.of(freeUser()));
        willThrow(new FeatureNotAvailableException(Feature.PHOTO_REPORTS, Plan.FREE))
                .given(featureGuard).requireFeature(any(User.class), eq(Feature.PHOTO_REPORTS));

        assertThatThrownBy(() -> service.list(projectId, ownerId))
                .isInstanceOf(FeatureNotAvailableException.class);
    }

    @Test
    void upload_manualPhoto_startsPrivate() throws Exception {
        stubGate();
        given(storage.store(any(), anyLong(), anyString(), anyString(), anyString()))
                .willReturn(new StoredObject("photos/a.jpg", 8, "image/jpeg"));
        given(photoRepository.save(any(ProjectPhoto.class))).willAnswer(inv -> inv.getArgument(0));

        ProjectPhotoResponse resp = service.upload(projectId, ownerId,
                file(JPEG), PhotoSource.MANUAL, "  На стіні  ", null);

        assertThat(resp.source()).isEqualTo(PhotoSource.MANUAL);
        assertThat(resp.visibility()).isEqualTo(PhotoVisibility.PRIVATE);
        assertThat(resp.caption()).isEqualTo("На стіні");
        assertThat(resp.estimateId()).isNull();
        // The file URL points at the authenticated stream, never the public /api/files/**.
        assertThat(resp.fileUrl()).startsWith("/api/projects/" + projectId + "/photos/");
    }

    @Test
    void upload_receiptPhoto_isPrivateAndSnapshotsEstimateName() throws Exception {
        stubGate();
        given(estimateService.get(estimateId, ownerId)).willReturn(estimateResponse(projectId, "Кухня"));
        given(storage.store(any(), anyLong(), anyString(), anyString(), anyString()))
                .willReturn(new StoredObject("photos/r.jpg", 8, "image/jpeg"));
        given(photoRepository.save(any(ProjectPhoto.class))).willAnswer(inv -> inv.getArgument(0));

        ProjectPhotoResponse resp = service.upload(projectId, ownerId,
                file(JPEG), PhotoSource.RECEIPT, null, estimateId);

        assertThat(resp.source()).isEqualTo(PhotoSource.RECEIPT);
        assertThat(resp.visibility()).isEqualTo(PhotoVisibility.PRIVATE);
        assertThat(resp.estimateId()).isEqualTo(estimateId);
        assertThat(resp.estimateName()).isEqualTo("Кухня"); // durable snapshot
    }

    @Test
    void upload_rejectedWhenPerObjectPhotoLimitReached_doesNotStore() throws Exception {
        stubGate();
        willThrow(new LimitExceededException(Limit.MAX_PHOTOS_PER_OBJECT, 5, Plan.PRO))
                .given(limitService).requireCanAddPhoto(ownerId, projectId, PhotoSource.MANUAL);

        assertThatThrownBy(() -> service.upload(projectId, ownerId,
                file(JPEG), PhotoSource.MANUAL, null, null))
                .isInstanceOf(LimitExceededException.class);
        verify(storage, never()).store(any(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void upload_receiptPhoto_rejectsEstimateFromAnotherProject() throws Exception {
        stubGate();
        given(estimateService.get(estimateId, ownerId))
                .willReturn(estimateResponse(UUID.randomUUID(), "Чужий")); // different project

        assertThatThrownBy(() -> service.upload(projectId, ownerId,
                file(JPEG), PhotoSource.RECEIPT, null, estimateId))
                .isInstanceOf(AccessDeniedException.class);
        verify(storage, never()).store(any(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void setVisibility_manualCanBeShared() {
        stubGate();
        ProjectPhoto photo = photo(PhotoSource.MANUAL, PhotoVisibility.PRIVATE);
        given(photoRepository.findByIdAndProjectId(photoId, projectId)).willReturn(Optional.of(photo));

        ProjectPhotoResponse resp = service.setVisibility(projectId, photoId, ownerId, PhotoVisibility.SHARED);

        assertThat(resp.visibility()).isEqualTo(PhotoVisibility.SHARED);
        assertThat(photo.getVisibility()).isEqualTo(PhotoVisibility.SHARED);
    }

    @Test
    void setVisibility_receiptCannotBeShared() {
        stubGate();
        given(photoRepository.findByIdAndProjectId(photoId, projectId))
                .willReturn(Optional.of(photo(PhotoSource.RECEIPT, PhotoVisibility.PRIVATE)));

        assertThatThrownBy(() -> service.setVisibility(projectId, photoId, ownerId, PhotoVisibility.SHARED))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void readSharedFile_rejectsPrivatePhoto() {
        given(photoRepository.findByIdAndProjectId(photoId, projectId))
                .willReturn(Optional.of(photo(PhotoSource.MANUAL, PhotoVisibility.PRIVATE)));

        assertThatThrownBy(() -> service.readSharedFile(projectId, photoId))
                .isInstanceOf(com.majstr.backend.exception.ResourceNotFoundException.class);
    }

    // ---- helpers ----------------------------------------------------------

    private void stubGate() {
        given(userRepository.findById(ownerId)).willReturn(Optional.of(freeUser()));
        given(projectService.loadOwned(projectId, ownerId))
                .willReturn(Project.builder().id(projectId).build());
    }

    private MockMultipartFile file(byte[] bytes) {
        return new MockMultipartFile("file", "photo.jpg", "image/jpeg", bytes);
    }

    private User freeUser() {
        return User.builder().id(ownerId).plan(Plan.PRO).build();
    }

    private ProjectPhoto photo(PhotoSource source, PhotoVisibility visibility) {
        return ProjectPhoto.builder()
                .id(photoId)
                .projectId(projectId)
                .storageKey("photos/x.jpg")
                .source(source)
                .visibility(visibility)
                .createdAt(Instant.now())
                .build();
    }

    private EstimateResponse estimateResponse(UUID projectId, String name) {
        BigDecimal zero = BigDecimal.ZERO;
        return new EstimateResponse(estimateId, projectId, name, EstimateStatus.DRAFT,
                null, null, Instant.now(), Instant.now(), List.of(), zero, zero, zero, null, zero);
    }
}
