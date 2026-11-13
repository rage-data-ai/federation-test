# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite 等成熟开源项目的工程与架构实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 9 轮（同日）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**与联邦路由设计正确。
3. **安全栈**：Bearer + RBAC + JWT + 控制台 PKCE + catalog ACL；Prometheus + OTLP。
4. **工程门禁**：CI（含 Docker/Testcontainers）、`mvn verify`（JaCoCo ≥75% 指令 / ≥70% 行）、Spotless、ESLint（Vue+TS）、Vitest、`tsc` + `vue-tsc`。
5. **前端**：逻辑层 TypeScript；首个 SFC（AskAnswerClarify）`lang="ts"`。

## 已关闭差距（累计至第 8 轮）

| 问题 | 状态 |
|------|------|
| 管线 / 鉴权 / RBAC / JWT / PKCE / catalog·表 ACL | ✅ |
| refresh_token 换发 | ✅（控制台） |
| RFC 7807 · `/auth/me` | ✅ |
| 巨型 SFC / God Class · composable TS | ✅ |
| WebMvc（Query/Catalog/Auth/Ask/Ontology） | ✅ |
| Engine 单测 + Store IT（CI Docker） | ✅ |
| 覆盖率 ≥75% 指令 | ✅（约 78%） |

## 距顶尖开源仍待项

### P1

| # | 问题 | 建议 |
|---|------|------|
| 1 | IdP 注销 / back-channel logout | RP + 会话吊销 |
| 2 | 行级谓词注入 | SqlGuard / 改写层按用户属性注入 WHERE |
| 3 | 多数 `.vue` 仍无 `lang="ts"` | 全面迁移 + 扩大 vue-tsc include |
| 4 | store 包在无 Docker 单元跑为 0% | 接受 IT-only，或抽可测迁移逻辑 |

### P2

本体版本回滚、a11y、Error Prone、OIDC Discovery、视觉回归。

## 总评（第 9 轮）

已达到**接近顶尖开源协作的高水位**：身份联邦（JWT/PKCE/refresh）、角色与 catalog/表 ACL、错误契约、类型化前端逻辑与强制覆盖率均已扎实。  

距「顶尖」仍差 IdP 注销联动、行级谓词、Vue 全量 TS。目标在剩余 P1 关闭并证据充分前**不标记完成**。

验证：`mvn -B verify` + `cd web && npm run lint && npm run typecheck && npm test && npm run build`。
