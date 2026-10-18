package com.tiangong.gateway.security;

import com.tiangong.common.util.CryptoUtil;

/**
 * 开放平台签名验证（纯函数，便于单测）：
 * Signature = HMAC_SHA256(appSecret, appKey \n timestamp \n nonce \n METHOD \n path \n sha256hex(body))
 * 时间窗 ±300s；nonce 在窗口内不得重复（防重放由 NonceStore 保证）。
 */
public final class SignatureVerifier {

    public static final long WINDOW_MILLIS = 300_000L;

    private SignatureVerifier() {
    }

    public static String canonicalString(String appKey, String timestamp, String nonce,
                                         String method, String path, byte[] body) {
        return appKey + "\n" + timestamp + "\n" + nonce + "\n"
                + method.toUpperCase() + "\n" + path + "\n"
                + CryptoUtil.sha256Hex(body == null ? new byte[0] : body);
    }

    public static boolean verify(String appSecret, String appKey, String timestamp, String nonce,
                                 String method, String path, byte[] body, String signature) {
        if (appSecret == null || timestamp == null || nonce == null || signature == null) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        long skew = Math.abs(System.currentTimeMillis() - ts);
        if (skew > WINDOW_MILLIS) {
            return false;
        }
        String expected = CryptoUtil.hmacSha256Hex(appSecret,
                canonicalString(appKey, timestamp, nonce, method, path, body == null ? new byte[0] : body));
        return CryptoUtil.constantTimeEquals(expected, signature);
    }
}
