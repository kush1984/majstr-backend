package com.majstr.backend.config;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Refuses to start without a known profile. The production hardening lives in application-prod.yml,
 * so a lost or misspelled SPRING_PROFILES_ACTIVE would otherwise boot with none of it: localhost CORS,
 * Swagger open, and a client IP read from a header the caller controls.
 *
 * <p>A {@link BeanFactoryPostProcessor} so it runs before any bean exists, which means before Flyway
 * can touch the database.</p>
 */
@Component
class ActiveProfileGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    private static final Profiles KNOWN = Profiles.of("prod | dev | test");

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (!environment.acceptsProfiles(KNOWN)) {
            throw new IllegalStateException(
                    "Set SPRING_PROFILES_ACTIVE to prod or dev; refusing to start with active profiles "
                            + Arrays.toString(environment.getActiveProfiles()) + ".");
        }
    }
}
