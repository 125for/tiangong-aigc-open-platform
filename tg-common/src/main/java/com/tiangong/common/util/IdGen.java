package com.tiangong.common.util;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/** 业务单号/密钥生成器：时间有序 + 随机后缀，长度可控便于索引。 */
public final class IdGen {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHANUM = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final DateTimeFormatter COMPACT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private IdGen() {
    }

    public static String orderNo() {
        return "ORD" + LocalDateTime.now().format(COMPACT) + random(6);
    }

    public static String taskNo() {
        return "TSK" + LocalDateTime.now().format(COMPACT) + random(6);
    }

    public static String paymentNo() {
        return "PAY" + LocalDateTime.now().format(COMPACT) + random(6);
    }

    public static String appKey() {
        return "ak_" + random(16).toLowerCase();
    }

    /** AppSecret：256-bit 随机，Base64URL 编码。 */
    public static String appSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String refreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String random(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(ALPHANUM[RANDOM.nextInt(ALPHANUM.length)]);
        }
        return sb.toString();
    }
}
