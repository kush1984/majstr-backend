package com.majstr.backend.service.ai;

import com.majstr.backend.config.BackgroundErrors;
import com.majstr.backend.exception.AiExtractionException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A ceiling on model calls per flow per hour, across every account (review B-125). Per-account limits
 * bound one master; nothing else bounded the bill. Past it a flow fails closed with AI_UNAVAILABLE,
 * which the PWA already answers with «enter it by hand», and Sentry hears about it once an hour.
 *
 * <p>In memory like every limiter here, so a deploy resets it. It is a safety net, not an account.</p>
 */
@Slf4j
@Component
public class AiFlowBudget {

    private static final long ALERT_EVERY_MILLIS = Duration.ofHours(1).toMillis();

    private final Map<AiFlow, Bucket> buckets = new EnumMap<>(AiFlow.class);
    private final Map<AiFlow, AtomicLong> lastAlert = new EnumMap<>(AiFlow.class);

    public AiFlowBudget(@Value("${app.ai.hourly-calls-per-flow:200}") int callsPerHour) {
        for (AiFlow flow : AiFlow.values()) {
            buckets.put(flow, Bucket.builder()
                    .addLimit(Bandwidth.builder()
                            .capacity(callsPerHour)
                            .refillGreedy(callsPerHour, Duration.ofHours(1))
                            .build())
                    .build());
            lastAlert.put(flow, new AtomicLong());
        }
    }

    void spend(AiFlow flow) {
        if (buckets.get(flow).tryConsume(1)) {
            return;
        }
        alertOnce(flow);
        throw new AiExtractionException("error.ai.unavailable");
    }

    private void alertOnce(AiFlow flow) {
        long now = System.currentTimeMillis();
        AtomicLong last = lastAlert.get(flow);
        long previous = last.get();
        if (now - previous >= ALERT_EVERY_MILLIS && last.compareAndSet(previous, now)) {
            log.error("AI flow {} reached its hourly budget; calls are refused until it refills", flow.key());
            BackgroundErrors.capture("ai-budget:" + flow.key(),
                    new IllegalStateException("AI flow " + flow.key() + " reached its hourly budget"));
        }
    }
}
