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
import org.springframework.dao.DataIntegrityViolationException;
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
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.io.IOException;
import java.util.Locale;
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
        // The raw message names the Java method and parameter that failed
        // ("listPhotos.projectId: must not be null") — internals, in English, shown to a
        // Ukrainian master. Keep the detail in the log and answer with the same localized
        // text body validation already uses. (Per-FIELD messages stay un-localized by
        // design — the PWA validates client-side with its own texts; this is the
        // parameter-level path, which has no such counterpart.)
        log.debug("Constraint violation on {}: {}", req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.BAD_REQUEST, msg("error.validation-failed"), req);
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

    /** Unique email constraint, checked in the DB. Used by both the fast pre-check
     *  ({@link EmailAlreadyExistsException}) and the fallback constraint catch below. */
    private static final String EMAIL_UNIQUE_CONSTRAINT = "users_email_unique";
    private static final String CATALOG_UNIQUE_INDEX = "ux_catalog_items_owner_name_type_unit";

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleDupEmail(EmailAlreadyExistsException ex, HttpServletRequest req) {
        return emailTaken(req);
    }

    /**
     * An admin tried to add a default-catalog position equivalent to one already there. Carries
     * the colliding name so the panel can show WHAT it clashes with — a bare "duplicate" would
     * leave the admin hunting through 800 positions for it.
     */
    @ExceptionHandler(DefaultCatalogDuplicateException.class)
    public ResponseEntity<ErrorResponse> handleDefaultCatalogDuplicate(
            DefaultCatalogDuplicateException ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.coded(HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.getReasonPhrase(),
                msg("error.catalog.default-duplicate", ex.getExistingName()),
                req.getRequestURI(), "DEFAULT_CATALOG_DUPLICATE");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(CustomTradeDuplicateException.class)
    public ResponseEntity<ErrorResponse> handleCustomTradeDuplicate(
            CustomTradeDuplicateException ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.coded(HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.getReasonPhrase(),
                msg("error.custom-trade.duplicate"),
                req.getRequestURI(), "CUSTOM_TRADE_DUPLICATE");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Level 2 (race safety): the pre-check in {@code AuthService.register} can't prevent
     * two concurrent registrations of the same email — the DB unique constraint does, and
     * surfaces at commit as a {@link DataIntegrityViolationException}. Map ONLY the email
     * constraint to the same clean 409 (no Sentry noise); any other integrity violation is
     * a real problem and keeps the 500 + Sentry path.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest req) {
        if (isEmailUniqueViolation(ex)) {
            return emailTaken(req);
        }
        if (isConstraintViolation(ex, CATALOG_UNIQUE_INDEX)) {
            // A catalog item with the same name+type+unit already exists — a friendly 409,
            // not a 500. The normal paths de-dup; this is a safety net (e.g. a rename clash).
            ErrorResponse body = ErrorResponse.coded(HttpStatus.CONFLICT.value(),
                    HttpStatus.CONFLICT.getReasonPhrase(), msg("error.catalog.duplicate"),
                    req.getRequestURI(), "CATALOG_ITEM_DUPLICATE");
            return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
        }
        return handleAny(ex, req);
    }

    private ResponseEntity<ErrorResponse> emailTaken(HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.coded(HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.getReasonPhrase(), msg("error.email-taken"),
                req.getRequestURI(), "EMAIL_ALREADY_REGISTERED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    private static boolean isEmailUniqueViolation(Throwable ex) {
        return isConstraintViolation(ex, EMAIL_UNIQUE_CONSTRAINT);
    }

    /** True only for the named constraint/index — never swallows other constraints.
     *  Checks the Hibernate constraint name and, defensively, the message text. */
    private static boolean isConstraintViolation(Throwable ex, String constraintName) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException cve
                    && constraintName.equalsIgnoreCase(cve.getConstraintName())) {
                return true;
            }
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains(constraintName)) {
                return true;
            }
        }
        return false;
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

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ErrorResponse> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest req) {
        // A PDF/file endpoint (produces = APPLICATION_PDF_VALUE and similar) hit by a client whose
        // Accept header doesn't include it or */* — a real browser always sends */*;q=0.8, so this
        // is almost always a link-preview bot (WhatsApp/Telegram/Viber unfurling a shared portal
        // link) sending a narrow "Accept: text/html". Not an application fault, same reasoning as
        // the quiet 404 below: a correct 406, no Sentry noise.
        log.debug("Not acceptable for {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        return build(HttpStatus.NOT_ACCEPTABLE, msg("error.not-acceptable"), req);
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

    @ExceptionHandler(CatalogImportException.class)
    public ResponseEntity<ErrorResponse> handleCatalogImport(CatalogImportException ex, HttpServletRequest req) {
        // The throw sites pass a bundle key as the exception message.
        return build(HttpStatus.BAD_REQUEST, msg(ex.getMessage()), req);
    }

    @ExceptionHandler(MeasurementException.class)
    public ResponseEntity<ErrorResponse> handleMeasurement(MeasurementException ex, HttpServletRequest req) {
        // The throw sites pass a bundle key as the exception message.
        return build(HttpStatus.BAD_REQUEST, msg(ex.getMessage()), req);
    }

    @ExceptionHandler(PaymentSplitException.class)
    public ResponseEntity<ErrorResponse> handlePaymentSplit(PaymentSplitException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, msg(ex.getMessage()), req);
    }

    @ExceptionHandler(PaymentValidationException.class)
    public ResponseEntity<ErrorResponse> handlePaymentValidation(PaymentValidationException ex, HttpServletRequest req) {
        return build(HttpStatus.BAD_REQUEST, msg(ex.getMessage(), ex.getArgs()), req);
    }

    @ExceptionHandler(AiExtractionException.class)
    public ResponseEntity<ErrorResponse> handleAiExtraction(AiExtractionException ex, HttpServletRequest req) {
        // Not configured (dev) or an upstream/parse failure — the AI couldn't do it
        // right now. 503 + a machine code so the PWA can offer "enter manually".
        log.warn("AI extraction unavailable at {} {}: {}", req.getMethod(), req.getRequestURI(), ex.getMessage());
        ErrorResponse body = ErrorResponse.coded(HttpStatus.SERVICE_UNAVAILABLE.value(),
                HttpStatus.SERVICE_UNAVAILABLE.getReasonPhrase(), msg(ex.getMessage()), req.getRequestURI(), "AI_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    @ExceptionHandler(FeatureNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleFeatureGate(FeatureNotAvailableException ex, HttpServletRequest req) {
        String message = msg("error.feature.unavailable",
                ex.getFeature().name(), ex.getCurrentPlan().name(), ex.getRequiredPlan().name());
        // Machine-readable code so a PRO-gated surface (e.g. the object-economy block)
        // can show its own upgrade teaser instead of a raw error.
        ErrorResponse body = ErrorResponse.coded(HttpStatus.FORBIDDEN.value(),
                HttpStatus.FORBIDDEN.getReasonPhrase(), message, req.getRequestURI(), "UPGRADE_REQUIRED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    @ExceptionHandler(LimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleLimitExceeded(LimitExceededException ex, HttpServletRequest req) {
        int max = ex.getMaxAllowed();
        String code;
        String message;
        switch (ex.getLimit()) {
            case MAX_ESTIMATES_PER_PROJECT -> {
                code = "ESTIMATE_LIMIT_REACHED";
                message = msg("error.limit.estimates", max, msg(pluralKey("plural.estimates", max)));
            }
            case MAX_PHOTOS_PER_OBJECT -> {
                // "фото" is invariant in Ukrainian plural — no plural helper needed.
                code = "PHOTO_LIMIT_REACHED";
                message = msg("error.limit.photos", max);
            }
            case MAX_RECEIPT_PHOTOS_PER_OBJECT -> {
                code = "RECEIPT_PHOTO_LIMIT_REACHED";
                message = msg("error.limit.receipt-photos", max);
            }
            default -> {
                code = "PROJECT_LIMIT_REACHED";
                message = msg("error.limit.projects", max, msg(pluralKey("plural.projects", max)));
            }
        }
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

    @ExceptionHandler(InvalidPasswordResetTokenException.class)
    public ResponseEntity<ErrorResponse> handleInvalidPasswordResetToken(InvalidPasswordResetTokenException ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.coded(HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(), msg(ex.getMessage()), req.getRequestURI(), "INVALID_OR_EXPIRED_TOKEN");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
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

    @ExceptionHandler(TrialNotAvailableException.class)
    public ResponseEntity<ErrorResponse> handleTrialNotAvailable(TrialNotAvailableException ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.coded(HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.getReasonPhrase(), msg("error.trial.unavailable"), req.getRequestURI(), "TRIAL_UNAVAILABLE");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(EmailDomainNotAllowedException.class)
    public ResponseEntity<ErrorResponse> handleEmailDomainBlocked(EmailDomainNotAllowedException ex, HttpServletRequest req) {
        ErrorResponse body = ErrorResponse.coded(HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(), msg(ex.getMessage()), req.getRequestURI(), "EMAIL_DOMAIN_BLOCKED");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
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
        if (isClientDisconnect(ex)) {
            // Nothing failed here — the peer went away while we were writing. Same reasoning as the
            // quiet 404 above: not an application fault, so no ERROR stack trace and no Sentry.
            // Returning null is the point, not a shortcut: there is no socket left to write to, and
            // an error body would only be a second failed write on the same dead connection.
            log.debug("Client disconnected during {} {}", req.getMethod(), req.getRequestURI());
            return null;
        }
        log.error("Unhandled exception at {} {}", req.getMethod(), req.getRequestURI(), ex);
        reportToSentry(ex, req);
        // Generic message only — the stack trace stays in the server log, never in the body.
        return build(HttpStatus.INTERNAL_SERVER_ERROR, msg("error.internal"), req);
    }

    /**
     * Did the CLIENT hang up mid-response?
     *
     * <p>It arrives deeply wrapped — {@code HttpMessageNotWritableException → JacksonIOException →
     * AsyncRequestNotUsableException → ClientAbortException → IOException: Broken pipe} — so the
     * whole cause chain is walked; matching only the outermost type would miss every real case.</p>
     *
     * <p>Matched by class NAME and message rather than by importing the types: {@code
     * ClientAbortException} is Tomcat's own, and a servlet container is not something an exception
     * handler should be compiled against. A phone that locks its screen, switches from wifi to
     * mobile data, or backgrounds the app mid-request produces exactly this, which is why it turns
     * up on the biggest payload we serve (the catalog) first.</p>
     */
    static boolean isClientDisconnect(Throwable ex) {
        // Bounded rather than "walk until null": a cause chain CAN be cyclic (two wrappers each
        // holding the other), and an unbounded walk would hang the request thread — a worse
        // outcome than the log noise this exists to remove. No real chain is 20 deep.
        Throwable t = ex;
        for (int depth = 0; t != null && depth < 20; depth++, t = t.getCause()) {
            String type = t.getClass().getSimpleName();
            if (type.equals("ClientAbortException") || type.equals("AsyncRequestNotUsableException")) {
                return true;
            }
            String message = t.getMessage();
            if (t instanceof IOException && message != null) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("broken pipe") || lower.contains("connection reset by peer")) {
                    return true;
                }
            }
        }
        return false;
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
