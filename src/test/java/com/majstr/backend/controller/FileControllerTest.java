package com.majstr.backend.controller;

import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.storage.StorageService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /api/files/**} is PUBLIC (no auth, no ownership check), so what it is allowed to
 * resolve is a security boundary, not a convenience. It may serve contractor logos — which
 * are public by design (client PDFs, the anonymous portal) — and nothing else.
 */
@ExtendWith(MockitoExtension.class)
class FileControllerTest {

    @Mock StorageService storage;
    @InjectMocks FileController controller;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
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
    void servesALogo() throws Exception {
        given(storage.open("logos/abc.png"))
                .willReturn(Optional.of(new ByteArrayInputStream("png".getBytes(StandardCharsets.UTF_8))));
        given(storage.contentType("logos/abc.png")).willReturn(Optional.of("image/png"));

        mockMvc.perform(get("/api/files/logos/abc.png"))
                .andExpect(status().isOk());
    }

    @Test
    void refusesAPrivatePhotoKey_andNeverTouchesStorage() throws Exception {
        // The exposure this closes: a receipt photo is financial personal data, and its key
        // could leak via a log line, a proxy log or a backup. Unguessability was the only
        // protection — now the prefix is.
        mockMvc.perform(get("/api/files/photos/2f1c0c6e-dead-beef-cafe-000000000001.jpg"))
                .andExpect(status().isNotFound());

        // A 404 must be decided from the key alone — never by asking storage, which would
        // let response timing hint at whether a private object exists.
        verify(storage, never()).open(anyString());
        verify(storage, never()).contentType(any());
    }

    @Test
    void refusesTraversalAndEmptyKeys() throws Exception {
        mockMvc.perform(get("/api/files/logos/../photos/secret.jpg"))
                .andExpect(status().isNotFound());
        verify(storage, never()).open(anyString());
    }
}
