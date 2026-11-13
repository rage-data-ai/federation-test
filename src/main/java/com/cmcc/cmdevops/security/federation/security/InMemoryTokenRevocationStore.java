package com.cmcc.cmdevops.security.federation.security;

import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryTokenRevocationStore implements TokenRevocationStore {

    private final ConcurrentHashMap<String, Long> revokedAtMs = new ConcurrentHashMap<>();

    @Override
    public void revoke(String fingerprint, long revokedAtMs) {
        this.revokedAtMs.put(fingerprint, revokedAtMs);
    }

    @Override
    public boolean isRevoked(String fingerprint) {
        return revokedAtMs.containsKey(fingerprint);
    }

    @Override
    public int size() {
        return revokedAtMs.size();
    }
}
