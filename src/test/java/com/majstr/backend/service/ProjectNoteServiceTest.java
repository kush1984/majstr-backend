package com.majstr.backend.service;

import com.majstr.backend.dto.NoteRequest;
import com.majstr.backend.dto.NoteResponse;
import com.majstr.backend.entity.ProjectNote;
import com.majstr.backend.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import com.majstr.backend.repository.ProjectNoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** Object notes CRUD: owner-scoping, trim/optional handling — no plan gate. Pure Mockito. */
@ExtendWith(MockitoExtension.class)
class ProjectNoteServiceTest {

    @Mock private ProjectNoteRepository noteRepository;
    @Mock private ProjectService projectService;

    private ProjectNoteService service;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID objectId = UUID.randomUUID();
    private final UUID noteId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ProjectNoteService(noteRepository, projectService);
    }

    @Test
    void add_trimsBodyAndDropsBlankOptionalFields() {
        given(noteRepository.save(any(ProjectNote.class))).willAnswer(inv -> inv.getArgument(0));

        service.add(objectId, ownerId, new NoteRequest("  Архітектор Олег ", "   ", "  ключі в консьєржа\nдруга лінія  "));

        verify(projectService).loadOwned(objectId, ownerId); // ownership first
        ArgumentCaptor<ProjectNote> saved = ArgumentCaptor.forClass(ProjectNote.class);
        verify(noteRepository).save(saved.capture());
        ProjectNote note = saved.getValue();
        assertThat(note.getProjectId()).isEqualTo(objectId);
        assertThat(note.getTitle()).isEqualTo("Архітектор Олег");
        assertThat(note.getPhone()).isNull();                 // blank phone → null, not ""
        assertThat(note.getBody()).isEqualTo("ключі в консьєржа\nдруга лінія"); // trimmed, newline kept
    }

    @Test
    void add_keepsPhoneVerbatim() {
        given(noteRepository.save(any(ProjectNote.class))).willAnswer(inv -> inv.getArgument(0));
        service.add(objectId, ownerId, new NoteRequest(null, " 067 123 45 67 ", "дзвонити після 18"));
        ArgumentCaptor<ProjectNote> saved = ArgumentCaptor.forClass(ProjectNote.class);
        verify(noteRepository).save(saved.capture());
        assertThat(saved.getValue().getPhone()).isEqualTo("067 123 45 67"); // trimmed edges, not reformatted
    }

    @Test
    void list_isOwnerScopedAndNewestFirst() {
        given(noteRepository.findByProjectIdOrderByCreatedAtDesc(objectId)).willReturn(List.of(
                ProjectNote.builder().id(noteId).projectId(objectId).body("нова").build()));
        List<NoteResponse> out = service.list(objectId, ownerId);
        verify(projectService).loadOwned(objectId, ownerId);
        assertThat(out).singleElement().extracting(NoteResponse::body).isEqualTo("нова");
    }

    @Test
    void update_editsTheOwnedNote() {
        ProjectNote existing = ProjectNote.builder().id(noteId).projectId(objectId).body("old").build();
        given(noteRepository.findByIdAndProjectId(noteId, objectId)).willReturn(Optional.of(existing));

        service.update(objectId, noteId, ownerId, new NoteRequest("Сантехнік", "+380671234567", "new body"));

        verify(projectService).loadOwned(objectId, ownerId);
        assertThat(existing.getTitle()).isEqualTo("Сантехнік");
        assertThat(existing.getPhone()).isEqualTo("+380671234567");
        assertThat(existing.getBody()).isEqualTo("new body");
    }

    @Test
    void update_missingNote_throwsNotFound() {
        given(noteRepository.findByIdAndProjectId(noteId, objectId)).willReturn(Optional.empty());
        assertThatThrownBy(() -> service.update(objectId, noteId, ownerId,
                new NoteRequest(null, null, "x"))).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_removesTheOwnedNote() {
        ProjectNote existing = ProjectNote.builder().id(noteId).projectId(objectId).body("bye").build();
        given(noteRepository.findByIdAndProjectId(noteId, objectId)).willReturn(Optional.of(existing));
        service.delete(objectId, noteId, ownerId);
        verify(noteRepository).delete(existing);
    }

    @Test
    void add_replayedWithTheSameClientId_returnsTheExistingNote() {
        // Offline authoring: a note written on site replays when the signal returns. Without
        // the id check a lost response would leave the master with the note twice.
        ProjectNote already = ProjectNote.builder()
                .id(noteId).projectId(objectId).body("Ключі в консьєржа").build();
        given(noteRepository.findById(noteId)).willReturn(Optional.of(already));

        var resp = service.add(objectId, ownerId, new NoteRequest(null, null, "Ключі в консьєржа"), noteId);

        assertThat(resp.id()).isEqualTo(noteId);
        verify(noteRepository, never()).save(any(ProjectNote.class)); // no duplicate
    }

    @Test
    void add_clientIdBelongingToAnotherObject_isRefused() {
        // A note is never silently re-homed onto a different object.
        ProjectNote elsewhere = ProjectNote.builder()
                .id(noteId).projectId(UUID.randomUUID()).body("Чужа").build();
        given(noteRepository.findById(noteId)).willReturn(Optional.of(elsewhere));

        assertThatThrownBy(() -> service.add(objectId, ownerId, new NoteRequest(null, null, "x"), noteId))
                .isInstanceOf(AccessDeniedException.class);
        verify(noteRepository, never()).save(any(ProjectNote.class));
    }

    @Test
    void delete_alreadyGoneIsANoOp_notA404() {
        // A replayed delete whose first response was lost must not surface as a rejection.
        given(noteRepository.findByIdAndProjectId(noteId, objectId)).willReturn(Optional.empty());

        assertThatCode(() -> service.delete(objectId, noteId, ownerId)).doesNotThrowAnyException();

        verify(noteRepository, never()).delete(any(ProjectNote.class));
    }

    @Test
    void aForeignObjectIsRefusedBeforeAnyNoteRead() {
        willThrow(new AccessDeniedException("not owner"))
                .given(projectService).loadOwned(objectId, ownerId);
        assertThatThrownBy(() -> service.list(objectId, ownerId)).isInstanceOf(AccessDeniedException.class);
        verify(noteRepository, never()).findByProjectIdOrderByCreatedAtDesc(any());
    }
}
