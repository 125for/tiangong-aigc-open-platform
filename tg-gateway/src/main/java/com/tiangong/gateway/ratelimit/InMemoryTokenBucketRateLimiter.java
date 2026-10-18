package com.tiangong.gateway.ratelimit;

import reactor.core.publisher.Mono;

/** 单机令牌桶（local profile）。 */
public class InMemoryTokenBucketRateLimiter implements RateLimiter {

    private static final class Bucket {
        double tokens;
        long lastRefillNanos;
    }

    private final java.util.Map<String, Bucket> buckets = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public Mono<Boolean> tryAcquire(String key, int limitPerMinute) {
        return Mono.fromSupplier(() -> doAcquire(key, limitPerMinute));
    }

    private boolean doAcquire(String key, int limitPerMinute) {
        if (limitPerMinute <= 0) {
            return false;
        }
        Bucket bucket = buckets.computeIfAbsent(key, k -> {
            Bucket b = new Bucket();
            b.tokens = limitPerMinute;
            b.lastRefillNanos = System.nanoTime();
            return b;
        });
        double perNano = limitPerMinute / 60_000_000_000.0;
        synchronized (bucket) {
            long now = System.nanoTime();
            double refill = (now - bucket.lastRefillNanos) * perNano;
            bucket.tokens = Math.min(limitPerMinute, bucket.tokens + refill);
            bucket.lastRefillNanos = now;
            if (bucket.tokens >= 1) {
                bucket.tokens -= 1;
                return true;
            }
            return false;
        }
    }

    public int size() {
        return buckets.size();
    }
}
