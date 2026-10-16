package com.majstr.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.task.SimpleAsyncTaskSchedulerCustomizer;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ErrorHandler;

/**
 * What escapes a {@code @Scheduled} job reaches Sentry, not only the log (see
 * {@link BackgroundErrors}). Spring's default handler logs it and stops there.
 *
 * <p>There is deliberately no {@code AsyncConfigurer} here: declaring one replaces Boot's own and
 * changes which executor runs {@code @Async}. Every {@code @Async} method in this codebase is
 * fail-soft and reports from its own {@code catch} instead.</p>
 */
@Slf4j
@Configuration
public class BackgroundErrorConfig {

    static final ErrorHandler SCHEDULED = error -> {
        log.error("Scheduled task failed", error);
        BackgroundErrors.capture("scheduled", error);
    };

    @Bean
    ThreadPoolTaskSchedulerCustomizer reportScheduledFailures() {
        return scheduler -> scheduler.setErrorHandler(SCHEDULED);
    }

    @Bean
    SimpleAsyncTaskSchedulerCustomizer reportScheduledFailuresOnVirtualThreads() {
        return scheduler -> scheduler.setErrorHandler(SCHEDULED);
    }
}
