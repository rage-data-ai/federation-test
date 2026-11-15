package com.cmcc.cmdevops.security.federation.account;

/** 登录会话。库里只存令牌的 SHA-256，拿到库也换不回令牌。 */
public record UserSession(String tokenHash, long userId, long createdAt, long expiresAt) {
}
