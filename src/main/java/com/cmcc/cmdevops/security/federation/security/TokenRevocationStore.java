package com.cmcc.cmdevops.security.federation.security;

/**
 * 令牌吊销 denylist 存储。默认内存；可配置文件路径实现跨实例共享（共享卷）。
 */
public interface TokenRevocationStore {

    void revoke(String fingerprint, long revokedAtMs);

    boolean isRevoked(String fingerprint);

    int size();
}
