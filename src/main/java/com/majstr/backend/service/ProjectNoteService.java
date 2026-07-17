package com.majstr.backend.service;

import com.majstr.backend.dto.NoteRequest;
import com.majstr.backend.dto.NoteResponse;
import com.majstr.backend.entity.ProjectNote;
import com.majstr.backend.exception.ResourceNotFoundException;
import com.majstr.backend.repository.ProjectNoteRepository;
import lombok.RequiredArgsConstructor;
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
        projectService.loadOwned(objectId, ownerId);
        ProjectNote note = noteRepository.save(ProjectNote.builder()
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

    @Transactional
    public void delete(UUID objectId, UUID noteId, UUID ownerId) {
        projectService.loadOwned(objectId, ownerId);
        noteRepository.delete(loadNote(objectId, noteId));
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
