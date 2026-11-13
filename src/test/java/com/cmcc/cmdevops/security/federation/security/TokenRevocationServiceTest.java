package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TokenRevocationServiceTest {

    @Test
    void revokeMarksToken() {
        TokenRevocationService service = new TokenRevocationService();
        assertFalse(service.isRevoked("abc"));
        service.revoke("abc");
        assertTrue(service.isRevoked("abc"));
        assertFalse(service.isRevoked("other"));
    }

    @Test
    void fileBackedStoreShared(@TempDir Path temp) {
        Path file = temp.resolve("denylist.tsv");
        TokenRevocationService a = new TokenRevocationService(new FileTokenRevocationStore(file));
        TokenRevocationService b = new TokenRevocationService(new FileTokenRevocationStore(file));
        a.revoke("shared-token");
        assertTrue(b.isRevoked("shared-token"));
    }

    @Test
    void revokeByFingerprint() {
        TokenRevocationService service = new TokenRevocationService();
        String fp = TokenRevocationService.fingerprint("secret");
        service.revokeFingerprint(fp);
        assertTrue(service.isRevoked("secret"));
    }
}
