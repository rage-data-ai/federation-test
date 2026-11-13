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
其它 IdP：控制台会请求 `{authority}/.well-known/openid-configuration`（OIDC Discovery）解析 authorize/token/logout；失败时回退 Keycloak 或 `/oauth2/v1/*` 约定路径。

侧栏「身份」→「OIDC 登录」；回调后 `access_token` / `refresh_token` 写入 localStorage，并由 `api.ts` 自动附带。  
可用「刷新令牌」调用 `grant_type=refresh_token`（见 `refreshAccessToken`）。
「OIDC 注销」清除本地令牌并跳转 IdP logout（`post_logout_redirect_uri`）。

## 表级 ACL

`federation.security.catalogs` 除 catalog 名外，还支持：

- `mysql_shop.public.orders` — 单表
- `mysql_shop.public.*` — schema 下全部表
- `mysql_shop` / `mysql_shop.*` — catalog 下全部表
- `*` — 全部

查询路径在路由前校验 `TableRef`；列表/元数据仍按 catalog 过滤。


## 行级过滤（启发式）

```yaml
federation.security.row-filters:
  reader: "mysql_shop.public.orders:tenant_id = 'acme'"
```

查询若触及该表，SQL 文本须包含谓词中的列标识（如 `tenant_id`）；否则 403。


## 令牌吊销

- `POST /api/v1/auth/logout`：吊销当前 Bearer（登出）。
- `POST /api/v1/auth/revoke`（ADMIN）：body `{"token":"..."}` 或吊销当前 token。
- `POST /api/v1/auth/backchannel-logout`：IdP/运维 back-channel；ADMIN Bearer 或头 `X-Backchannel-Secret`（`federation.security.backchannel-logout-secret`）；body `{"token":"..."}` 或 `{"fingerprint":"<sha256-hex>"}`。
- 默认进程内 denylist；`federation.security.revocation-redis-uri` 优先（多实例推荐），否则 `revocation-store-path` 用共享文件。控制台注销会先调 logout 再跳转 IdP。

```yaml
federation.security.revocation-redis-uri: redis://localhost:6379/0
# 或
federation.security.revocation-store-path: /var/lib/federation/revoked-tokens.tsv
federation.security.backchannel-logout-secret: ${BACKCHANNEL_LOGOUT_SECRET:}
```

