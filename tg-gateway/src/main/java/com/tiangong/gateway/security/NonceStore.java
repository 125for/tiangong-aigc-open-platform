package com.tiangong.gateway.security;

import reactor.core.publisher.Mono;

/** nonce 防重存契约。 */
public interface NonceStore {

    Mono<Boolean> tryAcquire(String nonce, long ttlMillis);
}
