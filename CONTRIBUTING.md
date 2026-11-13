# Contributing

## 开发环境

- JDK 21、Maven 3.9+
- Node 20+（控制台）
- MySQL 作为 `federal` 配置库（见 `.env.example`）

## 提交流程

1. 从 `main` 拉分支，命名建议 `cursor/<topic>`。
2. 本地执行：`mvn -B test`；若改动前端：`cd web && npm ci && npm run build`。
3. 保持文档与行为一致（尤其是 README 路由/鉴权说明）。
4. 提交信息用现在时、说明「为什么」；PR 描述写清动机与验证方式。

## 代码约定

- Java：包按职责划分；新增能力优先接到现有管线（`QueryService` / `AskService`），避免半成品死代码。
- 配置：禁止硬编码内网地址或密钥；使用环境变量或 profile。
- 测试：核心路由、改写、鉴权、SqlGuard 变更需带单测。
