package com.majstr.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class AsyncExecutorsConfigTest {

    private final AsyncExecutorsConfig config = new AsyncExecutorsConfig();

    private static ThreadPoolTaskExecutor started(ThreadPoolTaskExecutor executor) {
        executor.afterPropertiesSet();
        return executor;
    }

    @Test
    void aFullPoolDropsTheTaskRatherThanThrowingIntoTheCaller() throws Exception {
        ThreadPoolTaskExecutor broadcast = started(config.broadcastExecutor());
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger ran = new AtomicInteger();
        try {
            // One thread busy and a full queue of twenty; the rest have nowhere to go.
            for (int i = 0; i < 30; i++) {
                assertThatCode(() -> broadcast.execute(() -> {
                    awaitQuietly(release);
                    ran.incrementAndGet();
                })).doesNotThrowAnyException();
            }
            release.countDown();
        } finally {
            release.countDown();
            broadcast.getThreadPoolExecutor().shutdown();
            broadcast.getThreadPoolExecutor().awaitTermination(5, TimeUnit.SECONDS);
        }

        assertThat(ran.get()).isEqualTo(21);
    }

    @Test
    void eachPoolNamesItsThreadsAfterItsWork() {
        assertThat(config.mailExecutor().getThreadNamePrefix()).isEqualTo("mail-");
        assertThat(config.pushExecutor().getThreadNamePrefix()).isEqualTo("push-");
        assertThat(config.broadcastExecutor().getThreadNamePrefix()).isEqualTo("broadcast-");
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
