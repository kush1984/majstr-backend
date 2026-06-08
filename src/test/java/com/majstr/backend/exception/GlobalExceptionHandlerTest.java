package com.majstr.backend.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the 5xx fallback path: an unhandled exception must produce a
 * structured 500 with a generic message and must never leak the exception
 * class, its message, or a stack trace into the response body. (The Sentry
 * capture inside the handler is a safe no-op here — no DSN is configured.)
 */
class GlobalExceptionHandlerTest {

    private MockMvc mockMvc;

    @RestController
    static class BoomController {
        @GetMapping("/boom")
        String boom() {
            throw new IllegalStateException("secret internal detail: db password leaked");
        }
    }

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new BoomController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void unhandledException_returnsGenericFiveHundredWithoutLeakingDetails() throws Exception {
        String body = mockMvc.perform(get("/boom"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status", is(500)))
                .andExpect(jsonPath("$.message", is("Internal server error")))
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
}
