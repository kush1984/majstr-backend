package com.majstr.backend.service;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The per-key bucket map every rate limiter here keeps — with the eviction none of them had
 * (review B-22).
 *
 * <p>Eleven limiters each held a bare {@code ConcurrentHashMap} that only ever grew. For the
 * account-keyed ones that is bounded by the number of masters and merely untidy. For the six keyed
 * by something a stranger chooses — {@code email|ip} on login, the IP on register and forgot-
 * password, a portal token — it is unbounded by <b>request content</b>: the cheapest way to spend
 * this process's heap was to keep logging in as a new address.</p>
 *
 * <p>An entry untouched for twice its own refill period is dropped, and that changes no decision
 * anyone can observe: by then the bucket has refilled to capacity — and a full bucket is exactly
 * what a first-time key gets. That equivalence is the whole licence to evict, and it is why the
 * sweep may be as crude as it is. The period is passed in rather than assumed, because the login
 * and portal windows are configuration.</p>
 *
 * <p>The sweep runs opportunistically, on a call that finds one due, rather than on a scheduler: it
 * costs a pass over a map that is small by construction, and a limiter nobody is calling has no
 * growth to contain. No new dependency, and nothing to shut down.</p>
 *
 * <p>Process-local, like everything it replaces — the multi-instance store is still an open
 * question, and this class changes nothing about that.</p>
 */
public final class BucketRegistry<K> {

    private static final Duration MIN_IDLE = Duration.ofHours(2);
    private static final Duration SWEEP_EVERY = Duration.ofMinutes(10);

    /** The ceiling the eviction above keeps. Well past any honest load: the login limiter's key is
     *  {@code email|ip}, and 100 000 distinct ones is not a product this size. */
    private static final int MAX_KEYS = 100_000;

    private final Bandwidth bandwidth;
    private final long idleNanos;
    private final ConcurrentMap<K, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong nextSweepAt = new AtomicLong(System.nanoTime() + SWEEP_EVERY.toNanos());

    /**
     * @param refillPeriod the bandwidth's own refill period — the eviction threshold is TWICE it
     *                     (never under two hours), because that is what makes dropping an entry
     *                     unobservable: the bucket is full again by then. The window is
     *                     configurable per limiter, so it is passed rather than assumed.
     */
    public BucketRegistry(Bandwidth bandwidth, Duration refillPeriod) {
        this.bandwidth = bandwidth;
        Duration idle = refillPeriod.multipliedBy(2);
        this.idleNanos = (idle.compareTo(MIN_IDLE) > 0 ? idle : MIN_IDLE).toNanos();
    }

    /** The bucket for this key, created full on first use. */
    public Bucket get(K key) {
        sweepIfDue();
        evictIfOversized();
        Entry entry = entries.computeIfAbsent(key,
                k -> new Entry(Bucket.builder().addLimit(bandwidth).build()));
        entry.touchedAt = System.nanoTime();
        return entry.bucket;
    }

    /** Visible for tests: how many keys are currently held. */
    public int size() {
        return entries.size();
    }

    /**
     * A SIZE cap beside the idle sweep (review B-54).
     *
     * <p>Six of these registries are keyed by something a STRANGER picks — {@code email|ip}, a bare
     * IP, a portal token — and the idle sweep only runs every few minutes with a threshold of hours.
     * Between two sweeps a key-rotating caller can add entries at request rate, which is a slow leak
     * with a wide-open ceiling. The cap turns it into a bounded one: past {@code MAX_KEYS} the
     * oldest-touched tenth goes, which is the same thing the sweep does, only sooner and by
     * pressure rather than by clock.</p>
     *
     * <p>Evicting a bucket is unobservable for a key that has gone quiet (it refills to full), and
     * for a key under active attack the worst case is that it gets a fresh full bucket — which is
     * exactly what rotating the key gave it anyway. 100 000 keys is far above any honest load here.</p>
     */
    private void evictIfOversized() {
        if (entries.size() < MAX_KEYS) {
            return;
        }
        int toDrop = Math.max(1, entries.size() / 10);
        entries.entrySet().stream()
                .sorted(java.util.Comparator.comparingLong(e -> e.getValue().touchedAt))
                .limit(toDrop)
                .map(Map.Entry::getKey)
                .toList()
                .forEach(entries::remove);
    }

    private void sweepIfDue() {
        long now = System.nanoTime();
        long due = nextSweepAt.get();
        if (now < due || !nextSweepAt.compareAndSet(due, now + SWEEP_EVERY.toNanos())) {
            return; // not due, or another thread is already sweeping this round
        }
        long cutoff = now - idleNanos;
        entries.entrySet().removeIf(e -> e.getValue().touchedAt < cutoff);
    }

    /** Package-private for tests that need to age the map without waiting two hours. */
    void ageAll() {
        long aged = System.nanoTime() - idleNanos - 1;
        for (Map.Entry<K, Entry> e : entries.entrySet()) {
            e.getValue().touchedAt = aged;
        }
        // MIN_VALUE, not 0: System.nanoTime()'s origin is arbitrary and may be negative, so a
        // literal zero would not reliably read as «overdue».
        nextSweepAt.set(Long.MIN_VALUE);
    }

    private static final class Entry {
        private final Bucket bucket;
        private volatile long touchedAt;

        private Entry(Bucket bucket) {
            this.bucket = bucket;
            this.touchedAt = System.nanoTime();
        }
    }
}
