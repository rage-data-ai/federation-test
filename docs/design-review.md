# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite 等成熟开源项目的工程与架构实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 11 轮（同日）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**与联邦路由设计正确。
3. **安全栈**：Bearer + RBAC + JWT + PKCE（refresh/注销）+ catalog/表 ACL + **Calcite 行级 WHERE 注入** + **令牌 denylist**；Prometheus + OTLP。
4. **工程门禁**：CI、`mvn verify`（JaCoCo ≥75%/70%）、Spotless、ESLint、Vitest、`tsc` + `vue-tsc`。
5. **前端**：逻辑层 TypeScript；主要 SFC 已标 `lang="ts"`。

## 已关闭差距（累计至第 11 轮）

| 问题 | 状态 |
|------|------|
| 管线 / 鉴权 / RBAC / JWT / PKCE / refresh / IdP 注销跳转 | ✅ |
| catalog / 表级 ACL | ✅ |
| 行级谓词自动注入（Calcite WHERE） | ✅ |
| 令牌吊销 denylist（`/auth/logout` `/auth/revoke`） | ✅ |
| RFC 7807 · `/auth/me` | ✅ |
| composable TS · SFC `lang="ts"` | ✅（vue-tsc 覆盖核心组件） |
| WebMvc + Engine + Store IT | ✅ |
| 覆盖率 ≥75% | ✅ |

## 距顶尖开源仍待项

### P1

| # | 问题 | 建议 |
|---|------|------|
| 1 | back-channel logout 标准端点与跨实例 denylist | OIDC back-channel + Redis |
| 2 | 行级注入对多表 JOIN 别名消歧 | 谓词强制限定表别名 |
| 3 | vue-tsc 尚未覆盖全部 `.vue` | 扩大 `tsconfig.app.json` include |
| 4 | store 无 Docker 单测为 0% | 接受 IT-only |

### P2

本体版本回滚、a11y、Error Prone、OIDC Discovery、视觉回归。

## 总评（第 11 轮）

已达到**顶尖开源候选的高水位**，核心安全与质量门禁接近严肃开源生产网关。剩余主要是分布式吊销、JOIN 别名消歧与全量 Vue 类型检查。目标在证据充分前**不标记完成**。

验证：`mvn -B verify` + `cd web && npm run lint && npm run typecheck && npm test && npm run build`。
