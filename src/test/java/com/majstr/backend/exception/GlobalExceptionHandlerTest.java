package com.majstr.backend.exception;

import com.majstr.backend.entity.Plan;
import com.majstr.backend.feature.Limit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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
    void limitExceeded_buildsLocalizedMessageWithUkrainianPlural() throws Exception {
        mockMvc.perform(get("/limit").header("Accept-Language", "uk"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("2 об'єкти")))
                .andExpect(jsonPath("$.message", containsString("PRO")));
    }
}
