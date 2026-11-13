# Contributing

## 开发环境

- JDK 21、Maven 3.9+
- Node 22+（控制台；与 CI 对齐）
- MySQL 作为 `federal` 配置库（见 `.env.example`）
- 可选 Docker：Jdbc*StoreIT / PgvectorTermRetrieverIT 会在有 Docker 时跑 Testcontainers

## 提交流程

1. 从 `main` 拉分支，命名建议 `cursor/<topic>-<id>`。
2. 本地门禁（与 CI 对齐）：
   - 后端：`mvn -B verify`
   - 前端：`cd web && npm ci && npm run lint && npm run typecheck && npm test && npm run build`
   - 有 UI 变更时：`cd web && npm run test:e2e`（需本机 Chromium）
3. 保持文档与行为一致（尤其是 README / `docs/ontology-design.md` / 鉴权说明 / `SECURITY.md`）。
4. 提交信息用现在时、说明「为什么」；PR 描述写清动机与验证方式。

## 代码约定

- Java：包按职责划分；新增能力优先接到现有管线（`QueryService` / `AskService`），避免半成品死代码。
- 配置：禁止硬编码内网地址或密钥；使用环境变量或 profile。
- 测试：核心路由、改写、鉴权、SqlGuard、术语召回变更需带单测。
- 无障碍：新增对话框必须 `role="dialog"` + `aria-modal` + `useFocusTrap`（Escape 关闭）。

## 安全与依赖

- 勿在 PR / 日志中提交密钥；见 `SECURITY.md`。
- Dependabot 周更 Maven/npm、月更 Actions；分组 PR 优先整组合并并跑满门禁。
