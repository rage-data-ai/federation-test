package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import redis.clients.jedis.JedisPooled;

class RedisTokenRevocationStoreTest {

    @Test
    void revokeAndLookupUsePrefixedKeys() {
        JedisPooled jedis = mock(JedisPooled.class);
        when(jedis.exists("fed:revoked:abc")).thenReturn(true);
        when(jedis.exists("fed:revoked:missing")).thenReturn(false);

        RedisTokenRevocationStore store = new RedisTokenRevocationStore(jedis);
        store.revoke("abc", 123L);
        verify(jedis).set(eq("fed:revoked:abc"), eq("123"));
        assertTrue(store.isRevoked("abc"));
        assertFalse(store.isRevoked("missing"));
        verify(jedis).exists("fed:revoked:abc");
        verify(jedis).exists("fed:revoked:missing");
    }
}
