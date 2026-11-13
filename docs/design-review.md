# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite 等成熟开源项目的工程与架构实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 7 轮（同日）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**（SqlGuard + InsightSession）与联邦路由设计正确。
3. **本体域模型**有结构；起草复杂度已模块化。
4. **安全栈完整度高**：Bearer + RBAC + JWT + 控制台 PKCE + **按 catalog 资源 ACL**；Prometheus + OTLP。
5. **工程门禁**：CI、`mvn verify`（JaCoCo ≥70% 指令）、Spotless、ESLint、Vitest、`tsc`。
6. **前端模块化 + TypeScript**：API / composable / examples 均为 `.ts`。

## 已关闭差距（累计至第 7 轮）

| 问题 | 状态 |
|------|------|
| 查询管线 / 鉴权 / 配额 / 审计 / RBAC | ✅ |
| JWT + OIDC PKCE + `/auth/me` | ✅ |
| 按 catalog 资源 ACL | ✅ `CatalogAclService` |
| RFC 7807 | ✅ |
| 巨型 SFC / God Class | ✅ |
| 前端逻辑 TypeScript | ✅ |
| WebMvc + Store IT | ✅ |
| JaCoCo 70% / 65% | ✅（当前指令约 72%） |

## 距顶尖开源仍待项

### P1

| # | 问题 | 建议 |
|---|------|------|
| 1 | 刷新令牌 / IdP 注销联动 | refresh + RP / back-channel logout |
| 2 | 行级 / 列级权限 | 在 SqlGuard / 改写层注入谓词 |
| 3 | `.vue` 仍为 JS script | `lang="ts"` + vue-tsc |
| 4 | 覆盖率目标 75%+（现 ~72%） | engine / metadata / FederalDataSource |
| 5 | CI 未强制 Docker | GitHub Actions service container 跑 IT |

### P2

本体版本回滚、a11y、Error Prone、OIDC Discovery 自动解析、视觉回归。

## 总评（第 7 轮）

已达到**严肃开源中后期候选水位**：安全模型覆盖身份、角色与数据源范围；错误与身份 API 契约清晰；前端逻辑层类型化；测试与格式门禁可强制。  

距「顶尖」仍差会话生命周期、行列级授权、Vue SFC 全量 TS、更高覆盖率与强制容器 CI。目标在上述 P1 关闭并证据充分前**不标记完成**。

验证：`mvn -B verify`（127 tests）+ 前端 lint/typecheck/test/build。
