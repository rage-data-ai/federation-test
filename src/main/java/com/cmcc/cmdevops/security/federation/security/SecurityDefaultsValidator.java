package com.cmcc.cmdevops.security.federation.security;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;

/**
 * 启动时拒绝已知弱静态令牌，除非显式 {@code federation.security.allow-insecure-defaults=true}。
 */
@Component
@Order(0)
public class SecurityDefaultsValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SecurityDefaultsValidator.class);

    /** 仓库示例 / 历史默认值，不可用于生产。 */
    static final Set<String> WEAK_TOKENS = Set.of("demo-token", "reader-token");

    private final FederationProperties properties;

    public SecurityDefaultsValidator(FederationProperties properties) {
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<String, String> tokens = properties.getSecurity().getTokens();
        if (tokens == null || tokens.isEmpty()) {
            return;
        }
        boolean weak = tokens.values().stream()
                .filter(value -> value != null && !value.isBlank())
                .map(value -> value.strip().toLowerCase(Locale.ROOT))
                .anyMatch(WEAK_TOKENS::contains);
        if (!weak) {
            return;
        }
        if (properties.getSecurity().isAllowInsecureDefaults()) {
            log.warn(
                    "检测到弱静态令牌（demo-token/reader-token）。allow-insecure-defaults=true，仅可用于本地；生产请改强随机 token 并关闭该开关");
            return;
        }
        throw new IllegalStateException(
                "拒绝使用弱静态令牌（demo-token / reader-token）。请设置强随机 "
                        + "FEDERATION_DEMO_TOKEN / FEDERATION_READER_TOKEN，或仅用本地账号；"
                        + "本地调试可设 FEDERATION_ALLOW_INSECURE_DEFAULTS=true");
    }
}
