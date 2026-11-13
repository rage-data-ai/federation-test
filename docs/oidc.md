# OIDC / JWT 接入指南

网关作为**资源服务器**：校验 `Authorization: Bearer`（共享 token 或 IdP JWT）。控制台支持手动粘贴令牌，以及可选的 **Authorization Code + PKCE** 登录。

## 网关

```bash
export FEDERATION_SECURITY_ENABLED=true
export FEDERATION_JWT_ISSUER_URI=https://idp.example/realms/federation
export FEDERATION_JWT_ROLES_CLAIM=roles
```

- `issuer-uri` 非空时启用 JWKS JWT 校验（见 `JwtAccessTokenService`）。
- JWT 中 `roles` claim（可改）应为 `QUERY` / `ADMIN`（逗号分隔字符串或数组）。
- 仍可配置 `federation.security.tokens` 作为机器账号。

自检：`GET /api/v1/auth/me` 返回当前身份与 `jwtEnabled`。

## 控制台（Vite）

在 `web/.env.local`：

```bash
VITE_OIDC_AUTHORITY=https://idp.example/realms/federation
VITE_OIDC_CLIENT_ID=federation-console
# 可选；默认当前页面 origin+pathname
VITE_OIDC_REDIRECT_URI=http://localhost:5173/
VITE_OIDC_SCOPE=openid profile roles
```

Keycloak：创建 Public client，开启 PKCE，Valid redirect URIs 包含控制台地址；Token mapper 写入 `roles`。  
其它 IdP：若 authorize/token 路径不是 Keycloak 风格，请将 `authority` 配到可推导 `/oauth2/v1/authorize|token` 的发行方，或后续扩展发现文档。

侧栏「身份」→「OIDC 登录」；回调后 access_token 写入 localStorage，并由 `api.ts` 自动附带。
