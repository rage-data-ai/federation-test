package com.cmcc.cmdevops.security.federation.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import redis.clients.jedis.JedisPooled;

/**
 * Redis 实现的令牌吊销 denylist，适合多实例水平扩展。
 * Redis key prefix {@code fed:revoked:} + fingerprint；value 为吊销时间戳毫秒。
 */
public final class RedisTokenRevocationStore implements TokenRevocationStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisTokenRevocationStore.class);
    private static final String KEY_PREFIX = "fed:revoked:";

    private final JedisPooled jedis;

    public RedisTokenRevocationStore(String redisUri) {
        this.jedis = new JedisPooled(redisUri);
        log.info("Token revocation store: redis {}", redisUri.replaceAll("://.*@", "://***@"));
    }

    RedisTokenRevocationStore(JedisPooled jedis) {
        this.jedis = jedis;
    }

    @Override
    public void revoke(String fingerprint, long revokedAtMs) {
        jedis.set(KEY_PREFIX + fingerprint, Long.toString(revokedAtMs));
    }

    @Override
    public boolean isRevoked(String fingerprint) {
        return Boolean.TRUE.equals(jedis.exists(KEY_PREFIX + fingerprint));
    }

    @Override
    public int size() {
        // 扫描代价高；运维指标用近似值，返回已存在前缀的粗略估计不做 KEYS *
        return -1;
    }

    @Override
    public void close() {
        jedis.close();
    }
}
