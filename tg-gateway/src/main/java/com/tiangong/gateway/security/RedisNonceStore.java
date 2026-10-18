package com.tiangong.gateway.security;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;

/** docker profile：Redis SET NX EX 原子去重。 */
@Component
@Profile("docker")
public class RedisNonceStore implements NonceStore {

    private final ReactiveStringRedisTemplate redis;

    public RedisNonceStore(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Boolean> tryAcquire(String nonce, long ttlMillis) {
        return redis.opsForValue()
                .set("nonce:" + nonce, "1", Duration.ofMillis(ttlMillis))
                .defaultIfEmpty(false)
                .onErrorReturn(true);   // Redis 故障时放行，依赖签名时间窗兜底
    }
}
