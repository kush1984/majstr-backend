package com.majstr.backend.controller;

import com.majstr.backend.dto.NoteRequest;
import com.majstr.backend.dto.NoteResponse;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ProjectNoteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Object notes (Нотатки) — free text + optional title/phone kept against an object. Owner-scoped
 * in the service; <b>no plan gate</b> (every plan). PRIVATE — none of this reaches the client
 * portal, PDF, or a share-token response.
 */
@RestController
@RequestMapping("/api/projects/{id}/notes")
@RequiredArgsConstructor
@Tag(name = "Object notes", description = "Per-object free-text notes with an optional phone")
@SecurityRequirement(name = "bearer-jwt")
public class ProjectNoteController {

    private final ProjectNoteService noteService;

    @Operation(summary = "List the object's notes (newest first)")
    @GetMapping
    public List<NoteResponse> list(@PathVariable UUID id,
                                   @AuthenticationPrincipal UserPrincipal principal) {
        return noteService.list(id, principal.id());
    }

    @Operation(summary = "Add a note to the object")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public NoteResponse add(@PathVariable UUID id,
                            @Valid @RequestBody NoteRequest req,
                            @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
                            @AuthenticationPrincipal UserPrincipal principal) {
        return noteService.add(id, principal.id(), req, entityId);
    }

    @Operation(summary = "Edit a note")
    @PatchMapping("/{noteId}")
    public NoteResponse update(@PathVariable UUID id,
                               @PathVariable UUID noteId,
                               @Valid @RequestBody NoteRequest req,
                               @AuthenticationPrincipal UserPrincipal principal) {
        return noteService.update(id, noteId, principal.id(), req);
    }

    @Operation(summary = "Delete a note")
    @DeleteMapping("/{noteId}")
    public ResponseEntity<Void> delete(@PathVariable UUID id,
                                       @PathVariable UUID noteId,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        noteService.delete(id, noteId, principal.id());
        return ResponseEntity.noContent().build();
    }
}
