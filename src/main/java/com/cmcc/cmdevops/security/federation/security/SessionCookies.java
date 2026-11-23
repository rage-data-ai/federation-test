package com.cmcc.cmdevops.security.federation.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 本地账号会话的 HttpOnly Cookie（{@code FED_SESSION}），降低 XSS 从 JS 读取令牌的风险。
 */
public final class SessionCookies {

    public static final String NAME = "FED_SESSION";

    private SessionCookies() {
    }

    public static void write(HttpServletResponse response, String token, int maxAgeSeconds, boolean secure) {
        if (token == null || token.isBlank()) {
            clear(response, secure);
            return;
        }
        ResponseCookie cookie = ResponseCookie.from(NAME, token.strip())
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite("Lax")
                .maxAge(maxAgeSeconds)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public static void clear(HttpServletResponse response, boolean secure) {
        ResponseCookie cookie = ResponseCookie.from(NAME, "")
                .httpOnly(true)
                .secure(secure)
                .path("/")
                .sameSite("Lax")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    public static String read(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (NAME.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue().strip();
            }
        }
        return null;
    }

    public static boolean secureRequest(HttpServletRequest request) {
        if (request.isSecure()) {
            return true;
        }
        String forwarded = request.getHeader("X-Forwarded-Proto");
        return forwarded != null && "https".equalsIgnoreCase(forwarded.strip());
    }
}
