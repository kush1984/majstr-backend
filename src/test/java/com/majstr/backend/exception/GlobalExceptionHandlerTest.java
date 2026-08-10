package com.majstr.backend.exception;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.feature.Limit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpMethod;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the advice end-to-end with the real message bundle: the 5xx
 * fallback must stay generic (no class names, no stack frames, no internal
 * detail) and every user-facing message must come back localized — Ukrainian
 * by default, English on an explicit Accept-Language. Locale is pinned per
 * request via the Accept-Language header because the standalone MockMvc
 * default resolver falls back to the JVM locale otherwise.
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class BoomController {
        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("secret internal detail: db password leaked");
        }

        @GetMapping("/signed")
        String signed() {
            throw new EstimateSignedException();
        }

        @GetMapping("/stale")
        String stale() {
            throw new OptimisticLockingFailureException("row was updated by another transaction");
        }

        @GetMapping("/limit")
        String limit() {
            throw new LimitExceededException(Limit.MAX_PROJECTS, 2, Plan.FREE);
        }

        @GetMapping("/estimate-limit")
        String estimateLimit() {
            throw new LimitExceededException(Limit.MAX_ESTIMATES_PER_PROJECT, 3, Plan.FREE);
        }

        @GetMapping("/scan")
        String scan() throws NoResourceFoundException {
            // What Spring throws for an unknown path like a scanner's /admin/phpinfo.php.
            throw new NoResourceFoundException(HttpMethod.GET, "admin/phpinfo.php", "");
        }

        @GetMapping(value = "/pdf-like", produces = "application/pdf")
        String pdfLike() throws HttpMediaTypeNotAcceptableException {
            // What Spring throws when a request's Accept header excludes both the endpoint's
            // produced type AND */* — a real browser always sends */*;q=0.8, so this is what a
            // link-preview bot (e.g. WhatsApp/Telegram unfurling a shared portal PDF link) produces
            // with its narrow "Accept: text/html".
            throw new HttpMediaTypeNotAcceptableException(java.util.List.of(org.springframework.http.MediaType.APPLICATION_PDF));
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BoomController())
                .setControllerAdvice(new GlobalExceptionHandler(testMessageSource()))
                .build();
    }

    private static MessageSource testMessageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Test
    void unhandledException_returnsGenericFiveHundredWithoutLeakingDetails() throws Exception {
        String body = mockMvc.perform(get("/boom").header("Accept-Language", "uk"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status", is(500)))
                .andExpect(jsonPath("$.message", is("Внутрішня помилка сервера. Спробуйте пізніше.")))
                .andExpect(jsonPath("$.path", is("/boom")))
                .andReturn()
                .getResponse()
                .getContentAsString();

        // None of the internal details may appear in the response body.
        assertThat(body)
                .doesNotContain("secret internal detail")
                .doesNotContain("db password")
                .doesNotContain("IllegalStateException")
                .doesNotContain("at com.majstr");
    }

    @Test
    void messagesFollowAcceptLanguage_englishWhenAsked() throws Exception {
        mockMvc.perform(get("/boom").header("Accept-Language", "en"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message", is("Internal server error. Please try again later.")));
    }

    @Test
    void unknownLocaleFallsBackToUkrainianBaseBundle() throws Exception {
        mockMvc.perform(get("/boom").header("Accept-Language", "de"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message", is("Внутрішня помилка сервера. Спробуйте пізніше.")));
    }

    @Test
    void estimateSigned_returns409WithMachineReadableCodeAndLocalizedMessage() throws Exception {
        mockMvc.perform(get("/signed").header("Accept-Language", "uk"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.code", is("ESTIMATE_SIGNED")))
                .andExpect(jsonPath("$.message", containsString("Кошторис підписано клієнтом")));
    }

    @Test
    void optimisticLockConflict_returns409WithGenericMessage() throws Exception {
        mockMvc.perform(get("/stale").header("Accept-Language", "uk"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.message", containsString("Дані щойно змінилися")));
    }

    @Test
    void unknownPath_returns404NotGeneric500_soScannerProbesDontHitSentry() throws Exception {
        // /admin/phpinfo.php and friends must be a quiet 404, not the 500
        // fallback (which would also report the bot probe to Sentry).
        mockMvc.perform(get("/scan").header("Accept-Language", "uk"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.message", is("Запис не знайдено")));
    }

    /**
     * Sentry saw this as a 500 (JAVA-SPRING-BOOT-J, the public portal PDF endpoint) — but a
     * client whose Accept header can't be satisfied is not a fault of ours either.
     */
    @Test
    void notAcceptableAccept_returns406NotGeneric500_soLinkPreviewBotsDontHitSentry() throws Exception {
        // No body assertion: with Accept: text/html, Spring can't write our JSON error body
        // either (the same content-negotiation rule the fix itself relies on), so the body is
        // legitimately empty here — a bot doesn't read it anyway. The status is the whole point:
        // 406, not the generic 500 that used to reach Sentry.
        mockMvc.perform(get("/pdf-like").header("Accept-Language", "uk").header("Accept", "text/html"))
                .andExpect(status().isNotAcceptable());
    }

    /**
     * A client that hangs up mid-response is not a fault of ours, and Sentry saw one as a 500
     * (JAVA-SPRING-BOOT-G, «GET /api/catalog», Broken pipe). It arrives wrapped several layers
     * deep, which is the whole difficulty: matching the outermost type finds nothing.
     */
    @Test
    void aClientHangingUpMidResponseIsRecognisedThroughTheWholeCauseChain() {
        // The real chain off the production event, outermost first.
        Exception real = new HttpMessageNotWritableException("Could not write JSON",
                new IllegalStateException("ServletOutputStream failed to write",
                        new java.io.IOException("Broken pipe")));

        assertThat(GlobalExceptionHandler.isClientDisconnect(real)).isTrue();
        // The other wording the same event produces, on a different container / OS.
        assertThat(GlobalExceptionHandler.isClientDisconnect(
                new java.io.IOException("Connection reset by peer"))).isTrue();
    }

    @Test
    void aGenuineFailureIsStillAFiveHundred_soTheQuietPathCannotSwallowRealBugs() {
        // The guard above must not widen into "any IOException is the client's fault". A disk or
        // an upstream that fails mid-write is ours, and it has to keep reaching Sentry.
        assertThat(GlobalExceptionHandler.isClientDisconnect(
                new java.io.IOException("No space left on device"))).isFalse();
        assertThat(GlobalExceptionHandler.isClientDisconnect(
                new IllegalStateException("something else entirely"))).isFalse();
    }

    @Test
    void aCyclicCauseChainTerminatesInsteadOfHangingTheRequestThread() {
        // Throwable refuses a cause pointing at ITSELF, but nothing stops two wrappers holding
        // each other — and an unbounded walk over that never returns. A hung request thread would
        // be a far worse bug than the log noise this whole guard exists to remove.
        Exception a = new IllegalStateException("a");
        Exception b = new IllegalStateException("b", a);
        a.initCause(b);

        assertThat(GlobalExceptionHandler.isClientDisconnect(a)).isFalse();
    }

    @Test
    void limitExceeded_buildsLocalizedMessageWithUkrainianPlural() throws Exception {
        mockMvc.perform(get("/limit").header("Accept-Language", "uk"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("PROJECT_LIMIT_REACHED")))
                .andExpect(jsonPath("$.message", containsString("2 об'єкти")))
                .andExpect(jsonPath("$.message", containsString("PRO")));
    }

    @Test
    void estimateLimitExceeded_returns403WithCodeAndUkrainianPlural() throws Exception {
        mockMvc.perform(get("/estimate-limit").header("Accept-Language", "uk"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.code", is("ESTIMATE_LIMIT_REACHED")))
                .andExpect(jsonPath("$.message", containsString("3 кошториси")))
                .andExpect(jsonPath("$.message", containsString("PRO")));
    }
}
