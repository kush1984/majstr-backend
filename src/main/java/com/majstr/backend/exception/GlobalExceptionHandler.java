package com.majstr.backend.exception;

import com.majstr.backend.dto.ErrorResponse;
import com.majstr.backend.feature.FeatureNotAvailableException;
import com.majstr.backend.feature.Limit;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.storage.UnsupportedMediaTypeException;
import io.sentry.Sentry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.stream.Collectors;

/**
 * Maps exceptions to the shared {@link ErrorResponse} shape. Every message
 * that can reach an end user is resolved through the {@code messages} bundle
 * (Ukrainian base, see {@code LocalizationConfig}); exception messages
 * themselves stay English for logs. Some exceptions carry a bundle <i>key</i>
 * as their message ({@code msg(ex.getMessage())} call sites) — the resolver
 * falls back to the raw text when the key is unknown, so a stray literal can
 * never break a response.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final MessageSource messages;

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("; "));
        return build(HttpStatus.BAD_REQUEST, message.isBlank() ? msg("error.validation-failed") : message, req);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraint(ConstraintViolationException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, msg("error.malformed-json"), req);
    }

    @ExceptionHandler({BadCredentialsException.class, UsernameNotFoundException.class})
    public ResponseEntity<ErrorResponse> handleBadCredentials(Exception ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, msg("error.bad-credentials"), req);
    }

    @ExceptionHandler(InvalidTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidToken(InvalidTokenException ex, HttpServletRequest req) {
        log.debug("Invalid token: {}", ex.getMessage());
        return build(HttpStatus.UNAUTHORIZED, msg("error.session-invalid"), req);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuth(AuthenticationException ex, HttpServletRequest req) {
        return build(HttpStatus.UNAUTHORIZED, msg("error.auth-failed"), req);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return build(HttpStatus.FORBIDDEN, msg("error.access-denied"), req);
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleDupEmail(EmailAlreadyExistsException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, msg("error.email-taken"), req);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest req) {
        log.debug("Not found: {}", ex.getMessage());
        return build(HttpStatus.NOT_FOUND, msg("error.not-found"), req);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        // Unknown path — almost always a bot/scanner probe (e.g. /admin/phpinfo.php).
        // A plain 404, NOT the catch-all 500, and deliberately NOT reported to
        // Sentry: it's internet background noise, not an application fault.
        log.debug("No resource for {} {}", req.getMethod(), req.getRequestURI());
        return build(HttpStatus.NOT_FOUND, msg("error.not-found"), req);
    }

    @ExceptionHandler(UnsupportedMediaTypeException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMedia(UnsupportedMediaTypeException ex, HttpServletRequest req) {
        // The throw sites pass a bundle key as the exception message.
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, msg(ex.getMessage()), req);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return build(HttpStatus.PAYLOAD_TOO_LARGE, msg("error.upload.too-large"), req);
    }

    @ExceptionHandler(FeatureNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleFeatureGate(FeatureNotAvailableException ex, HttpServletRequest req) {
        String message = msg("error.feature.unavailable",
                ex.getFeature().name(), ex.getCurrentPlan().name(), ex.getRequiredPlan().name());
        return build(HttpStatus.FORBIDDEN, message, req);
    }

    @ExceptionHandler(LimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleLimitExceeded(LimitExceededException ex, HttpServletRequest req) {
        boolean estimates = ex.getLimit() == Limit.MAX_ESTIMATES_PER_PROJECT;
        String messageKey = estimates ? "error.limit.estimates" : "error.limit.projects";
        String pluralPrefix = estimates ? "plural.estimates" : "plural.projects";
        String code = estimates ? "ESTIMATE_LIMIT_REACHED" : "PROJECT_LIMIT_REACHED";
        String message = msg(messageKey, ex.getMaxAllowed(), msg(pluralKey(pluralPrefix, ex.getMaxAllowed())));
        ErrorResponse body = ErrorResponse.coded(HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(), message, req.getRequestURI(), code);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(EmailNotVerifiedException.class)
    public ResponseEntity<ErrorResponse> handleEmailNotVerified(EmailNotVerifiedException ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.coded(HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(), msg(ex.getMessage()), req.getRequestURI(), "EMAIL_NOT_VERIFIED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(InvalidVerificationTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidVerificationToken(InvalidVerificationTokenException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, msg(ex.getMessage()), req);
    }

    @ExceptionHandler(ClientEmailMissingException.class)
    public ResponseEntity<ErrorResponse> handleClientEmailMissing(ClientEmailMissingException ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.coded(HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(), msg(ex.getMessage()), req.getRequestURI(), "CLIENT_EMAIL_MISSING");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(EstimateSignedException.class)
    public ResponseEntity<ErrorResponse> handleEstimateSigned(EstimateSignedException ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.coded(HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.getReasonPhrase(), msg("error.estimate.signed"), req.getRequestURI(), "ESTIMATE_SIGNED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(InvalidEstimateStatusException.class)
    public ResponseEntity<ErrorResponse> handleInvalidEstimateStatus(InvalidEstimateStatusException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, msg(ex.getMessage()), req);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(OptimisticLockingFailureException ex, HttpServletRequest req) {
        return build(HttpStatus.CONFLICT, msg("error.conflict.concurrent"), req);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ErrorResponse> handleTooManyRequests(TooManyRequestsException ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.rateLimited(msg(ex.getMessage()), req.getRequestURI(), ex.getRetryAfterSeconds());
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", String.valueOf(ex.getRetryAfterSeconds()))
                .body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleAny(Exception ex, HttpServletRequest req) {
        log.error("Unhandled exception at {} {}", req.getMethod(), req.getRequestURI(), ex);
        reportToSentry(ex, req);
        // Generic message only — the stack trace stays in the server log, never in the body.
        return build(HttpStatus.INTERNAL_SERVER_ERROR, msg("error.internal"), req);
    }

    /**
     * Reports a 5xx/unhandled exception to Sentry with the endpoint and an opaque
     * user id for triage. No PII (email, request body, headers, tokens) is attached.
     * A no-op when Sentry has no DSN (local dev), so it is always safe to call.
     */
    private void reportToSentry(Exception ex, HttpServletRequest req) {
        Sentry.withScope(scope -> {
            scope.setTag("endpoint", req.getMethod() + " " + req.getRequestURI());
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
                io.sentry.protocol.User user = new io.sentry.protocol.User();
                user.setId(principal.id().toString());
                scope.setUser(user);
            }
            Sentry.captureException(ex);
        });
    }

    /** Resolves a bundle key for the request locale; unknown keys pass through as-is. */
    private String msg(String code, Object... args) {
        return messages.getMessage(code, args.length == 0 ? null : args, code, LocaleContextHolder.getLocale());
    }

    /** Ukrainian-style plural bucket for {@code <prefix>.one/few/many}:
     *  1/21/31 → one, 2-4/22-24 → few, the rest → many. */
    private static String pluralKey(String prefix, int n) {
        int mod10 = Math.abs(n) % 10;
        int mod100 = Math.abs(n) % 100;
        if (mod10 == 1 && mod100 != 11) return prefix + ".one";
        if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) return prefix + ".few";
        return prefix + ".many";
    }

    private String formatFieldError(FieldError err) {
        return err.getField() + ": " + (err.getDefaultMessage() == null ? "invalid" : err.getDefaultMessage());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.of(status.value(), status.getReasonPhrase(), message, req.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
