package com.cmcc.cmdevops.security.federation.security;

import java.nio.file.Path;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

@Configuration
public class TokenRevocationConfiguration {

    @Bean
    TokenRevocationStore tokenRevocationStore(FederationProperties properties) {
        String path = properties.getSecurity().getRevocationStorePath();
        if (path == null || path.isBlank()) {
            return new InMemoryTokenRevocationStore();
        }
        return new FileTokenRevocationStore(Path.of(path.trim()));
    }
}
