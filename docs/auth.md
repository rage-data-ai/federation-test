# 本地账号（注册 / 登录）

开启 `FEDERATION_SECURITY_ENABLED=true` 后，控制台必须先登录。身份有三条来源，按令牌依次解析：

1. 配置文件里的静态令牌（`federation.security.tokens`，适合脚本 / 机器账号）
2. **本地账号会话**（用户名 + 密码，见本文）
3. 外部 IdP 签发的 JWT（见 [`oidc.md`](oidc.md)）

## 自助注册

```bash
curl -s localhost:18080/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice-pass-1","displayName":"爱丽丝"}'
```

- 注册后立即可用，默认角色 `QUERY`，无需审批、无需邮箱。
- 用户名：3–32 位小写字母、数字或 `.` `_` `-`，以字母或数字开头。
- 密码至少 8 位；库里只存 BCrypt 哈希。
- 配置里已出现的用户名（静态令牌、角色、catalog ACL、行级过滤）以及 `anonymous` 不开放注册。

返回体含 `token`、`expiresAt`、`user`（不含密码哈希）。后续请求带 `Authorization: Bearer <token>`。

## 登录与会话

```bash
curl -s localhost:18080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"alice-pass-1"}'
```

- 会话默认 **24 小时**（`FEDERATION_SESSION_TTL_HOURS` / `federation.security.session-ttl-hours`）；有请求时自动续期，闲置满 TTL 需重新登录。
- 库表 `fed_user_session` 只存令牌的 SHA-256；登出、改密、禁用账号、管理员重置密码都会让相关会话立刻失效。
- 同一用户名连续登录失败 5 次，锁定 15 分钟。

## 首个管理员

```bash
export FEDERATION_ADMIN_USER=admin
export FEDERATION_ADMIN_PASSWORD='……至少 8 位……'
```

启动时若该用户名还不存在则创建，并赋予 `ADMIN,QUERY`。账号已存在则不动（不会覆盖在控制台改过的密码）。

## 管理员维护

`GET/PUT /api/v1/users`、`POST /api/v1/users/{id}/password`（需 `ADMIN`）：列表、改显示名 / 角色 / 状态、重置密码。不能禁用自己，也不能去掉自己的管理员角色。

普通用户改自己的密码：`POST /api/v1/auth/password`（需本地账号会话）。

## 控制台

鉴权开启且未登录时显示登录 / 注册页。任一接口返回 401 时：先试 OIDC 刷新令牌，不行则清令牌回登录页。管理员侧栏多「用户」模块。访问令牌粘贴与 OIDC 收在「其他登录方式」。

## 与问数偏好的关系

问数个人补充提示词（`USER.md`）按 `GatewayUser.current().name()` 归属；登录后不同用户互不可见，全局提示词与知识库仍共享。
对话历史同样按用户隔离：`GET /api/v1/ask/conversations` 只返回当前用户的会话，控制台左侧「历史」可回看并继续追问。
