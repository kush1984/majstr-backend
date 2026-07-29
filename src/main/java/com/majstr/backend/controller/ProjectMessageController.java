package com.majstr.backend.controller;

import com.majstr.backend.dto.MessageView;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.MessageFileService;
import com.majstr.backend.service.MessageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@RestController
// Two paths, one controller. `/messages` is the name the concept has now; `/questions` stays because
// the installed PWA calls it, and an app on somebody's phone updates only when they tap «Оновити» —
// dropping the old path would break every master who has not.
@RequestMapping({"/api/projects/{projectId}/messages", "/api/projects/{projectId}/questions"})
@RequiredArgsConstructor
@Tag(name = "Object messages", description = "Messages left on an object — from a client on the portal, or through the master's message link")
@SecurityRequirement(name = "bearer-jwt")
public class ProjectMessageController {

    private final MessageService messageService;

    @Operation(summary = "List an object's messages (newest first)")
    @GetMapping
    public List<MessageView> list(@PathVariable UUID projectId,
                                   @AuthenticationPrincipal UserPrincipal principal) {
        return messageService.listForProject(projectId, principal.id());
    }

    @Operation(summary = "Mark a message as read")
    @PatchMapping("/{questionId}/read")
    public MessageView markRead(@PathVariable UUID projectId,
                                 @PathVariable UUID questionId,
                                 @AuthenticationPrincipal UserPrincipal principal) {
        return messageService.markRead(projectId, questionId, principal.id());
    }

    @Operation(summary = "Delete a message from the object")
    @DeleteMapping("/{messageId}")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId,
                                       @PathVariable UUID messageId,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        messageService.delete(projectId, messageId, principal.id());
        return ResponseEntity.noContent().build();
    }

    /**
     * One attachment, for the master who owns the object.
     *
     * <p>Always {@code attachment}, never {@code inline}. These bytes came from a stranger, and a
     * browser asked to render them inside the app's own origin is the difference between a file being
     * downloaded and a file running against the master's session. The sniffed type is sent so the
     * download is useful, and {@code nosniff} stops the browser second-guessing it.</p>
     *
     * <p>Ownership is checked on the object, then the file is looked up by id AND message — so a file
     * belonging to somebody else is indistinguishable from one that does not exist.</p>
     */
    @Operation(summary = "Download an attachment of a message")
    @GetMapping("/{messageId}/files/{fileId}")
    public ResponseEntity<byte[]> file(@PathVariable UUID projectId,
                                       @PathVariable UUID messageId,
                                       @PathVariable UUID fileId,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        MessageFileService.MessageFileContent content =
                messageService.openFile(projectId, messageId, fileId, principal.id());
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(content.downloadName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, content.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=0, no-store")
                .body(content.bytes());
    }
}
