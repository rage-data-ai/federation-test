package com.cmcc.cmdevops.security.federation.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.Cookie;

class SessionCookiesTest {

    @Test
    void writeReadAndClear() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SessionCookies.write(response, "tok-1", 3600, false);
        String setCookie = response.getHeader("Set-Cookie");
        assertTrue(setCookie.contains(SessionCookies.NAME + "=tok-1"));
        assertTrue(setCookie.contains("HttpOnly"));
        assertTrue(setCookie.contains("SameSite=Lax"));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(SessionCookies.NAME, "tok-1"));
        assertEquals("tok-1", SessionCookies.read(request));

        MockHttpServletResponse cleared = new MockHttpServletResponse();
        SessionCookies.clear(cleared, true);
        assertTrue(cleared.getHeader("Set-Cookie").contains("Max-Age=0"));
        assertTrue(cleared.getHeader("Set-Cookie").contains("Secure"));
    }

    @Test
    void blankWriteClearsAndMissingCookieIsNull() {
        MockHttpServletResponse response = new MockHttpServletResponse();
        SessionCookies.write(response, "  ", 10, false);
        assertTrue(response.getHeader("Set-Cookie").contains("Max-Age=0"));
        assertNull(SessionCookies.read(new MockHttpServletRequest()));
    }

    @Test
    void secureRequestDetectsForwardedProto() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Forwarded-Proto", "https");
        assertTrue(SessionCookies.secureRequest(request));
    }
}
