package com.tiangong.gateway;

import com.tiangong.common.util.CryptoUtil;
import com.tiangong.gateway.ratelimit.InMemoryTokenBucketRateLimiter;
import com.tiangong.gateway.security.InMemoryNonceStore;
import com.tiangong.gateway.security.SignatureVerifier;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.junit.jupiter.api.Assertions.*;

/** 网关安全组件单测（F3 防重放 / 令牌桶语义）。 */
class GatewaySecurityTest {

    private static final String SECRET = "app-secret-for-test";

    private String sign(String ts, String nonce, String method, String path, byte[] body) {
        return CryptoUtil.hmacSha256Hex(SECRET,
                SignatureVerifier.canonicalString("ak_x", ts, nonce, method, path, body));
    }

    @Test
    void signatureVerifyOkAndTamperRejected() {
        String ts = String.valueOf(System.currentTimeMillis());
        byte[] body = "{\"type\":\"TTS\"}".getBytes();
        String good = sign(ts, "n1", "POST", "/api/v1/tasks", body);
        assertTrue(SignatureVerifier.verify(SECRET, "ak_x", ts, "n1", "POST", "/api/v1/tasks",
                body, good));
        // 篡改 body / path / secret 任一项 → 拒绝
        assertFalse(SignatureVerifier.verify(SECRET, "ak_x", ts, "n1", "POST", "/api/v1/tasks",
                "{\"type\":\"HACK\"}".getBytes(), good));
        assertFalse(SignatureVerifier.verify(SECRET, "ak_x", ts, "n1", "GET", "/api/v1/tasks",
                body, good));
        assertFalse(SignatureVerifier.verify("other", "ak_x", ts, "n1", "POST", "/api/v1/tasks",
                body, good));
    }

    @Test
    void timestampWindowEnforced() {
        byte[] body = new byte[0];
        String old = String.valueOf(System.currentTimeMillis() - 400_000);
        assertFalse(SignatureVerifier.verify(SECRET, "ak_x", old, "n2", "POST", "/p", body,
                sign(old, "n2", "POST", "/p", body)));
        String future = String.valueOf(System.currentTimeMillis() + 400_000);
        assertFalse(SignatureVerifier.verify(SECRET, "ak_x", future, "n3", "POST", "/p", body,
                sign(future, "n3", "POST", "/p", body)));
        assertFalse(SignatureVerifier.verify(SECRET, "ak_x", "not-a-number", "n4", "POST", "/p",
                body, "deadbeef"));
    }

    @Test
    void nonceReplayRejected() {
        InMemoryNonceStore store = new InMemoryNonceStore();
        StepVerifier.create(store.tryAcquire("nonce-1", 300_000)).expectNext(true).verifyComplete();
        StepVerifier.create(store.tryAcquire("nonce-1", 300_000)).expectNext(false).verifyComplete();
        StepVerifier.create(store.tryAcquire("nonce-2", 300_000)).expectNext(true).verifyComplete();
    }

    @Test
    void tokenBucketAllowsBurstThenRejects() {
        InMemoryTokenBucketRateLimiter limiter = new InMemoryTokenBucketRateLimiter();
        int allowed = 0;
        for (int i = 0; i < 15; i++) {
            if (limiter.tryAcquire("ip:1.2.3.4", 10).block()) {
                allowed++;
            }
        }
        assertEquals(10, allowed, "容量 10 的桶应放行前 10 个突发请求");
        assertEquals(1, limiter.size());
    }
}
