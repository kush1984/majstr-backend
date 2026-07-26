package com.majstr.backend.service;

import com.majstr.backend.dto.NoteRequest;
import com.majstr.backend.dto.NoteResponse;
import com.majstr.backend.entity.ProjectNote;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.ProjectNoteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Object notes (Нотатки) — free text + optional title/phone a master keeps against an
 * object. <b>Owner-scoped</b> (the object must belong to the caller) but <b>not plan-gated</b>
 * (available on every plan — it's a retention utility, not monetization). PRIVATE: nothing
 * here is ever part of an estimate/portal/PDF/share response. Newest note first.
 */
@Service
@RequiredArgsConstructor
public class ProjectNoteService {

    private final ProjectNoteRepository noteRepository;
    private final ProjectService projectService;

    @Transactional(readOnly = true)
    public List<NoteResponse> list(UUID objectId, UUID ownerId) {
        projectService.loadOwned(objectId, ownerId); // existence + ownership (404 / 403)
        return noteRepository.findByProjectIdOrderByCreatedAtDesc(objectId).stream()
                .map(NoteResponse::from)
                .toList();
    }

    @Transactional
    public NoteResponse add(UUID objectId, UUID ownerId, NoteRequest req) {
        return add(objectId, ownerId, req, null);
    }

    /**
     * Add a note, optionally with a CLIENT-PROVIDED id (offline authoring) so a replayed add
     * returns the existing note instead of duplicating it. A note jotted down on site —
     * «ключі в консьєржа» — is exactly the kind of thing written where there is no signal.
     *
     * <p>An id that already belongs to a DIFFERENT object is rejected, never re-homed.
     */
    @Transactional
    public NoteResponse add(UUID objectId, UUID ownerId, NoteRequest req, UUID requestedId) {
        projectService.loadOwned(objectId, ownerId);
        if (requestedId != null) {
            var existing = noteRepository.findById(requestedId);
            if (existing.isPresent()) {
                if (!existing.get().getProjectId().equals(objectId)) {
                    throw new AccessDeniedException("Note belongs to a different object");
                }
                return NoteResponse.from(existing.get()); // idempotent replay
            }
        }
        ProjectNote note = noteRepository.save(ProjectNote.builder()
                .id(requestedId)
                .projectId(objectId)
                .title(trimToNull(req.title()))
                .phone(trimToNull(req.phone()))
                .body(req.body().trim())
                .build());
        return NoteResponse.from(note);
    }

    @Transactional
    public NoteResponse update(UUID objectId, UUID noteId, UUID ownerId, NoteRequest req) {
        projectService.loadOwned(objectId, ownerId);
        ProjectNote note = loadNote(objectId, noteId);
        note.setTitle(trimToNull(req.title()));
        note.setPhone(trimToNull(req.phone()));
        note.setBody(req.body().trim());
        return NoteResponse.from(note);
    }

    /** Idempotent: a replayed offline delete of an already-gone note is a no-op, not a 404 —
     *  a 404 on replay would surface to the master as "не збережено в хмару". */
    @Transactional
    public void delete(UUID objectId, UUID noteId, UUID ownerId) {
        projectService.loadOwned(objectId, ownerId);
        noteRepository.findByIdAndProjectId(noteId, objectId).ifPresent(noteRepository::delete);
    }

    private ProjectNote loadNote(UUID objectId, UUID noteId) {
        return noteRepository.findByIdAndProjectId(noteId, objectId)
                .orElseThrow(() -> new ResourceNotFoundException("Note not found: " + noteId));
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String trimmed = s.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
