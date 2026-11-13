# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite 等成熟开源项目的工程与架构实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 10 轮（同日）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**与联邦路由设计正确。
3. **安全栈**：Bearer + RBAC + JWT + 控制台 PKCE（refresh / 注销跳转）+ catalog/表 ACL + 行级启发式门禁；Prometheus + OTLP。
4. **工程门禁**：CI（Docker/Testcontainers）、`mvn verify`（JaCoCo ≥75%/70%）、Spotless、ESLint（Vue+TS）、Vitest、`tsc` + `vue-tsc`。
5. **前端**：逻辑层 TypeScript；`AskAnswerClarify` 已 `lang="ts"`。

## 已关闭差距（累计至第 10 轮）

| 问题 | 状态 |
|------|------|
| 管线 / 鉴权 / RBAC / JWT / PKCE | ✅ |
| refresh_token + IdP 注销跳转 | ✅ |
| catalog / 表级 ACL | ✅ |
| 行级启发式过滤 | ✅ `RowFilterService` |
| RFC 7807 · `/auth/me` | ✅ |
| 巨型 SFC / God Class · composable TS | ✅ |
| WebMvc + Engine 单测 + Store IT | ✅ |
| 覆盖率 ≥75% 指令 | ✅（约 78%+） |

## 距顶尖开源仍待项

### P1

| # | 问题 | 建议 |
|---|------|------|
| 1 | back-channel logout / 令牌吊销列表 | OIDC back-channel + denylist |
| 2 | AST 级行级谓词自动注入 | Calcite 改写 WHERE |
| 3 | 多数 `.vue` 仍无 `lang="ts"` | 全面迁移 + 扩大 vue-tsc include |
| 4 | store 包在无 Docker 单元跑为 0% | 接受 IT-only，或抽可测迁移逻辑 |

### P2

本体版本回滚、a11y、Error Prone、OIDC Discovery、视觉回归。

## 总评（第 10 轮）

已达到**顶尖开源候选水位**。距宣称「顶尖完成」仍差 back-channel 吊销、AST 级行级注入、Vue 全量 TS。目标在上述项关闭并证据充分前**不标记完成**。

验证：`mvn -B verify` + `cd web && npm run lint && npm run typecheck && npm test && npm run build`。
