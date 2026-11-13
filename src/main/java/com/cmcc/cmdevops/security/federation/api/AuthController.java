package com.cmcc.cmdevops.security.federation.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cmcc.cmdevops.security.federation.config.FederationProperties;
import com.cmcc.cmdevops.security.federation.security.GatewayRole;
import com.cmcc.cmdevops.security.federation.security.GatewayUser;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final FederationProperties properties;

    public AuthController(FederationProperties properties) {
        this.properties = properties;
    }

    /** 返回当前 Bearer / JWT 解析后的身份；鉴权关闭时为 anonymous。 */
    @GetMapping("/me")
    public Map<String, Object> me() {
        GatewayUser user = GatewayUser.current();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", user.name());
        body.put("roles", user.roles().stream().map(GatewayRole::name).sorted().collect(Collectors.toList()));
        body.put("securityEnabled", properties.getSecurity().isEnabled());
        body.put("jwtEnabled", properties.getSecurity().getJwt().isEnabled());
        return body;
    }
}
