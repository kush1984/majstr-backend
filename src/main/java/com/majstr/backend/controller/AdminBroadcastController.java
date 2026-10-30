package com.majstr.backend.controller;

import com.majstr.backend.dto.BroadcastPreviewRequest;
import com.majstr.backend.dto.BroadcastPreviewResponse;
import com.majstr.backend.dto.BroadcastRecipientView;
import com.majstr.backend.dto.BroadcastSegmentView;
import com.majstr.backend.dto.BroadcastSendRequest;
import com.majstr.backend.dto.BroadcastSummary;
import com.majstr.backend.entity.BroadcastSegment;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.broadcast.BroadcastService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/broadcasts")
@RequiredArgsConstructor
@Tag(name = "Admin broadcasts", description = "Re-engagement letters to funnel segments (ROLE_ADMIN only)")
@SecurityRequirement(name = "bearer-jwt")
public class AdminBroadcastController {

    private final BroadcastService broadcastService;

    @Operation(summary = "Every segment with its criteria and how many masters it holds right now")
    @GetMapping("/segments")
    public List<BroadcastSegmentView> segments() {
        return broadcastService.segments();
    }

    @Operation(summary = "Who a segment holds right now, with the date each last got this segment's letter")
    @GetMapping("/segments/{segment}/recipients")
    public List<BroadcastRecipientView> recipients(@PathVariable BroadcastSegment segment) {
        return broadcastService.recipients(segment);
    }

    @Operation(summary = "Render a draft letter as the segment's first master would get it")
    @PostMapping("/preview")
    public BroadcastPreviewResponse preview(@Valid @RequestBody BroadcastPreviewRequest req) {
        return broadcastService.preview(req);
    }

    @Operation(summary = "Send the draft to the admin's own address (synchronous)")
    @PostMapping("/test")
    public ResponseEntity<Map<String, String>> test(@Valid @RequestBody BroadcastPreviewRequest req,
                                                    @AuthenticationPrincipal UserPrincipal principal) {
        try {
            return ResponseEntity.ok(Map.of("sentTo", broadcastService.sendTest(req, principal.email())));
        } catch (RuntimeException e) {
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    @Operation(summary = "Send to the ticked masters who are still in the segment; letters go out in the background")
    @PostMapping
    public ResponseEntity<?> send(@Valid @RequestBody BroadcastSendRequest req,
                                  @AuthenticationPrincipal UserPrincipal principal) {
        return broadcastService.send(req, principal.email())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.unprocessableEntity()
                        .body(Map.of("message", "Нікого з позначених уже немає в цьому сегменті")));
    }

    @Operation(summary = "The last 50 sends with per-status letter counts")
    @GetMapping
    public List<BroadcastSummary> history() {
        return broadcastService.history();
    }
}
