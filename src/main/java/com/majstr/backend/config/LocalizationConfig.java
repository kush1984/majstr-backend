package com.majstr.backend.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.support.ResourceBundleMessageSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.AcceptHeaderLocaleResolver;

import java.time.ZoneId;
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

    /**
     * The product's wall-clock zone. Every date a MASTER or CLIENT reads — the PDF's «Дата:»,
     * the estimate email — must be rendered in it, never in UTC or the server's default.
     *
     * <p>The server stores {@code Instant}s and runs in UTC, so formatting one without a zone
     * silently dates anything created between midnight and 02:00–03:00 Kyiv to the PREVIOUS
     * day. On a signed estimate that is a wrong date on a document the client keeps.
     */
    public static final ZoneId ZONE = ZoneId.of("Europe/Kyiv");

    @Bean
    public MessageSource messageSource() {
        ResourceBundleMessageSource source = new ResourceBundleMessageSource();
        source.setBasename("messages");
        source.setDefaultEncoding("UTF-8");
        // Never let the JVM's own locale pick the bundle — base file (uk) wins.
        source.setFallbackToSystemLocale(false);
        return source;
    }

    /** The client pages' API: every answer there is Ukrainian, whatever the phone's own language. */
    public static final String PUBLIC_API_PREFIX = "/api/public/";

    @Bean
    public LocaleResolver localeResolver() {
        AcceptHeaderLocaleResolver byHeader = new AcceptHeaderLocaleResolver();
        byHeader.setDefaultLocale(UKRAINIAN);
        // The portal is a Ukrainian page for a Ukrainian client: an English-language phone turned its
        // error texts English while every word around them stayed Ukrainian.
        return new LocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                return isPublic(request) ? UKRAINIAN : byHeader.resolveLocale(request);
            }

            @Override
            public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
                byHeader.setLocale(request, response, locale);
            }
        };
    }

    public static boolean isPublic(HttpServletRequest request) {
        return request.getRequestURI() != null && request.getRequestURI().startsWith(PUBLIC_API_PREFIX);
    }

    /**
     * Locale for servlet filters, which run before the DispatcherServlet sets
     * up {@code LocaleContextHolder}. Without an Accept-Language header,
     * {@code request.getLocale()} falls back to the JVM default — which may be
     * English on a server — so default to Ukrainian explicitly.
     */
    public static Locale requestLocale(HttpServletRequest request) {
        if (isPublic(request)) {
            return UKRAINIAN;
        }
        return request.getHeader(HttpHeaders.ACCEPT_LANGUAGE) != null ? request.getLocale() : UKRAINIAN;
    }
}
