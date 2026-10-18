package com.tiangong.gateway.security;

import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/** 单机 nonce 去重（local profile）：时间窗内重复即判定重放。 */
public class InMemoryNonceStore implements NonceStore {

    private static final int MAX_ENTRIES = 100_000;

    private final Map<String, Long> seen = new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Long> eldest) {
            return size() > MAX_ENTRIES;
        }
    };

    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public Mono<Boolean> tryAcquire(String nonce, long ttlMillis) {
        return Mono.fromSupplier(() -> doAcquire(nonce, ttlMillis));
    }

    private boolean doAcquire(String nonce, long ttlMillis) {
        long now = System.currentTimeMillis();
        lock.lock();
        try {
            Long at = seen.get(nonce);
            if (at != null && now - at < ttlMillis) {
                return false;
            }
            seen.put(nonce, now);
            if (seen.size() % 1024 == 0) {
                seen.values().removeIf(t -> now - t >= ttlMillis);
            }
            return true;
        } finally {
            lock.unlock();
        }
    }
}
