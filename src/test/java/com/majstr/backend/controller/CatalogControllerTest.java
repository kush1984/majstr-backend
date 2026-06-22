package com.majstr.backend.controller;

import com.majstr.backend.entity.Role;
import com.majstr.backend.entity.Trade;
import com.majstr.backend.entity.User;
import com.majstr.backend.exception.GlobalExceptionHandler;
import com.majstr.backend.repository.UserRepository;
import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.CatalogService;
import com.majstr.backend.service.CatalogTemplateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class CatalogControllerTest {

    @Mock private CatalogService catalogService;
    @Mock private CatalogTemplateService catalogTemplateService;
    @Mock private UserRepository userRepository;
    @InjectMocks private CatalogController controller;

    private MockMvc mockMvc;

    private final UUID userId = UUID.randomUUID();
    private final UserPrincipal principal = new UserPrincipal(
            userId, "john@example.com", "hash", Role.USER);

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

    /**
     * Regression for the production LazyInitializationException on
     * reset-from-template: the user must be loaded with the lazy {@code trades}
     * collection eager-fetched (open-in-view is off, so resetForUser reads
     * trades on a detached entity). Asserts the controller uses
     * {@code findWithTradesById} — never the plain {@code findById} that left
     * trades uninitialized and 500'd.
     */
    @Test
    void resetFromTemplate_loadsUserWithTradesAndReturnsCount() throws Exception {
        User user = User.builder()
                .id(userId)
                .email("john@example.com")
                .trades(new LinkedHashSet<>(Set.of(Trade.ELECTRICAL, Trade.PLUMBING)))
                .build();
        given(userRepository.findWithTradesById(userId)).willReturn(Optional.of(user));
        given(catalogTemplateService.resetForUser(user)).willReturn(7);

        mockMvc.perform(post("/api/catalog/reset-from-template"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemsAdded", is(7)));

        verify(userRepository).findWithTradesById(userId);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void resetFromTemplate_returns404WhenUserMissing() throws Exception {
        given(userRepository.findWithTradesById(userId)).willReturn(Optional.empty());

        mockMvc.perform(post("/api/catalog/reset-from-template").header("Accept-Language", "en"))
                .andExpect(status().isNotFound());
    }

    @Test
    void templateUpdates_loadsUserWithTradesAndReturnsAvailableCount() throws Exception {
        User user = User.builder()
                .id(userId)
                .trades(new LinkedHashSet<>(Set.of(Trade.PAINTER)))
                .build();
        given(userRepository.findWithTradesById(userId)).willReturn(Optional.of(user));
        given(catalogTemplateService.countNewFromCatalog(user)).willReturn(4);

        mockMvc.perform(get("/api/catalog/template-updates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available", is(4)));

        verify(userRepository).findWithTradesById(userId);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void addNewFromTemplate_loadsUserWithTradesAndReturnsItemsAdded() throws Exception {
        User user = User.builder()
                .id(userId)
                .trades(new LinkedHashSet<>(Set.of(Trade.PAINTER)))
                .build();
        given(userRepository.findWithTradesById(userId)).willReturn(Optional.of(user));
        given(catalogTemplateService.addNewFromCatalog(user)).willReturn(3);

        mockMvc.perform(post("/api/catalog/add-new-from-template"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemsAdded", is(3)));

        verify(userRepository).findWithTradesById(userId);
        verify(userRepository, never()).findById(any());
    }
}
