package com.majstr.backend.controller;

import com.majstr.backend.dto.ShoppingListItemRequest;
import com.majstr.backend.dto.ShoppingListItemResponse;
import com.majstr.backend.dto.ShoppingListItemUpdateRequest;
import com.majstr.backend.dto.ShoppingListResponse;
import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.ShoppingListItemSource;
import com.majstr.backend.entity.Unit;
import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.ShoppingListService;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class ShoppingListControllerTest {

    @Mock private ShoppingListService shoppingListService;
    @InjectMocks private ShoppingListController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final UUID userId = UUID.randomUUID();
    private final UUID projectId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
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

    private ShoppingListItemResponse item() {
        return new ShoppingListItemResponse(itemId, null, "Шпаклівка фінішна", Unit.KG,
                new BigDecimal("25.000"), false, null, false, null, false,
                ShoppingListItemSource.MANUAL, null, null, 1);
    }

    @Test
    void get_returnsTheListOfTheObject() throws Exception {
        given(shoppingListService.get(projectId, userId)).willReturn(new ShoppingListResponse(
                UUID.randomUUID(), projectId, "Квартира", null, 1, 0, false, List.of(item())));

        mockMvc.perform(get("/api/projects/{id}/shopping-list", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalCount").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Шпаклівка фінішна"))
                // No price anywhere on the wire: the shop's tag is the price, not our forecast.
                .andExpect(jsonPath("$.items[0].price").doesNotExist());
    }

    @Test
    void pdf_isServedAsAnAttachmentTheMasterCanSendOn() throws Exception {
        given(shoppingListService.renderPdf(projectId, userId))
                .willReturn("%PDF-1.4".getBytes(java.nio.charset.StandardCharsets.US_ASCII));

        mockMvc.perform(get("/api/projects/{id}/shopping-list/pdf", projectId))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        containsString("shopping-list-" + projectId + ".pdf")));
    }

    @Test
    void add_passesTheClientSuppliedIdSoAReplayIsIdempotent() throws Exception {
        UUID entityId = UUID.randomUUID();
        given(shoppingListService.addManual(eq(projectId), eq(userId), any(), eq(entityId)))
                .willReturn(item());

        mockMvc.perform(post("/api/projects/{id}/shopping-list/items", projectId)
                        .header("X-Entity-Uuid", entityId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShoppingListItemRequest(
                                null, "Шпаклівка фінішна", Unit.KG, new BigDecimal("25"), null))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(itemId.toString()));
    }

    @Test
    void add_worksWithoutTheHeaderToo() throws Exception {
        given(shoppingListService.addManual(eq(projectId), eq(userId), any(), isNull()))
                .willReturn(item());

        mockMvc.perform(post("/api/projects/{id}/shopping-list/items", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShoppingListItemRequest(
                                null, "Шпаклівка фінішна", Unit.KG, new BigDecimal("25"), null))))
                .andExpect(status().isCreated());
    }

    @Test
    void add_rejectsABlankName() throws Exception {
        mockMvc.perform(post("/api/projects/{id}/shopping-list/items", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ShoppingListItemRequest(
                                null, "  ", Unit.KG, new BigDecimal("25"), null))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void patch_forwardsThePartialEdit() throws Exception {
        given(shoppingListService.update(eq(projectId), eq(userId), eq(itemId), any()))
                .willReturn(item());

        mockMvc.perform(patch("/api/projects/{id}/shopping-list/items/{itemId}", projectId, itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bought\":true}"))
                .andExpect(status().isOk());

        ArgumentCaptor<ShoppingListItemUpdateRequest> captor =
                ArgumentCaptor.forClass(ShoppingListItemUpdateRequest.class);
        verify(shoppingListService).update(eq(projectId), eq(userId), eq(itemId), captor.capture());
        // Absent fields stay null — that is how "leave it alone" is expressed.
        assertThat(captor.getValue().bought()).isTrue();
        assertThat(captor.getValue().quantity()).isNull();
        assertThat(captor.getValue().note()).isNull();
    }

    @Test
    void delete_answersNoContent() throws Exception {
        mockMvc.perform(delete("/api/projects/{id}/shopping-list/items/{itemId}", projectId, itemId))
                .andExpect(status().isNoContent());

        verify(shoppingListService).delete(projectId, userId, itemId);
    }

    @Test
    void clearBought_returnsTheListItLeftBehind() throws Exception {
        given(shoppingListService.clearBought(projectId, userId)).willReturn(new ShoppingListResponse(
                UUID.randomUUID(), projectId, "Квартира", null, 0, 0, false, List.of()));

        mockMvc.perform(post("/api/projects/{id}/shopping-list/clear-bought", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty());
    }

    @Test
    void summary_isScopedToThePrincipal() throws Exception {
        given(shoppingListService.summaries(userId)).willReturn(List.of());

        mockMvc.perform(get("/api/shopping-lists/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        verify(shoppingListService).summaries(userId);
    }

    @Test
    void archivedAtIsOnTheWireSoThePwaCanHideTheCardWithoutASecondCall() throws Exception {
        given(shoppingListService.get(projectId, userId)).willReturn(new ShoppingListResponse(
                UUID.randomUUID(), projectId, "Квартира", Instant.parse("2026-09-07T10:00:00Z"),
                1, 1, false, List.of(item())));

        mockMvc.perform(get("/api/projects/{id}/shopping-list", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").exists());
    }
}
