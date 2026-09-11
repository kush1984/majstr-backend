package com.majstr.backend.controller;

import com.majstr.backend.dto.MaterialPrefsRequest;
import com.majstr.backend.dto.MaterialPrefsResponse;
import com.majstr.backend.entity.Role;
import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.exception.MaterialPrefValidationException;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.MaterialPrefService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class MaterialPrefControllerTest {

    @Mock private MaterialPrefService prefService;
    @InjectMocks private MaterialPrefController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final UserPrincipal principal = new UserPrincipal(
            userId, "master@example.com", "hash", Role.USER);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(testMessageSource()))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static MessageSource testMessageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Test
    void get_returnsTheMastersHabits() throws Exception {
        given(prefService.get(userId)).willReturn(
                new MaterialPrefsResponse(Map.of("GKL_SHEET", "1200x2500", "PAINT_COATS", "2")));

        mockMvc.perform(get("/api/me/material-prefs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefs.GKL_SHEET").value("1200x2500"));
    }

    @Test
    void put_savesAndAnswersTheWholeSet() throws Exception {
        given(prefService.save(eq(userId), any()))
                .willReturn(new MaterialPrefsResponse(Map.of("PAINT_COATS", "3")));

        mockMvc.perform(put("/api/me/material-prefs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new MaterialPrefsRequest(Map.of("PAINT_COATS", "3")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.prefs.PAINT_COATS").value("3"));
    }

    @Test
    void put_rejectsAKeyTheCalculatorDoesNotKnow() throws Exception {
        willThrow(new MaterialPrefValidationException("error.material-pref.invalid-key"))
                .given(prefService).save(eq(userId), any());

        mockMvc.perform(put("/api/me/material-prefs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"prefs\":{\"ROOM_PERIMETER\":\"12\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MATERIAL_PREF_INVALID"));
    }

    @Test
    void put_rejectsAMissingBody() throws Exception {
        mockMvc.perform(put("/api/me/material-prefs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
