package com.majstr.backend.service;

import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.ProjectPhotoResponse;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.PhotoSource;
import com.majstr.backend.entity.PhotoVisibility;
import com.majstr.backend.entity.Plan;
import com.majstr.backend.entity.Project;
import com.majstr.backend.entity.ProjectPhoto;
import com.majstr.backend.entity.ProjectPhotoFolder;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.LimitExceededException;
import com.majstr.backend.exception.PhotoFolderInUseException;
import com.majstr.backend.exception.PhotoFolderValidationException;
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

import java.io.ByteArrayInputStream;
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
    @Mock private com.majstr.backend.repository.ProjectPhotoFolderRepository folderRepository;
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
        service = new ProjectPhotoService(photoRepository, folderRepository, projectService,
                estimateService, userRepository, featureGuard, limitService, storage);
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
                file(JPEG), PhotoSource.MANUAL, "  На стіні  ", null, null);

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
                file(JPEG), PhotoSource.RECEIPT, null, estimateId, null);

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
                file(JPEG), PhotoSource.MANUAL, null, null, null))
                .isInstanceOf(LimitExceededException.class);
        verify(storage, never()).store(any(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void upload_receiptPhoto_rejectsEstimateFromAnotherProject() throws Exception {
        stubGate();
        given(estimateService.get(estimateId, ownerId))
                .willReturn(estimateResponse(UUID.randomUUID(), "Чужий")); // different project

        assertThatThrownBy(() -> service.upload(projectId, ownerId,
                file(JPEG), PhotoSource.RECEIPT, null, estimateId, null))
                .isInstanceOf(AccessDeniedException.class);
        verify(storage, never()).store(any(), anyLong(), anyString(), anyString(), anyString());
    }

    @Test
    void upload_withoutAFolder_routesBySource_soNoPhotoLandsOutsideOne() throws Exception {
        stubGate();
        given(storage.store(any(), anyLong(), anyString(), anyString(), anyString()))
                .willReturn(new StoredObject("photos/a.jpg", 8, "image/jpeg"));
        given(photoRepository.save(any(ProjectPhoto.class))).willAnswer(inv -> inv.getArgument(0));

        assertThat(service.upload(projectId, ownerId, file(JPEG),
                PhotoSource.RECEIPT, null, null, null).folder())
                .isEqualTo(ProjectPhoto.FOLDER_RECEIPTS);
        assertThat(service.upload(projectId, ownerId, file(JPEG),
                PhotoSource.MANUAL, null, null, null).folder())
                .isNull(); // «Інше»
    }

    @Test
    void upload_intoACustomFolder_landsThere_andMintsTheFolder() throws Exception {
        stubGate();
        given(storage.store(any(), anyLong(), anyString(), anyString(), anyString()))
                .willReturn(new StoredObject("photos/a.jpg", 8, "image/jpeg"));
        given(photoRepository.save(any(ProjectPhoto.class))).willAnswer(inv -> inv.getArgument(0));
        given(folderRepository.findByProjectIdAndName(projectId, "Санвузол")).willReturn(Optional.empty());
        given(folderRepository.save(any(ProjectPhotoFolder.class))).willAnswer(inv -> inv.getArgument(0));

        ProjectPhotoResponse resp = service.upload(projectId, ownerId, file(JPEG),
                PhotoSource.MANUAL, null, null, "Санвузол");

        assertThat(resp.folder()).isEqualTo("Санвузол");
        verify(folderRepository).save(any(ProjectPhotoFolder.class)); // the folder now exists
    }

    @Test
    void upload_intoTheReceiptsFolderByItsUkrainianName_foldsOntoTheSystemValue() throws Exception {
        // The tab uploads the folder it is standing in, and the master may have typed «Чеки»
        // by hand — a twin folder next to the reserved one would split the receipts in two.
        stubGate();
        given(storage.store(any(), anyLong(), anyString(), anyString(), anyString()))
                .willReturn(new StoredObject("photos/a.jpg", 8, "image/jpeg"));
        given(photoRepository.save(any(ProjectPhoto.class))).willAnswer(inv -> inv.getArgument(0));

        ProjectPhotoResponse resp = service.upload(projectId, ownerId, file(JPEG),
                PhotoSource.RECEIPT, null, null, "Чеки");

        assertThat(resp.folder()).isEqualTo(ProjectPhoto.FOLDER_RECEIPTS);
        verify(folderRepository, never()).save(any());
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
    void setVisibility_receiptCanNowBeShared() {
        // payments-economy-portal iteration lifted the old "receipts always PRIVATE" rule —
        // sharing a receipt is a deliberate master action, same as a progress photo.
        stubGate();
        ProjectPhoto photo = photo(PhotoSource.RECEIPT, PhotoVisibility.PRIVATE);
        given(photoRepository.findByIdAndProjectId(photoId, projectId)).willReturn(Optional.of(photo));

        ProjectPhotoResponse resp = service.setVisibility(projectId, photoId, ownerId, PhotoVisibility.SHARED);

        assertThat(resp.visibility()).isEqualTo(PhotoVisibility.SHARED);
        assertThat(photo.getVisibility()).isEqualTo(PhotoVisibility.SHARED);
    }

    @Test
    void readSharedFile_servesASharedReceiptToo() throws Exception {
        given(photoRepository.findByIdAndProjectId(photoId, projectId))
                .willReturn(Optional.of(photo(PhotoSource.RECEIPT, PhotoVisibility.SHARED)));
        given(storage.open(anyString())).willReturn(Optional.of(new ByteArrayInputStream("x".getBytes())));
        given(storage.contentType(anyString())).willReturn(Optional.of("image/jpeg"));

        ProjectPhotoService.PhotoFile file = service.readSharedFile(projectId, photoId);

        assertThat(file.contentType()).isEqualTo("image/jpeg");
    }

    @Test
    void readSharedFile_rejectsPrivatePhoto() {
        given(photoRepository.findByIdAndProjectId(photoId, projectId))
                .willReturn(Optional.of(photo(PhotoSource.MANUAL, PhotoVisibility.PRIVATE)));

        assertThatThrownBy(() -> service.readSharedFile(projectId, photoId))
                .isInstanceOf(com.majstr.backend.exception.ResourceNotFoundException.class);
    }

    // ---- helpers ----------------------------------------------------------

    // ---- folders (photo-folders; round 2 fixed the status these used to answer) ----

    @Test
    void createFolder_reservedOrBlankName_is400NotUnsupportedMediaType() {
        stubGate();

        assertThatThrownBy(() -> service.createFolder(projectId, ownerId, "RECEIPTS"))
                .isInstanceOf(PhotoFolderValidationException.class);
    }

    @Test
    void createFolder_nameLongerThanTheColumn_is400() {
        stubGate();

        assertThatThrownBy(() -> service.createFolder(projectId, ownerId, "x".repeat(101)))
                .isInstanceOf(PhotoFolderValidationException.class);
    }

    @Test
    void createFolder_localizedDefaultName_foldsOntoTheSystemValueInsteadOfATwin() {
        stubGate();

        // «Чеки» is what the PWA renders for the reserved RECEIPTS folder, so typing it by hand must
        // not mint a second one — it normalizes to the reserved value, which is then refused.
        assertThatThrownBy(() -> service.createFolder(projectId, ownerId, "чеки"))
                .isInstanceOf(PhotoFolderValidationException.class);
        verify(folderRepository, never()).save(any());
    }

    @Test
    void deleteFolder_whilePhotosStillCarryTheName_is409() {
        stubGate();
        UUID folderId = UUID.randomUUID();
        given(folderRepository.findByIdAndProjectId(folderId, projectId)).willReturn(Optional.of(
                com.majstr.backend.entity.ProjectPhotoFolder.builder().id(folderId)
                        .projectId(projectId).name("Фасад").build()));
        given(photoRepository.existsByProjectIdAndFolder(projectId, "Фасад")).willReturn(true);

        assertThatThrownBy(() -> service.deleteFolder(projectId, folderId, ownerId))
                .isInstanceOf(PhotoFolderInUseException.class);
        verify(folderRepository, never()).delete(any());
    }

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
                null, null, Instant.now(), Instant.now(), List.of(), zero, zero, zero, null, zero, List.of());
    }
}
