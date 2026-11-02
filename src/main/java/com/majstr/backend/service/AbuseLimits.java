package com.majstr.backend.service;

import com.majstr.backend.config.RateLimitProperties;
import com.majstr.backend.exception.TooManyRequestsException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;

/**
 * The limits a caller reaches for by name, each answering with {@link TooManyRequestsException}
 * (review B-125, B-127, B-128). Process-local {@link BucketRegistry}, single-node like the others.
 */
@Component
public class AbuseLimits {

    private final Limiter<UUID> aiImport;
    private final Limiter<String> loginFailures;
    private final Limiter<String> loginFromIp;
    private final Limiter<String> forgotForAddress;
    private final Limiter<String> forgotFromIp;
    private final Limiter<String> anonymous;
    private final Limiter<UUID> emailChange;

    public AbuseLimits(RateLimitProperties props) {
        this.aiImport = new Limiter<>(props.aiImport().maxPerHour(), Duration.ofHours(1));
        this.loginFailures = new Limiter<>(props.loginGuard().failuresPerAccountPerHour(), Duration.ofHours(1));
        this.loginFromIp = new Limiter<>(props.loginGuard().attemptsPerIpPerQuarter(), Duration.ofMinutes(15));
        this.forgotForAddress = new Limiter<>(props.forgotGuard().perAddressPerHour(), Duration.ofHours(1));
        this.forgotFromIp = new Limiter<>(props.forgotGuard().perIpPerHour(), Duration.ofHours(1));
        this.anonymous = new Limiter<>(props.anonymous().perIpPerMinute(), Duration.ofMinutes(1));
        this.emailChange = new Limiter<>(props.emailChange().maxPerDay(), Duration.ofDays(1));
    }

    /**
     * One form for an address as a limiter key. Java lowercasing leaves «ſ» and «ı» alone while the
     * database folds them to S and I, so the same account had several buckets.
     */
    public static String foldEmail(String email) {
        return email == null ? "" : email.trim().toUpperCase(Locale.ROOT).toLowerCase(Locale.ROOT);
    }

    public void requireAiImport(UUID account) {
        aiImport.consume(account, "error.rate.ai-import");
    }

    /**
     * Keyed on the account when there is one and on the address when there is not, so a limit reached
     * says nothing about whether the account exists. Only a failure spends it: a master who types the
     * right password never loses the bucket, and it is refused only while it is empty.
     */
    public void requireLoginNotBlocked(String accountKey) {
        loginFailures.requireAvailable(accountKey, "error.rate.login");
    }

    public void recordLoginFailure(String accountKey) {
        loginFailures.spend(accountKey);
    }

    public void requireLoginFromIp(String ip) {
        loginFromIp.consume(ip, "error.rate.login");
    }

    public void requireForgot(String ip, String email) {
        forgotFromIp.consume(ip, "error.rate.forgot");
        forgotForAddress.consume(foldEmail(email), "error.rate.forgot");
    }

    public void requireAnonymous(String ip) {
        anonymous.consume(ip, "error.rate.anonymous");
    }

    public void requireEmailChange(UUID account) {
        emailChange.consume(account, "error.rate.email-change");
    }

    private static final class Limiter<K> {

        private final BucketRegistry<K> buckets;

        Limiter(int capacity, Duration window) {
            this.buckets = new BucketRegistry<>(Bandwidth.builder()
                    .capacity(capacity)
                    .refillIntervally(capacity, window)
                    .build(), window);
        }

        void consume(K key, String messageKey) {
            ConsumptionProbe probe = buckets.get(key).tryConsumeAndReturnRemaining(1);
            if (!probe.isConsumed()) {
                throw new TooManyRequestsException(messageKey, retryAfter(probe.getNanosToWaitForRefill()));
            }
        }

        void requireAvailable(K key, String messageKey) {
            Bucket bucket = buckets.get(key);
            if (bucket.getAvailableTokens() < 1) {
                throw new TooManyRequestsException(messageKey,
                        retryAfter(bucket.estimateAbilityToConsume(1).getNanosToWaitForRefill()));
            }
        }

        void spend(K key) {
            buckets.get(key).tryConsume(1);
        }

        private static long retryAfter(long nanos) {
            return Math.max(1L, Duration.ofNanos(nanos).toSeconds());
        }
    }
}
