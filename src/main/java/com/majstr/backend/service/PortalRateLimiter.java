package com.majstr.backend.service;

import com.majstr.backend.config.RateLimitProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Rate limits for the public portal endpoints ({@code /api/public/**}).
 *
 * <p>It used to be ONE bucket per IP, 30 a minute, refilled all at once. A meetup hall on one
 * Wi-Fi, or a mobile carrier's CGNAT, is one IP for dozens of people opening the SAME link — a
 * view, its photos, a PDF each — so most of the room got «Не вдалося завантажити кошторис».
 * Three buckets now, all refilled smoothly (greedy), so a burst drains them and the next request
 * is never a full minute away:</p>
 * <ul>
 *   <li><b>per IP + link, reads</b> — what one person on one link does; generous;</li>
 *   <li><b>per IP + link, writes</b> (sign, question) — stricter, a real client signs once;</li>
 *   <li><b>per IP, everything</b> — a high ceiling, the defence the old bucket was for: walking
 *       through many tokens from one address. The tokens themselves are 32 random bytes.</li>
 * </ul>
 */
@Component
public class PortalRateLimiter {

    private final BucketRegistry<String> perIp;
    private final BucketRegistry<String> reads;
    private final BucketRegistry<String> writes;

    public PortalRateLimiter(RateLimitProperties props) {
        RateLimitProperties.Portal portal = props.portal();
        Duration window = Duration.ofMinutes(portal.windowMinutes());
        this.perIp = registry(portal.maxAttempts(), window);
        this.reads = registry(portal.perLinkMaxAttempts(), window);
        this.writes = registry(portal.writeMaxAttempts(), window);
    }

    private static BucketRegistry<String> registry(int perWindow, Duration window) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(perWindow)
                .refillGreedy(perWindow, window)
                .build();
        return new BucketRegistry<>(bandwidth, window);
    }

    /**
     * @param token the link token from the path, or null for a route that names none
     * @param write a POST — a signature or a question
     */
    public ConsumeResult tryConsume(String ip, String token, boolean write) {
        String link = ip + "|" + (token == null ? "-" : token);
        ConsumeResult own = consume((write ? writes : reads).get(link));
        if (!own.allowed()) {
            return own;
        }
        return consume(perIp.get(ip));
    }

    private static ConsumeResult consume(Bucket bucket) {
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            return new ConsumeResult(true, 0L);
        }
        long retryAfter = Math.max(1L, (long) Math.ceil(probe.getNanosToWaitForRefill() / 1e9));
        return new ConsumeResult(false, retryAfter);
    }

    public record ConsumeResult(boolean allowed, long retryAfterSeconds) {}
}
