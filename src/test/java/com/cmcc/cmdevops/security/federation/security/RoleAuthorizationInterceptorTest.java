package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.server.ResponseStatusException;

class RoleAuthorizationInterceptorTest {

    private final RoleAuthorizationInterceptor interceptor = new RoleAuthorizationInterceptor();

    @AfterEach
    void tearDown() {
        GatewayUser.clear();
    }

    @Test
    void allowsWhenRolePresent() throws Exception {
        GatewayUser.set(new GatewayUser("admin", Set.of(GatewayRole.ADMIN)));
        Method method = Sample.class.getMethod("adminOnly");
        assertTrue(interceptor.preHandle(new MockHttpServletRequest(), new MockHttpServletResponse(),
                new HandlerMethod(new Sample(), method)));
    }

    @Test
    void rejectsWhenRoleMissing() throws Exception {
        GatewayUser.set(new GatewayUser("reader", Set.of(GatewayRole.QUERY)));
        Method method = Sample.class.getMethod("adminOnly");
        assertThrows(ResponseStatusException.class, () -> interceptor.preHandle(
                new MockHttpServletRequest(), new MockHttpServletResponse(), new HandlerMethod(new Sample(), method)));
    }

    static class Sample {
        @RequireRole(GatewayRole.ADMIN)
        public void adminOnly() {
        }
    }
}
