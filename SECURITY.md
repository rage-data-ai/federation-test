# Security Policy

## 报告漏洞

请通过仓库 Issues（或私有渠道）报告安全问题，并标注 `security`。请勿在公开 Issue 中粘贴密钥、令牌或生产连接串。

## 部署建议

1. 生产环境设置 `FEDERATION_SECURITY_ENABLED=true`，并为每个调用方配置独立 Bearer token（`federation.security.tokens`）。
2. 通过 `federation.security.roles` 分配角色：`QUERY`（查询/问数）、`ADMIN`（数据源/元数据/本体/模型写入）。未配置 roles 的用户默认拥有全部角色。
2b. 可选 `federation.security.catalogs`（用户 → catalog 列表或 `*`）：非空时启用资源 ACL，限制列表/查询/元数据可见范围；`ADMIN` 绕过。
3. 可选配置 `FEDERATION_JWT_ISSUER_URI`，接受外部 IdP 签发的 JWT（roles claim 默认 `roles`）。控制台可通过 PKCE 登录（见 `docs/oidc.md`）或粘贴 access_token。
4. 使用强随机 token；不要使用仓库示例值。
5. `FEDERAL_DB_*`、`LLM_API_KEY`、数据源密码仅通过环境变量或密钥管理系统注入。
6. 将 CORS 收紧为实际前端来源（`FEDERATION_CORS_ORIGINS`）。
7. 网关默认对 500 错误脱敏，并以 RFC 7807 Problem Details 返回；客户端可见的业务错误仍可能包含 SQL 失败原因，请勿把网关直接暴露到不可信公网而不加鉴权与网络隔离。
8. 可选配置 `OTEL_EXPORTER_OTLP_ENDPOINT` 导出分布式追踪。

## 已知边界

- 支持共享 Bearer、JWT、控制台 PKCE（含 refresh_token）、catalog/表级 ACL；尚未实现 IdP 注销联动或行级谓词注入。
- 问数路径依赖 `SqlGuard` 与表白名单；`/api/v1/query` 在具备 `QUERY` 后仍可执行任意只读 SQL（相对已注册 catalog），需配合配额与审计。
