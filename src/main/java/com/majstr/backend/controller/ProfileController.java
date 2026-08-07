package com.majstr.backend.controller;

import com.majstr.backend.dto.CustomTradeRequest;
import com.majstr.backend.dto.ProfileUpdateRequest;
import com.majstr.backend.dto.UserResponse;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.repository.UserTradeRepository;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.UUID;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
@Tag(name = "Profile", description = "Contractor profile (logo upload)")
@SecurityRequirement(name = "bearer-jwt")
public class ProfileController {

    private final ProfileService profileService;
    private final UserRepository userRepository;
    private final UserTradeRepository userTradeRepository;

    @Operation(summary = "Update the contractor's profile (email editable only while unverified)")
    @PutMapping
    public UserResponse update(@Valid @RequestBody ProfileUpdateRequest req,
                               @AuthenticationPrincipal UserPrincipal principal) {
        return profileService.updateProfile(principal.id(), req);
    }

    @Operation(summary = "Upload contractor logo (PNG or JPEG, max 2MB)")
    @PostMapping(value = "/logo", consumes = "multipart/form-data")
    public UserResponse uploadLogo(@RequestParam("file") MultipartFile file,
                                   @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        profileService.uploadLogo(principal.id(), file);
        // Eager-fetch trades: UserResponse.from reads them and this reload runs
        // outside a session (open-in-view off), so a plain findById would throw.
        var user = userRepository.findWithTradesById(principal.id()).orElseThrow();
        return UserResponse.from(user, userTradeRepository.findByUserIdOrderBySortOrderAscIdAsc(user.getId()));
    }

    @Operation(summary = "Delete contractor logo")
    @DeleteMapping("/logo")
    public ResponseEntity<Void> deleteLogo(@AuthenticationPrincipal UserPrincipal principal) {
        profileService.deleteLogo(principal.id());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Record privacy-policy consent (one-time, for users predating the checkbox)")
    @PostMapping("/consent")
    public UserResponse consent(@AuthenticationPrincipal UserPrincipal principal) {
        return profileService.recordPrivacyConsent(principal.id());
    }

    @Operation(summary = "Acknowledge responsibility for entering client data (shown once)")
    @PostMapping("/acknowledge-client-data")
    public UserResponse acknowledgeClientData(@AuthenticationPrincipal UserPrincipal principal) {
        return profileService.acknowledgeClientData(principal.id());
    }

    @Operation(summary = "Toggle subscription auto-renewal (disable is instant; enable needs a saved card)")
    @PatchMapping("/auto-renew")
    public UserResponse setAutoRenew(@RequestParam boolean enabled,
                                     @AuthenticationPrincipal UserPrincipal principal) {
        return profileService.setAutoRenew(principal.id(), enabled);
    }

    @Operation(summary = "Add a master-invented trade — no reference catalog exists for it, "
            + "the master fills it himself")
    @PostMapping("/custom-trades")
    public UserResponse addCustomTrade(@Valid @RequestBody CustomTradeRequest req,
                                       @AuthenticationPrincipal UserPrincipal principal) {
        return profileService.addCustomTrade(principal.id(), req.name());
    }

    @Operation(summary = "Rename a custom trade — a live FK, so every position/template filed "
            + "under it picks up the new name immediately")
    @PatchMapping("/custom-trades/{id}")
    public UserResponse renameCustomTrade(@PathVariable UUID id,
                                          @Valid @RequestBody CustomTradeRequest req,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        return profileService.renameCustomTrade(principal.id(), id, req.name());
    }

    @Operation(summary = "Delete a custom trade — positions/templates filed under it fall back "
            + "to \"Інше\", nothing is lost")
    @DeleteMapping("/custom-trades/{id}")
    public UserResponse deleteCustomTrade(@PathVariable UUID id,
                                          @AuthenticationPrincipal UserPrincipal principal) {
        return profileService.deleteCustomTrade(principal.id(), id);
    }
}
