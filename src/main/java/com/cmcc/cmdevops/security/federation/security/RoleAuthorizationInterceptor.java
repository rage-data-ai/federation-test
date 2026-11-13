package com.cmcc.cmdevops.security.federation.security;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class RoleAuthorizationInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RequireRole required = method.getMethodAnnotation(RequireRole.class);
        if (required == null) {
            required = method.getBeanType().getAnnotation(RequireRole.class);
        }
        if (required == null) {
            return true;
        }
        if (!GatewayUser.current().hasRole(required.value())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "需要角色 " + required.value() + "，当前用户 " + GatewayUser.current().name() + " 无权访问");
        }
        return true;
    }
}
