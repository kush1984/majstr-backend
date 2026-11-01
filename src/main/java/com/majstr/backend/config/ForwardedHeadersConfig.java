package com.majstr.backend.config;

import com.majstr.backend.security.TrustedForwardedHeaderFilter;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Replaces the {@code ForwardedHeaderFilter} Boot registers for
 * {@code server.forward-headers-strategy=framework}. Boot's condition matches the registration's exact
 * generic, so the return type has to say {@code ForwardedHeaderFilter}: declared as the subclass, Boot
 * registers its own beside this one and which runs first is an accident of ordering.
 */
@Configuration
@ConditionalOnProperty(name = "server.forward-headers-strategy", havingValue = "framework")
public class ForwardedHeadersConfig {

    @Bean
    public FilterRegistrationBean<ForwardedHeaderFilter> trustedForwardedHeaderFilter() {
        FilterRegistrationBean<ForwardedHeaderFilter> registration =
                new FilterRegistrationBean<>(new TrustedForwardedHeaderFilter());
        registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.ERROR);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
