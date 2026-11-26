package com.majstr.backend.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;

/**
 * A pool per kind of background work (review B-123), so a broadcast or a stalled push service cannot
 * make a password-reset letter wait. Each queue is bounded: past it the task is dropped and reported,
 * rather than growing the heap or running a mail call on a request thread.
 *
 * <p>Named beans rather than an {@code AsyncConfigurer}, which would replace Boot's own (see
 * {@link BackgroundErrorConfig}). Declaring any {@code Executor} makes Boot back off from its default
 * one, so {@code spring.task.execution.mode: force} keeps it for a plain {@code @Async}.</p>
 */
@Slf4j
@Configuration
public class AsyncExecutorsConfig {

    private static final int DRAIN_SECONDS = 20;

    @Bean
    ThreadPoolTaskExecutor mailExecutor() {
        return executor("mail-", 4, 500, true);
    }

    @Bean
    ThreadPoolTaskExecutor pushExecutor() {
        return executor("push-", 4, 500, true);
    }

    /**
     * One broadcast at a time, and letters are paced anyway. Nothing is lost by not draining it on
     * shutdown: each letter commits on its own, and an unfinished broadcast resumes on startup.
     */
    @Bean
    ThreadPoolTaskExecutor broadcastExecutor() {
        return executor("broadcast-", 1, 20, false);
    }

    /** Usage rows (a PDF handed out): one insert each, never worth a second thread. */
    @Bean
    ThreadPoolTaskExecutor metricsExecutor() {
        return executor("metrics-", 1, 1000, true);
    }

    /**
     * Telegram parses: each one is a model call. Two at a time is plenty for the masters we have. The message
     * is stored before this pool sees it, so a dropped parse is swept to FAILED, never lost.
     */
    @Bean
    ThreadPoolTaskExecutor botExecutor() {
        return executor("bot-", 2, 200, true);
    }

    /**
     * The bot's notifications to masters (iteration-master-contact): one short HTTP call each, apart from the
     * parses and taps so a burst of signatures never waits behind a model call — and never makes them wait.
     */
    @Bean
    ThreadPoolTaskExecutor telegramNotifyExecutor() {
        return executor("tg-notify-", 2, 500, true);
    }

    /** Button taps in the bot: database work only, so they never wait behind a parse (review S-12). */
    @Bean
    ThreadPoolTaskExecutor botTapExecutor() {
        return executor("bot-tap-", 2, 200, true);
    }

    private static ThreadPoolTaskExecutor executor(String prefix, int threads, int queue, boolean drain) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(prefix);
        executor.setCorePoolSize(threads);
        executor.setMaxPoolSize(threads);
        executor.setQueueCapacity(queue);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setRejectedExecutionHandler(reportRejected(prefix));
        executor.setWaitForTasksToCompleteOnShutdown(drain);
        executor.setAwaitTerminationSeconds(drain ? DRAIN_SECONDS : 0);
        return executor;
    }

    static RejectedExecutionHandler reportRejected(String pool) {
        return (task, pooled) -> {
            log.error("Background pool {} is full; a task was dropped", pool);
            BackgroundErrors.capture("executor:" + pool, new RejectedExecutionException("Pool " + pool + " is full"));
        };
    }
}
