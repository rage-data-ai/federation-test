package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

class SecurityDefaultsValidatorTest {

    @Test
    void rejectsWeakTokensUnlessAllowed() {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setAllowInsecureDefaults(false);
        properties.getSecurity().getTokens().put("demo", "demo-token");
        SecurityDefaultsValidator validator = new SecurityDefaultsValidator(properties);
        IllegalStateException thrown = assertThrows(
                IllegalStateException.class, () -> validator.run(new DefaultApplicationArguments()));
        assertTrue(thrown.getMessage().contains("弱静态令牌"));
    }

    @Test
    void allowsWeakTokensWhenFlagSet() {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setAllowInsecureDefaults(true);
        properties.getSecurity().getTokens().put("demo", "demo-token");
        assertDoesNotThrow(() -> new SecurityDefaultsValidator(properties).run(new DefaultApplicationArguments()));
    }

    @Test
    void ignoresStrongOrBlankTokens() {
        FederationProperties properties = new FederationProperties();
        properties.getSecurity().setAllowInsecureDefaults(false);
        properties.getSecurity().getTokens().put("demo", "s3cret-long-random");
        properties.getSecurity().getTokens().put("reader", "");
        assertDoesNotThrow(() -> new SecurityDefaultsValidator(properties).run(new DefaultApplicationArguments()));
    }
}
