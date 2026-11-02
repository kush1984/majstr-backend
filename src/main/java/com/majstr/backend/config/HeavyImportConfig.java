package com.majstr.backend.config;

import com.majstr.backend.service.HeavyWork;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
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

    private static final String ENTERED = HeavyImportConfig.class.getName() + ".entered";

    private final HeavyWork heavyWork;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
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
