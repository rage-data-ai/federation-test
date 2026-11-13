package com.cmcc.cmdevops.security.federation.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.cmcc.cmdevops.security.federation.security.RoleAuthorizationInterceptor;

@Configuration
public class WebMvcSecurityConfiguration implements WebMvcConfigurer {

    private final RoleAuthorizationInterceptor roleAuthorizationInterceptor;

    public WebMvcSecurityConfiguration(RoleAuthorizationInterceptor roleAuthorizationInterceptor) {
        this.roleAuthorizationInterceptor = roleAuthorizationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(roleAuthorizationInterceptor).addPathPatterns("/api/**");
    }
}
