package com.tiangong.common;

import com.tiangong.common.errcode.ErrorCode;
import com.tiangong.common.exception.BizException;
import com.tiangong.common.util.CryptoUtil;
import com.tiangong.common.util.IdGen;
import com.tiangong.common.util.JwtSupport;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class CommonUtilTest {

    private static final String SECRET = "tiangong-unit-test-jwt-secret-key!!";

    @Test
    void jwtIssueAndParse() {
        String token = JwtSupport.issueAccessToken(SECRET, 42L, "a@b.c", "nick", 60);
        JwtSupport.AccessClaims claims = JwtSupport.parseAccessToken(SECRET, token);
        assertEquals(42L, claims.userId());
        assertEquals("a@b.c", claims.email());
    }

    @Test
    void jwtExpiredOrBadSignatureRejected() {
        String token = JwtSupport.issueAccessToken(SECRET, 1L, "a@b.c", "n", -1);
        BizException e = assertThrows(BizException.class,
                () -> JwtSupport.parseAccessToken(SECRET, token));
        assertEquals(ErrorCode.UNAUTHORIZED, e.getErrorCode());

        String other = JwtSupport.issueAccessToken("another-secret-key-32-bytes-long!!!", 1L, "a@b.c", "n", 60);
        assertThrows(BizException.class, () -> JwtSupport.parseAccessToken(SECRET, other));
    }

    @Test
    void hmacAndSha256() {
        String mac = CryptoUtil.hmacSha256Hex("key", "data");
        assertEquals(64, mac.length());
        assertEquals(mac, CryptoUtil.hmacSha256Hex("key", "data"));
        assertNotEquals(mac, CryptoUtil.hmacSha256Hex("key2", "data"));
        assertEquals(CryptoUtil.sha256Hex("abc"), CryptoUtil.sha256Hex("abc"));
    }

    @Test
    void aesGcmRoundTrip() {
        String key = Base64.getEncoder().encodeToString(new byte[32]);
        String cipher = CryptoUtil.aesGcmEncrypt(key, "hello-app-secret");
        assertNotEquals("hello-app-secret", cipher);
        assertEquals("hello-app-secret", CryptoUtil.aesGcmDecrypt(key, cipher));
        // 随机 IV：两次密文不同
        assertNotEquals(cipher, CryptoUtil.aesGcmEncrypt(key, "hello-app-secret"));
    }

    @Test
    void idGenFormat() {
        assertTrue(IdGen.orderNo().startsWith("ORD"));
        assertTrue(IdGen.taskNo().startsWith("TSK"));
        assertTrue(IdGen.appKey().startsWith("ak_"));
        assertEquals(43, IdGen.refreshToken().length());
    }
}
