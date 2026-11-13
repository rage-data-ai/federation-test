package com.cmcc.cmdevops.security.federation.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;

/**
 * 令牌吊销门面：指纹化后写入 {@link TokenRevocationStore}。
 * 默认内存；配置 {@code federation.security.revocation-store-path} 可跨实例共享文件 denylist。
 */
@Component
public class TokenRevocationService {

    private final TokenRevocationStore store;

    public TokenRevocationService(TokenRevocationStore store) {
        this.store = store;
    }

    /** 测试 / WebMvc 切片默认内存 store。 */
    public TokenRevocationService() {
        this(new InMemoryTokenRevocationStore());
    }

    public void revoke(String token) {
        if (token == null || token.isBlank()) {
            return;
        }
        store.revoke(fingerprint(token), System.currentTimeMillis());
    }

    /** 按已计算指纹吊销（供 back-channel 仅传 jti/hash 场景）。 */
    public void revokeFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank()) {
            return;
        }
        store.revoke(fingerprint.trim().toLowerCase(), System.currentTimeMillis());
    }

    public boolean isRevoked(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return store.isRevoked(fingerprint(token));
    }

    public int size() {
        return store.size();
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
