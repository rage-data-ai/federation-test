# Security Policy

## 报告漏洞

请通过仓库 Issues（或私有渠道）报告安全问题，并标注 `security`。请勿在公开 Issue 中粘贴密钥、令牌或生产连接串。

## 部署建议

1. 生产环境设置 `FEDERATION_SECURITY_ENABLED=true`，并为每个调用方配置独立 Bearer token（`federation.security.tokens`）。
2. 使用强随机 `FEDERATION_DEMO_TOKEN` / 自定义 token；不要使用仓库示例值。
3. `FEDERAL_DB_*`、`LLM_API_KEY`、数据源密码仅通过环境变量或密钥管理系统注入。
4. 将 CORS 收紧为实际前端来源（`FEDERATION_CORS_ORIGINS`）。
5. 网关默认对 500 错误脱敏；客户端可见的业务错误仍可能包含 SQL 失败原因，请勿把网关直接暴露到不可信公网而不加鉴权与网络隔离。

## 已知边界

- 当前鉴权为共享 Bearer token 模型，不是完整的 OAuth2 / RBAC。
- 问数路径依赖 `SqlGuard` 与表白名单；原始 `/api/v1/query` 在鉴权开启后仍可执行任意只读 SQL（相对已注册 catalog），需配合配额与审计。
