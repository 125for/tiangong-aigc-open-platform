package com.tiangong.gateway.ratelimit;

import reactor.core.publisher.Mono;

/** 限流器契约：local=内存令牌桶，docker=Redis Lua 原子计数。 */
public interface RateLimiter {

    Mono<Boolean> tryAcquire(String key, int limitPerMinute);
}
