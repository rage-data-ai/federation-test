# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite 等成熟开源项目的工程与架构实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 12 轮（进行中）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**与联邦路由设计正确。
3. **安全栈**：Bearer + RBAC + JWT + PKCE（refresh/注销）+ catalog/表 ACL + **Calcite 行级 WHERE 注入（JOIN 别名限定）** + **可插拔令牌 denylist**（内存 / 共享文件）+ **back-channel logout**；Prometheus + OTLP。
4. **工程门禁**：CI、`mvn verify`（JaCoCo ≥75%/70%）、Spotless、ESLint、Vitest、`tsc` + `vue-tsc`。
5. **前端**：逻辑层 TypeScript；主要 SFC 已标 `lang="ts"`；共享 `types.ts` 收敛中。

## 已关闭差距（累计至第 12 轮）

| 问题 | 状态 |
|------|------|
| 管线 / 鉴权 / RBAC / JWT / PKCE / refresh / IdP 注销跳转 | ✅ |
| catalog / 表级 ACL | ✅ |
| 行级谓词自动注入（Calcite WHERE） | ✅ |
| **JOIN 别名限定行级谓词** | ✅（`RowFilterService.qualifyPredicate` + 测试） |
| 令牌吊销 denylist（`/auth/logout` `/auth/revoke`） | ✅ |
| **跨实例 denylist（文件 Store）+ back-channel logout** | ✅（`TokenRevocationStore` / `revocation-store-path` / `/auth/backchannel-logout`） |
| RFC 7807 · `/auth/me` | ✅ |
| composable TS · SFC `lang="ts"` | ✅ |
| WebMvc + Engine + Store IT | ✅ |
| 覆盖率 ≥75% | ✅（本轮约 78% instruction） |

## 距顶尖开源仍待项

### P1

| # | 问题 | 建议 |
|---|------|------|
| 1 | vue-tsc 尚未对全部 `.vue` 零错误通过 | 扩大 include 后系统性补类型（本轮进行中） |
| 2 | store 无 Docker 单测为 0% | 接受 IT-only 或抽纯逻辑单测 |

### P2

本体版本回滚、a11y、Error Prone、OIDC Discovery、视觉回归、Redis 版 `TokenRevocationStore`（文件 Store 已覆盖多实例共享卷场景）。

## 总评（第 12 轮）

安全与联邦查询路径已达严肃开源网关水准；本轮补齐 JOIN 行级消歧与跨实例吊销。剩余阻塞顶尖标签的主要是**全量 Vue SFC 严格类型检查**。目标在证据充分前**不标记完成**。

验证：`mvn -B verify` + `cd web && npm run lint && npm run typecheck && npm test && npm run build`。
