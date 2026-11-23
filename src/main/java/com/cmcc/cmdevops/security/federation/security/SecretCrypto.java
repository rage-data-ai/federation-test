package com.cmcc.cmdevops.security.federation.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

/**
 * 联邦库敏感字段落库加密（AES-256-GCM）。
 * <p>密文格式：{@code enc:v1:<ivB64>:<cipherB64>}。无 {@link #PREFIX} 前缀的视为历史明文。
 * 主密钥来自 {@code FEDERATION_SECRETS_KEY}（32 字节 base64，或任意口令经 SHA-256 派生）。
 */
@Component
public class SecretCrypto {

    public static final String PREFIX = "enc:v1:";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public SecretCrypto(FederationProperties properties) {
        String raw = properties.getSecurity().getSecretsKey();
        this.key = deriveKey(raw);
    }

    public boolean enabled() {
        return key != null;
    }

    /** 写入前：明文 → 密文；已是密文或空则原样。未配置密钥时原样落库（兼容本地）。 */
    public String seal(String plaintext) {
        if (plaintext == null || plaintext.isBlank() || !enabled() || plaintext.startsWith(PREFIX)) {
            return plaintext;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] cipherBytes = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
            return PREFIX + b64.encodeToString(iv) + ":" + b64.encodeToString(cipherBytes);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("加密敏感字段失败", e);
        }
    }

    /** 读出后：密文 → 明文；明文兼容原样返回。 */
    public String open(String stored) {
        if (stored == null || stored.isBlank() || !stored.startsWith(PREFIX)) {
            return stored;
        }
        if (!enabled()) {
            throw new IllegalStateException(
                    "库中存在加密敏感字段，但未配置 FEDERATION_SECRETS_KEY，无法解密");
        }
        String body = stored.substring(PREFIX.length());
        int sep = body.indexOf(':');
        if (sep <= 0 || sep == body.length() - 1) {
            throw new IllegalStateException("敏感字段密文格式无效");
        }
        try {
            Base64.Decoder b64 = Base64.getUrlDecoder();
            byte[] iv = b64.decode(body.substring(0, sep));
            byte[] cipherBytes = b64.decode(body.substring(sep + 1));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(cipherBytes), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("解密敏感字段失败（密钥是否匹配？）", e);
        }
    }

    static SecretKey deriveKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.strip();
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(text);
            if (decoded.length == 32) {
                return new SecretKeySpec(decoded, "AES");
            }
            if (decoded.length == 16 || decoded.length == 24) {
                return new SecretKeySpec(decoded, "AES");
            }
        } catch (IllegalArgumentException ignored) {
            // fall through to passphrase derive
        }
        try {
            byte[] standard = Base64.getDecoder().decode(text);
            if (standard.length == 16 || standard.length == 24 || standard.length == 32) {
                return new SecretKeySpec(standard, "AES");
            }
        } catch (IllegalArgumentException ignored) {
            // passphrase
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return new SecretKeySpec(sha.digest(text.getBytes(StandardCharsets.UTF_8)), "AES");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("无法派生 FEDERATION_SECRETS_KEY", e);
        }
    }

    /** 测试辅助：固定密钥构造。 */
    public static SecretCrypto forTests(String secretsKey) {
        FederationProperties props = new FederationProperties();
        props.getSecurity().setSecretsKey(secretsKey);
        return new SecretCrypto(props);
    }

    /** 随机生成 32 字节 key 的 url-safe base64（运维文档用）。 */
    public static String generateKeyMaterial() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** 打包长度校验（未使用，保留防优化）。 */
    @SuppressWarnings("unused")
    private static int packedSize(byte[] iv, byte[] cipher) {
        return ByteBuffer.allocate(4).putInt(iv.length + cipher.length).getInt(0);
    }
}
