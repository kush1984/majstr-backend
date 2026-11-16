package com.majstr.backend.controller;

import com.majstr.backend.dto.InboxCreateRequest;
import com.majstr.backend.dto.InboxEntryView;
import com.majstr.backend.dto.InboxItemUpdateRequest;
import com.majstr.backend.entity.InboxEntry;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.inbox.InboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * «Вхідні» — what the bot or a quick add could not file by itself, plus the quick add itself.
 * Every apply goes through the record's own service (docs/iteration-majster-bot.md).
 */
@RestController
@RequestMapping("/api/inbox")
@RequiredArgsConstructor
@Tag(name = "Inbox", description = "Quick notes from the bot and the app, waiting to be filed")
@SecurityRequirement(name = "bearer-jwt")
public class InboxController {

    private final InboxService inbox;

    public record CountResponse(long open) {}

    @Operation(summary = "Messages with something still waiting, newest first")
    @GetMapping
    public List<InboxEntryView> list(@AuthenticationPrincipal UserPrincipal principal) {
        return inbox.list(principal.id());
    }

    @Operation(summary = "How many messages wait (the badge)")
    @GetMapping("/count")
    public CountResponse count(@AuthenticationPrincipal UserPrincipal principal) {
        return new CountResponse(inbox.openCount(principal.id()));
    }

    @Operation(summary = "Quick add: parse a typed or dictated note and file what is certain")
    @PostMapping
    public InboxService.Outcome create(@Valid @RequestBody InboxCreateRequest req,
                                       @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        InboxService.Outcome received = inbox.receive(principal.id(), InboxEntry.Source.PWA, req.text(),
                null, null, entityId);
        // A replay (the outbox, after a timeout) answers what is there: the first request is still parsing
        // or has finished. Parsing it again would call the model twice and race the first run (review M-2).
        if (!received.fresh()) {
            return received;
        }
        return inbox.process(principal.id(), received.entry().id());
    }

    @Operation(summary = "Parse a message again (after a failed parse)")
    @PostMapping("/{entryId}/retry")
    public InboxService.Outcome retry(@PathVariable UUID entryId,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return inbox.retry(principal.id(), entryId);
    }

    @Operation(summary = "Undo what a message filed")
    @PostMapping("/{entryId}/undo")
    public InboxService.Outcome undo(@PathVariable UUID entryId,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        return inbox.undo(principal.id(), entryId);
    }

    @Operation(summary = "Create a «Додаткові роботи» estimate for lines that found every estimate signed")
    @PostMapping("/{entryId}/estimate")
    public InboxService.Outcome createDraft(@PathVariable UUID entryId,
                                            @AuthenticationPrincipal UserPrincipal principal) {
        return inbox.createDraft(principal.id(), entryId);
    }

    @Operation(summary = "Throw away what still waits in a message")
    @DeleteMapping("/{entryId}")
    public ResponseEntity<Void> discard(@PathVariable UUID entryId,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        inbox.discardEntry(principal.id(), entryId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Correct a waiting item")
    @PatchMapping("/items/{itemId}")
    public InboxEntryView updateItem(@PathVariable UUID itemId,
                                     @Valid @RequestBody InboxItemUpdateRequest req,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        return inbox.updateItem(principal.id(), itemId, req);
    }

    @Operation(summary = "File a waiting item — for money this is the confirmation")
    @PostMapping("/items/{itemId}/apply")
    public InboxService.Outcome applyItem(@PathVariable UUID itemId,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        return inbox.applyItem(principal.id(), itemId);
    }

    @Operation(summary = "Throw away one waiting item")
    @DeleteMapping("/items/{itemId}")
    public InboxEntryView discardItem(@PathVariable UUID itemId,
                                      @AuthenticationPrincipal UserPrincipal principal) {
        return inbox.discardItem(principal.id(), itemId);
    }
}
