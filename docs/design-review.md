# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite 等成熟开源项目的工程与架构实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 6 轮（同日）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**（SqlGuard + InsightSession）与联邦路由设计正确。
3. **本体域模型**有结构；起草复杂度已模块化。
4. **安全与可观测**：Bearer + RBAC + JWT 资源服务器；控制台 PKCE / 令牌粘贴；Prometheus + OTLP。
5. **工程门禁**：CI、`mvn verify`（JaCoCo ≥70% 指令）、Spotless、ESLint、Vitest、`tsc`。
6. **前端模块化 + TypeScript**：核心 composable / API / examples 均已 `.ts`；错误契约读 `detail||message`。

## 已关闭差距（累计至第 6 轮）

| 问题 | 状态 |
|------|------|
| 查询管线断路 | ✅ |
| 鉴权 / 配额 / 审计 | ✅ |
| 粗粒度 RBAC | ✅ |
| JWT + 控制台 OIDC PKCE | ✅ `docs/oidc.md` · `/api/v1/auth/me` |
| RFC 7807 错误模型 | ✅ |
| God Class / 巨型 Vue SFC | ✅ |
| API + composable TypeScript | ✅ |
| CI / Docker / Compose / LICENSE | ✅ |
| Catalog / Auth WebMvc 测试 | ✅ |
| Store IT 扩面（元数据/LLM） | ✅（无 Docker 时跳过） |
| Spotless / JaCoCo | ✅ 70% / 65% |

## 距顶尖开源仍待项

### P1

| # | 问题 | 建议 |
|---|------|------|
| 1 | 刷新令牌 / 注销与 IdP 会话联动 | refresh + back-channel logout |
| 2 | 细粒度资源 ACL / 行级权限 | 按 catalog/表授权 |
| 3 | `.vue` 仍为 JS script | `lang="ts"` + vue-tsc |
| 4 | 覆盖率目标 75%+（现 ~72% 指令） | engine / metadata / FederalDataSource IT |
| 5 | 无 Docker 时 store 包单测覆盖仍为 0 | H2 兼容层或 Testcontainers 强制 CI 服务 |

### P2

本体版本回滚、a11y、Error Prone、视觉回归、OIDC Discovery 文档自动解析。

## 总评（第 6 轮）

已达到**可认真对外协作的开源中期水位**：身份可接 IdP、错误与 API 契约标准化、前端逻辑层 TypeScript 化、WebMvc + 可选容器 IT、质量门禁可强制。  

距「顶尖」仍差资源级 ACL、会话生命周期完整化、Vue SFC 全量 TS，以及更高覆盖率与强制容器 CI。目标在剩余 P1 关闭并证据充分前**不标记完成**。

验证：`mvn -B verify`（124 tests）+ `cd web && npm run lint && npm run typecheck && npm test && npm run build`（11 tests）。
