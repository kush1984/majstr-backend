package com.majstr.backend.config;

import com.majstr.backend.security.UserPrincipal;
import com.majstr.backend.service.AbuseLimits;
import com.majstr.backend.service.HeavyWork;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The import endpoints read a whole upload into memory and hold it through a model call, so the gate
 * sits around the handler: nothing inside can read the bytes before it has a permit. Multipart parsing
 * comes first, but Tomcat spools that to disk.
 */
@Configuration
@RequiredArgsConstructor
public class HeavyImportConfig implements WebMvcConfigurer {

    static final String[] IMPORT_PATHS = {
            "/api/estimates/import/parse",
            "/api/estimates/*/receipt-items/parse",
            "/api/projects/*/measurements/sketch/parse",
            "/api/projects/*/measurements/project/parse",
            "/api/projects/*/measurements/electrical/plan/parse",
    };

    /** The title pass reads text rather than a file, so it holds no gate, but it still spends a model call. */
    static final String TRIAGE_PATH = "/api/projects/*/measurements/project/triage";

    private static final String ENTERED = HeavyImportConfig.class.getName() + ".entered";

    private final HeavyWork heavyWork;
    private final AbuseLimits abuseLimits;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // First, so a flood is turned away before it can hold a permit (B-125).
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                Authentication auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth != null && auth.getPrincipal() instanceof UserPrincipal principal) {
                    abuseLimits.requireAiImport(principal.id());
                }
                return true;
            }
        }).addPathPatterns(IMPORT_PATHS).addPathPatterns(TRIAGE_PATH);
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                heavyWork.enterImport();
                request.setAttribute(ENTERED, true);
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                                        Exception ex) {
                if (request.getAttribute(ENTERED) != null) {
                    heavyWork.leaveImport();
                }
            }
        }).addPathPatterns(IMPORT_PATHS);
    }
}
