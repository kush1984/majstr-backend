package com.majstr.backend.controller;

import com.majstr.backend.dto.MeasurementItemRequest;
import com.majstr.backend.dto.MeasurementRoomRequest;
import com.majstr.backend.dto.MeasurementsResponse;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.measurement.MeasurementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

import java.util.UUID;

/**
 * Object measurements (Заміри), PRO-gated ({@code Feature.MEASUREMENTS}) and owner-scoped in the
 * service — TEMPORARILY also granted to FREE, see the comment on {@code Plan.FREE} in
 * {@link com.majstr.backend.feature.PlanConfig}. A master measures the object once by room; the
 * metrics are later pulled into estimate line quantities (Stage 2). Every mutating call returns
 * the fresh tree (rooms → elements + per-room/object totals). Owner-only — none of this reaches
 * the client portal, PDF, or a share-token response.
 */
@RestController
@RequestMapping("/api/projects/{id}/measurements")
@RequiredArgsConstructor
@Tag(name = "Object measurements", description = "Per-object rooms + measured elements (PRO)")
@SecurityRequirement(name = "bearer-jwt")
public class MeasurementController {

    private final MeasurementService measurementService;

    @Operation(summary = "The object's measurement tree (rooms → elements) + totals")
    @GetMapping
    public MeasurementsResponse tree(@PathVariable UUID id,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        return measurementService.tree(id, principal.id());
    }

    @Operation(summary = "Add a room",
            description = "Offline-authored creates may send a client-generated UUID in the "
                    + "X-Entity-Uuid header — the create is then idempotent on replay.")
    @PostMapping("/rooms")
    public MeasurementsResponse addRoom(@PathVariable UUID id,
                                        @Valid @RequestBody MeasurementRoomRequest req,
                                        @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return measurementService.addRoom(id, principal.id(), req, entityId);
    }

    @Operation(summary = "Rename / reorder a room")
    @PatchMapping("/rooms/{roomId}")
    public MeasurementsResponse updateRoom(@PathVariable UUID id,
                                           @PathVariable UUID roomId,
                                           @Valid @RequestBody MeasurementRoomRequest req,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        return measurementService.updateRoom(id, roomId, principal.id(), req);
    }

    @Operation(summary = "Delete a room (its elements cascade)")
    @DeleteMapping("/rooms/{roomId}")
    public MeasurementsResponse deleteRoom(@PathVariable UUID id,
                                           @PathVariable UUID roomId,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        return measurementService.deleteRoom(id, roomId, principal.id());
    }

    @Operation(summary = "Add a measured element to a room",
            description = "Offline-authored creates may send a client-generated UUID in the "
                    + "X-Entity-Uuid header — the create is then idempotent on replay.")
    @PostMapping("/rooms/{roomId}/items")
    public MeasurementsResponse addItem(@PathVariable UUID id,
                                        @PathVariable UUID roomId,
                                        @Valid @RequestBody MeasurementItemRequest req,
                                        @RequestHeader(value = "X-Entity-Uuid", required = false) UUID entityId,
                                        @AuthenticationPrincipal UserPrincipal principal) {
        return measurementService.addItem(id, roomId, principal.id(), req, entityId);
    }

    @Operation(summary = "Edit a measured element")
    @PatchMapping("/rooms/{roomId}/items/{itemId}")
    public MeasurementsResponse updateItem(@PathVariable UUID id,
                                           @PathVariable UUID roomId,
                                           @PathVariable UUID itemId,
                                           @Valid @RequestBody MeasurementItemRequest req,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        return measurementService.updateItem(id, roomId, itemId, principal.id(), req);
    }

    @Operation(summary = "Delete a measured element")
    @DeleteMapping("/rooms/{roomId}/items/{itemId}")
    public MeasurementsResponse deleteItem(@PathVariable UUID id,
                                           @PathVariable UUID roomId,
                                           @PathVariable UUID itemId,
                                           @AuthenticationPrincipal UserPrincipal principal) {
        return measurementService.deleteItem(id, roomId, itemId, principal.id());
    }
}
