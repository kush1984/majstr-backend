package com.majstr.backend.controller;

import com.majstr.backend.dto.ApplyTemplatesRequest;
import com.majstr.backend.dto.EstimateResponse;
import com.majstr.backend.dto.EstimateTemplateDetail;
import com.majstr.backend.dto.EstimateTemplateSummary;
import com.majstr.backend.dto.SaveAsTemplateRequest;
import com.majstr.backend.dto.TemplateItemRequest;
import com.majstr.backend.dto.TemplateItemsOrderRequest;
import com.majstr.backend.entity.EstimateStatus;
import com.majstr.backend.entity.ItemType;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.EstimateTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class EstimateTemplateControllerTest {

    @Mock private EstimateTemplateService templateService;
    @Mock private UserRepository userRepository;
    @InjectMocks private EstimateTemplateController controller;

    private MockMvc mockMvc;
    private final ObjectMapper json = JsonMapper.builder().build();

    private final UUID userId = UUID.randomUUID();
    private final UserPrincipal principal = new UserPrincipal(userId, "john@example.com", "hash", Role.USER);

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler(messageSource()))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private static MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Test
    void list_loadsUserWithTradesAndReturnsSummaries() throws Exception {
        User user = User.builder()
                .id(userId)
                .trades(new LinkedHashSet<>(Set.of(Trade.TILING)))
                .build();
        given(userRepository.findWithTradesById(userId)).willReturn(Optional.of(user));
        given(templateService.listForUser(user)).willReturn(List.of(
                new EstimateTemplateSummary(UUID.randomUUID(), "Санвузол повний", null, Trade.TILING, null, null, true, 8)));

        mockMvc.perform(get("/api/estimate-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name", is("Санвузол повний")))
                .andExpect(jsonPath("$[0].itemCount", is(8)))
                .andExpect(jsonPath("$[0].isDefault", is(true)));

        verify(userRepository).findWithTradesById(userId);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void saveAsTemplate_returns201WithSummary() throws Exception {
        UUID estimateId = UUID.randomUUID();
        given(templateService.saveFromEstimate(estimateId, "Санвузол Іванова", null, null, null, userId))
                .willReturn(new EstimateTemplateSummary(UUID.randomUUID(), "Санвузол Іванова", null, null, null, null, false, 5));

        mockMvc.perform(post("/api/estimates/{id}/save-as-template", estimateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new SaveAsTemplateRequest("Санвузол Іванова", null, null, null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.itemCount", is(5)))
                .andExpect(jsonPath("$.isDefault", is(false)));
    }

    @Test
    void saveAsTemplate_blankNameIsRejected() throws Exception {
        mockMvc.perform(post("/api/estimates/{id}/save-as-template", UUID.randomUUID())
                        .header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new SaveAsTemplateRequest("  ", null, null, null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createFromTemplate_returns201WithEstimate() throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID templateId = UUID.randomUUID();
        UUID estimateId = UUID.randomUUID();
        given(templateService.applyToProject(eq(projectId), eq(templateId), any(), eq(userId)))
                .willReturn(new EstimateResponse(estimateId, projectId, "Кухня",
                        EstimateStatus.DRAFT, null, null, null, null, null, List.of(),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, List.of()));

        mockMvc.perform(post("/api/projects/{p}/estimates/from-template/{t}", projectId, templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(estimateId.toString())))
                .andExpect(jsonPath("$.projectId", is(projectId.toString())));
    }

    @Test
    void createFromTemplates_passesEveryPickedTemplateInOrder_withThePositionsTickedInEachOne()
            throws Exception {
        UUID projectId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID pickedLine = UUID.randomUUID();
        UUID estimateId = UUID.randomUUID();
        ArgumentCaptor<ApplyTemplatesRequest> captor = ArgumentCaptor.forClass(ApplyTemplatesRequest.class);
        given(templateService.applyToProject(eq(projectId), any(ApplyTemplatesRequest.class), eq(userId)))
                .willReturn(new EstimateResponse(estimateId, projectId, "Санвузол",
                        EstimateStatus.DRAFT, null, null, null, null, null, List.of(),
                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, BigDecimal.ZERO, List.of()));

        mockMvc.perform(post("/api/projects/{p}/estimates/from-templates", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"templates\":[{\"templateId\":\"%s\",\"itemIds\":[\"%s\"]},"
                                + "{\"templateId\":\"%s\"}],\"estimate\":{\"name\":\"Санвузол\"}}")
                                .formatted(first, pickedLine, second)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(estimateId.toString())));

        verify(templateService).applyToProject(eq(projectId), captor.capture(), eq(userId));
        ApplyTemplatesRequest sent = captor.getValue();
        assertThat(sent.templateIds()).containsExactly(first, second);
        // A bundle taken whole names no position at all — the subset map has no key for it, and
        // «no key» is what the service reads as «everything». An empty list here would be a
        // bundle contributing nothing, which is not a thing the picker can express.
        assertThat(sent.pickedItemIds()).containsOnlyKeys(first);
        assertThat(sent.pickedItemIds().get(first)).containsExactly(pickedLine);
        assertThat(sent.estimateOrEmpty().name()).isEqualTo("Санвузол");
    }

    @Test
    void createFromTemplates_withoutASingleTemplate_is400() throws Exception {
        mockMvc.perform(post("/api/projects/{p}/estimates/from-templates", UUID.randomUUID())
                        .header("Accept-Language", "en")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"templates\":[],\"estimate\":{}}"))
                .andExpect(status().isBadRequest());
        verify(templateService, never()).applyToProject(any(), any(ApplyTemplatesRequest.class), any());
    }

    @Test
    void reorderItems_passesTheWholeOrderThrough() throws Exception {
        UUID templateId = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        given(templateService.reorderItems(eq(templateId), any(), eq(userId)))
                .willReturn(detail(templateId, "Малярні роботи"));

        mockMvc.perform(put("/api/estimate-templates/{id}/items/order", templateId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new TemplateItemsOrderRequest(List.of(first, second)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(templateId.toString())));

        ArgumentCaptor<TemplateItemsOrderRequest> captor =
                ArgumentCaptor.forClass(TemplateItemsOrderRequest.class);
        verify(templateService).reorderItems(eq(templateId), captor.capture(), eq(userId));
        assertThat(captor.getValue().itemIds()).containsExactly(first, second);
    }

    @Test
    void reorderItems_rejectsAnEmptyOrder() throws Exception {
        mockMvc.perform(put("/api/estimate-templates/{id}/items/order", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemIds\":[]}"))
                .andExpect(status().isBadRequest());
        verify(templateService, never()).reorderItems(any(), any(), any());
    }

    @Test
    void updateItem_passesNameTypeAndUnitThrough() throws Exception {
        UUID templateId = UUID.randomUUID();
        UUID itemId = UUID.randomUUID();
        given(templateService.updateItem(eq(templateId), eq(itemId), any(), eq(userId)))
                .willReturn(detail(templateId, "Малярні роботи"));

        mockMvc.perform(patch("/api/estimate-templates/{id}/items/{itemId}", templateId, itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new TemplateItemRequest("Ґрунтівка", ItemType.MATERIAL, Unit.PIECE))))
                .andExpect(status().isOk());

        ArgumentCaptor<TemplateItemRequest> captor = ArgumentCaptor.forClass(TemplateItemRequest.class);
        verify(templateService).updateItem(eq(templateId), eq(itemId), captor.capture(), eq(userId));
        assertThat(captor.getValue().name()).isEqualTo("Ґрунтівка");
        assertThat(captor.getValue().type()).isEqualTo(ItemType.MATERIAL);
        assertThat(captor.getValue().unit()).isEqualTo(Unit.PIECE);
    }

    @Test
    void restoreDefaults_unhidesThenRelistsForTheUser() throws Exception {
        User user = User.builder().id(userId).trades(new LinkedHashSet<>(Set.of(Trade.PAINTER))).build();
        given(userRepository.findWithTradesById(userId)).willReturn(Optional.of(user));
        given(templateService.listForUser(user)).willReturn(List.of(new EstimateTemplateSummary(
                UUID.randomUUID(), "Малярні роботи", null, Trade.PAINTER, null, null, true, 30)));

        mockMvc.perform(post("/api/estimate-templates/restore-defaults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name", is("Малярні роботи")));

        verify(templateService).restoreDefaults(userId);
    }

    private static EstimateTemplateDetail detail(UUID id, String name) {
        return new EstimateTemplateDetail(id, name, null, Trade.PAINTER, null, null, false, List.of());
    }
}
