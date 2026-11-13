package com.cmcc.cmdevops.security.federation.security;

/**
 * 令牌吊销 denylist 存储。默认内存；可配置文件路径或 Redis URI 实现跨实例共享。
 */
public interface TokenRevocationStore extends AutoCloseable {

    void revoke(String fingerprint, long revokedAtMs);

    boolean isRevoked(String fingerprint);

    int size();

    @Override
    default void close() {
        // no-op for memory / file stores
    }
}
