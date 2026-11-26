package com.majstr.backend.telegram;

import com.majstr.backend.dto.TelegramNotificationsRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import com.majstr.backend.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The PWA's side of the Telegram link: status, a fresh deep link, and unlinking. */
@RestController
@RequestMapping("/api/telegram")
@RequiredArgsConstructor
@Tag(name = "Telegram bot", description = "Link the master's Telegram chat to the bot")
@SecurityRequirement(name = "bearer-jwt")
public class TelegramController {

    private final TelegramLinkService links;

    /** {@code url} is null when the bot is not configured on this server. */
    public record LinkResponse(String url) {}

    @Operation(summary = "Is the bot available here, and is this master's chat linked?")
    @GetMapping
    public TelegramLinkService.Status status(@AuthenticationPrincipal UserPrincipal principal) {
        return links.status(principal.id());
    }

    @Operation(summary = "A one-time t.me deep link that binds the chat that opens it")
    @PostMapping("/link")
    public LinkResponse link(@AuthenticationPrincipal UserPrincipal principal) {
        return new LinkResponse(links.createLink(principal.id()));
    }

    @Operation(summary = "Turn the app's notifications in the linked chat on or off (the caller's own)")
    @PutMapping("/notifications")
    public TelegramLinkService.Status notifications(@Valid @RequestBody TelegramNotificationsRequest req,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        return links.setNotify(principal.id(), req.enabled());
    }

    @Operation(summary = "Unlink the chat")
    @DeleteMapping
    public ResponseEntity<Void> unlink(@AuthenticationPrincipal UserPrincipal principal) {
        links.unlink(principal.id());
        return ResponseEntity.noContent().build();
    }
}
