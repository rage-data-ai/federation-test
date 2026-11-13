package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTokenRevocationStoreTest {

    @TempDir
    Path temp;

    @Test
    void persistsAcrossInstances() {
        Path file = temp.resolve("revoked.tsv");
        FileTokenRevocationStore first = new FileTokenRevocationStore(file);
        first.revoke("abc123", 1L);
        assertTrue(first.isRevoked("abc123"));

        FileTokenRevocationStore second = new FileTokenRevocationStore(file);
        assertTrue(second.isRevoked("abc123"));
        assertFalse(second.isRevoked("other"));
    }
}
