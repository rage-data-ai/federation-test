package com.cmcc.cmdevops.security.federation.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * 进程内令牌吊销表（denylist）。用于登出与管理吊销；重启后清空。
 * 生产可替换为 Redis / 共享存储。
 */
@Component
public class TokenRevocationService {

    private final ConcurrentHashMap<String, Long> revokedAtMs = new ConcurrentHashMap<>();

    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        revokedAtMs.put(fingerprint(token), System.currentTimeMillis());
    }

    public boolean isRevoked(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return revokedAtMs.containsKey(fingerprint(token));
    }

    public int size() {
        return revokedAtMs.size();
    }

    static String fingerprint(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(token.hashCode());
        }
    }
}
