package com.tiangong.gateway.ratelimit;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * docker profile：Redis Lua 固定窗口计数（原子），分布式一致。
 * 首次命中设置 60s 过期；窗口内超限即拒绝。
 */
@Component
@Profile("docker")
public class RedisRateLimiter implements RateLimiter {

    private static final RedisScript<Long> SCRIPT = RedisScript.of(
            "local c = redis.call('INCR', KEYS[1]) "
                    + "if c == 1 then redis.call('PEXPIRE', KEYS[1], 60000) end "
                    + "return c",
            Long.class);

    private final ReactiveStringRedisTemplate redis;

    public RedisRateLimiter(ReactiveStringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public Mono<Boolean> tryAcquire(String key, int limitPerMinute) {
        return redis.execute(SCRIPT, List.of("rl:" + key))
                .next()
                .map(count -> count <= limitPerMinute)
                .onErrorReturn(true);   // Redis 故障时放行（限流是保护手段，不阻断可用性）
    }
}
