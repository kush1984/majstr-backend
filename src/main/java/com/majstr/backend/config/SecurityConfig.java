package com.majstr.backend.config;

import com.majstr.backend.security.JwtAuthenticationFilter;
import com.majstr.backend.security.LoginRateLimitFilter;
import com.majstr.backend.security.PublicPortalRateLimitFilter;
import com.majstr.backend.security.RegisterRateLimitFilter;
import com.majstr.backend.security.RestAccessDeniedHandler;
import com.majstr.backend.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/auth/register",
            "/api/auth/login",
            "/api/auth/refresh",
            "/api/auth/logout",
            "/api/auth/verify-email",
            "/api/auth/forgot",
            "/api/auth/reset",
            "/api/push/vapid-public-key",
            // monobank calls this server-to-server; it verifies the X-Sign
            // signature itself, so it can't require a user JWT.
            "/api/billing/webhook",
            "/api/public/**",
            "/api/files/**",
            "/portal/**",
            "/robots.txt",
            // Browsers auto-probe /favicon.ico on every page. Without an explicit
            // permit it falls to anyRequest().authenticated() and Security answers
            // 403 before the DispatcherServlet — never the quiet 404 it should be.
            "/favicon.ico",
            "/logo.svg",
            "/v3/api-docs/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/actuator/health",
            "/actuator/health/**"
    };

    /** The admin HTML lives in /static/admin/; the JS inside performs its own
     *  Bearer-JWT calls against /api/admin/**, which require ROLE_ADMIN. */
    private static final String[] ADMIN_PATHS = {"/api/admin/**"};

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final LoginRateLimitFilter loginRateLimitFilter;
    private final RegisterRateLimitFilter registerRateLimitFilter;
    private final PublicPortalRateLimitFilter publicPortalRateLimitFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;
    private final CorsProperties corsProperties;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers("/admin/**").permitAll()
                        .requestMatchers(ADMIN_PATHS).hasRole("ADMIN")
                        .anyRequest().authenticated())
                // Without these, Spring falls back to Http403ForbiddenEntryPoint (the default
                // when no form/basic login is configured): an absent or expired token answered
                // 403, indistinguishable from a plan-limit or ownership refusal — and the PWA
                // only refreshes on 401. Anonymous → 401, authenticated-but-denied → 403.
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler))
                .addFilterBefore(loginRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(registerRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(publicPortalRateLimitFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(corsProperties.allowedOrigins());
        cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cfg.setAllowedHeaders(List.of("*"));
        cfg.setExposedHeaders(List.of("Authorization", "Retry-After"));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cfg);
        return source;
    }
}
