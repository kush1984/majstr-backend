package com.majstr.backend.controller;

import com.majstr.backend.dto.UserResponse;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/profile")
@RequiredArgsConstructor
@Tag(name = "Profile", description = "Contractor profile (logo upload)")
@SecurityRequirement(name = "bearer-jwt")
public class ProfileController {

    private final ProfileService profileService;
    private final UserRepository userRepository;

    @Operation(summary = "Upload contractor logo (PNG or JPEG, max 2MB)")
    @PostMapping(value = "/logo", consumes = "multipart/form-data")
    public UserResponse uploadLogo(@RequestParam("file") MultipartFile file,
                                   @AuthenticationPrincipal UserPrincipal principal) throws IOException {
        profileService.uploadLogo(principal.id(), file);
        return userRepository.findById(principal.id())
                .map(UserResponse::from)
                .orElseThrow();
    }

    @Operation(summary = "Delete contractor logo")
    @DeleteMapping("/logo")
    public ResponseEntity<Void> deleteLogo(@AuthenticationPrincipal UserPrincipal principal) {
        profileService.deleteLogo(principal.id());
        return ResponseEntity.noContent().build();
    }
}
