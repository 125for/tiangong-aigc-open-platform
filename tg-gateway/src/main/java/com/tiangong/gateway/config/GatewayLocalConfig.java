package com.tiangong.gateway.config;

import com.tiangong.gateway.ratelimit.InMemoryTokenBucketRateLimiter;
import com.tiangong.gateway.ratelimit.RateLimiter;
import com.tiangong.gateway.security.InMemoryNonceStore;
import com.tiangong.gateway.security.NonceStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/** local profile：单机实现（docker profile 由 Redis 实现替换，契约一致）。 */
@Configuration
@Profile("!docker")
public class GatewayLocalConfig {

    @Bean
    public RateLimiter rateLimiter() {
        return new InMemoryTokenBucketRateLimiter();
    }

    @Bean
    public NonceStore nonceStore() {
        return new InMemoryNonceStore();
    }
}
