package com.majstr.backend.controller;

import com.majstr.backend.dto.ProjectRequest;
import com.majstr.backend.dto.ProjectResponse;
import com.majstr.backend.entity.ProjectStatus;
import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock private ProjectService projectService;
    @InjectMocks private ProjectController controller;

    private MockMvc mockMvc;
    private ObjectMapper objectMapper;

    private final UUID userId = UUID.randomUUID();
    private final UserPrincipal principal = new UserPrincipal(
            userId, "john@example.com", "hash", com.majstr.backend.entity.Role.USER);

    @BeforeEach
    void setUp() {
        objectMapper = JsonMapper.builder().build();
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void create_returns201WithProjectResponse() throws Exception {
        UUID projectId = UUID.randomUUID();
        ProjectResponse stubbed = new ProjectResponse(
                projectId, "Apartment 5", "Khreshchatyk 1", ProjectStatus.DRAFT,
                "Bathroom + kitchen", null, null, Instant.now(), Instant.now());
        given(projectService.create(any(ProjectRequest.class), eq(userId))).willReturn(stubbed);

        ProjectRequest req = new ProjectRequest("Apartment 5", "Khreshchatyk 1", "Bathroom + kitchen", null);

        mockMvc.perform(post("/api/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(projectId.toString())))
                .andExpect(jsonPath("$.name", is("Apartment 5")))
                .andExpect(jsonPath("$.status", is("DRAFT")));
    }

    @Test
    void list_returnsArrayOfProjects() throws Exception {
        UUID id = UUID.randomUUID();
        ProjectResponse stubbed = new ProjectResponse(
                id, "P1", "Addr", ProjectStatus.IN_PROGRESS, null, null, null, Instant.now(), Instant.now());
        given(projectService.listForOwner(userId, null)).willReturn(List.of(stubbed));

        mockMvc.perform(get("/api/projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", is(id.toString())))
                .andExpect(jsonPath("$[0].status", is("IN_PROGRESS")));
    }

    @Test
    void get_returns403WhenServiceThrowsAccessDenied() throws Exception {
        UUID id = UUID.randomUUID();
        willThrow(new AccessDeniedException("Project does not belong to the current user"))
                .given(projectService).get(id, userId);

        mockMvc.perform(get("/api/projects/{id}", id))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.message", is("Access denied")));
    }
}
