package com.majstr.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.util.Locale;

/**
 * Localization of end-user-visible messages (error responses, rate-limit
 * bodies, push notification texts). The bundle layout is deliberate:
 *
 * <ul>
 *   <li>{@code messages.properties} — <b>Ukrainian</b>, the product language.
 *       Being the base file makes it the fallback for every locale we don't
 *       translate (ru, de, ...), so an unknown {@code Accept-Language} can
 *       never surface English internals to a Ukrainian end user.</li>
 *   <li>{@code messages_en.properties} — English, served only when the client
 *       explicitly asks for it.</li>
 * </ul>
 *
 * Explicit beans (not Boot auto-config) — Spring Boot 4's module split makes
 * auto-config presence easy to break silently; this is load-bearing UX.
 */
@Configuration
public class LocalizationConfig {

    public static final Locale UKRAINIAN = Locale.of("uk");

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        // Never let the JVM's own locale pick the bundle — base file (uk) wins.
        source.setFallbackToSystemLocale(false);
        return source;
    }

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver resolver = new AcceptHeaderLocaleResolver();
        resolver.setDefaultLocale(UKRAINIAN);
        return resolver;
    }

    /**
     * Locale for servlet filters, which run before the DispatcherServlet sets
     * up {@code LocaleContextHolder}. Without an Accept-Language header,
     * {@code request.getLocale()} falls back to the JVM default — which may be
     * English on a server — so default to Ukrainian explicitly.
     */
    public static Locale requestLocale(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.ACCEPT_LANGUAGE) != null ? request.getLocale() : UKRAINIAN;
    }
}
