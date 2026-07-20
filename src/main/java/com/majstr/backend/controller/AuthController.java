package com.majstr.backend.controller;

import com.majstr.backend.dto.AuthResponse;
import com.majstr.backend.dto.ForgotPasswordRequest;
import com.majstr.backend.dto.LoginRequest;
import com.majstr.backend.dto.RefreshTokenRequest;
import com.majstr.backend.dto.RegisterRequest;
import com.majstr.backend.dto.ResetPasswordRequest;
import com.majstr.backend.dto.UserResponse;
import com.majstr.backend.dto.VerifyEmailRequest;
import com.majstr.backend.exception.TooManyRequestsException;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.AuthService;
import com.majstr.backend.service.EmailVerificationService;
import com.majstr.backend.service.ForgotPasswordRateLimiter;
import com.majstr.backend.service.PasswordResetService;
import com.majstr.backend.service.VerificationEmailRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Registration, login and token management for contractors")
public class AuthController {

    private final AuthService authService;
    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;
    private final VerificationEmailRateLimiter verificationEmailRateLimiter;
    private final PasswordResetService passwordResetService;
    private final ForgotPasswordRateLimiter forgotPasswordRateLimiter;

    @Operation(summary = "Register a new contractor")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @Operation(summary = "Log in with email and password")
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @Operation(summary = "Exchange a refresh token for a new access + refresh pair")
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        return ResponseEntity.ok(authService.refresh(request.refreshToken()));
    }

    @Operation(summary = "Log out — revoke the given refresh token server-side")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Verify email using the token from the verification link (public)")
    @PostMapping("/verify-email")
    public ResponseEntity<Void> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
        emailVerificationService.verify(request.token());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Request a password-reset link (public, rate-limited, anti-enumeration)")
    @PostMapping("/forgot")
    public ResponseEntity<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request,
                                               HttpServletRequest http) {
        // Key by IP+email so a spammer can't hammer reset mail at one address, and one IP
        // can't churn many addresses. The limit itself never leaks account existence.
        String key = clientIp(http) + "|" + request.email().trim().toLowerCase(Locale.ROOT);
        ForgotPasswordRateLimiter.ConsumeResult probe = forgotPasswordRateLimiter.tryConsume(key);
        if (!probe.allowed()) {
            throw new TooManyRequestsException("error.rate.forgot", probe.retryAfterSeconds());
        }
        passwordResetService.requestReset(request.email());
        // Always 200, whether or not the account exists — the PWA shows a neutral screen.
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Set a new password using the token from the reset link (public)")
    @PostMapping("/reset")
    public ResponseEntity<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.reset(request.token(), request.newPassword());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Resend the verification email to the current user (rate-limited)",
            security = @SecurityRequirement(name = "bearer-jwt"))
    @PostMapping("/resend-verification")
    public ResponseEntity<Void> resendVerification(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new UsernameNotFoundException("Not authenticated");
        }
        VerificationEmailRateLimiter.ConsumeResult probe = verificationEmailRateLimiter.tryConsume(principal.id());
        if (!probe.allowed()) {
            throw new TooManyRequestsException("error.rate.verification-resend", probe.retryAfterSeconds());
        }
        emailVerificationService.resendFor(principal.id());
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Get the currently authenticated contractor", security = @SecurityRequirement(name = "bearer-jwt"))
    @GetMapping("/me")
    @Transactional(readOnly = true) // load the lazy trades set within a session (open-in-view is off)
    public ResponseEntity<UserResponse> me(@AuthenticationPrincipal UserPrincipal principal) {
        if (principal == null) {
            throw new UsernameNotFoundException("Not authenticated");
        }
        return userRepository.findById(principal.id())
                .map(UserResponse::from)
                .map(ResponseEntity::ok)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));
    }

    /** First X-Forwarded-For entry if present, else the socket address — mirrors the rate-limit
     *  filters. In prod the forward-headers strategy strips XFF so getRemoteAddr is the real IP. */
    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        return request.getRemoteAddr();
    }
}
