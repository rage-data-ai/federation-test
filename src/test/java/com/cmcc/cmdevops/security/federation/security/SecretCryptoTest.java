package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SecretCryptoTest {

    @Test
    void disabledWhenKeyBlankKeepsPlaintext() {
        SecretCrypto crypto = SecretCrypto.forTests("");
        assertFalse(crypto.enabled());
        assertEquals("sk-plain", crypto.seal("sk-plain"));
        assertEquals("sk-plain", crypto.open("sk-plain"));
    }

    @Test
    void roundTripWithPassphrase() {
        SecretCrypto crypto = SecretCrypto.forTests("unit-test-passphrase");
        assertTrue(crypto.enabled());
        String sealed = crypto.seal("sk-secret-value");
        assertTrue(sealed.startsWith(SecretCrypto.PREFIX));
        assertNotEquals("sk-secret-value", sealed);
        assertEquals("sk-secret-value", crypto.open(sealed));
        assertEquals(sealed, crypto.seal(sealed), "already sealed stays as-is");
    }

    @Test
    void openFailsWithoutKey() {
        SecretCrypto writer = SecretCrypto.forTests("writer-key");
        String sealed = writer.seal("need-key");
        SecretCrypto blank = SecretCrypto.forTests("");
        assertThrows(IllegalStateException.class, () -> blank.open(sealed));
    }

    @Test
    void generateKeyMaterialIsUrlSafeBase64() {
        String key = SecretCrypto.generateKeyMaterial();
        assertTrue(key.length() >= 40);
        SecretCrypto crypto = SecretCrypto.forTests(key);
        assertEquals("x", crypto.open(crypto.seal("x")));
    }

    @Test
    void acceptsStandardBase64KeyMaterial() {
        byte[] raw = new byte[32];
        for (int i = 0; i < raw.length; i++) {
            raw[i] = (byte) i;
        }
        String standard = java.util.Base64.getEncoder().encodeToString(raw);
        SecretCrypto crypto = SecretCrypto.forTests(standard);
        assertEquals("secret", crypto.open(crypto.seal("secret")));
    }

    @Test
    void rejectsCorruptCiphertext() {
        SecretCrypto crypto = SecretCrypto.forTests("corrupt-check");
        assertThrows(IllegalStateException.class, () -> crypto.open(SecretCrypto.PREFIX + "bad"));
        assertThrows(IllegalStateException.class, () -> crypto.open(SecretCrypto.PREFIX + "aa:bb"));
    }
}
