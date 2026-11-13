# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite 等成熟开源项目的工程与架构实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 5 轮（同日）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**（SqlGuard + InsightSession）与联邦路由设计正确。
3. **本体域模型**有结构；起草复杂度已模块化。
4. **安全与可观测**：Bearer + RBAC + 可选 JWT；Prometheus + OTLP。
5. **工程门禁**：CI、`mvn verify`（JaCoCo ≥65% 指令 / Spotless）、ESLint、Vitest、`tsc`。
6. **前端模块化**：数据源 / 本体 / 问数均已抽出 composable；API 层 TypeScript。

## 已关闭差距（累计至第 5 轮）

| 问题 | 状态 |
|------|------|
| 查询管线断路 | ✅ |
| 鉴权 / 配额 / 审计 | ✅ |
| 粗粒度 RBAC | ✅ |
| 可选外部 IdP JWT | ✅ `FEDERATION_JWT_ISSUER_URI` |
| RFC 7807 错误模型 | ✅ Problem Details（兼容 `message`） |
| 500 脱敏 / 配置外置 | ✅ |
| God Class 拆分 | ✅ |
| 巨型 Vue SFC（三视图） | ✅ |
| API TypeScript | ✅ `web/src/api.ts` |
| CI / Docker / Compose / LICENSE | ✅ |
| Spotless / JaCoCo 门槛抬升 | ✅ 65% / 60% |
| MockMvc + Testcontainers | ✅ |
| 前端 ESLint / Vitest / tsc | ✅ |

## 距顶尖开源仍待项

### P1

| # | 问题 | 建议 |
|---|------|------|
| 1 | 无完整 OIDC 登录/授权码与会话 | 控制台接入 IdP；网关纯资源服务器 |
| 2 | Testcontainers 覆盖面仍窄 | 元数据采集 / 应用启动切片 |
| 3 | Vue SFC 与 composable 仍为 JS | 渐进迁移 `.vue` script 与 composable 到 TS |
| 4 | 覆盖率仍可抬至 75%+ | FederalDataSource / OntologyController 等 |
| 5 | 细粒度资源 ACL / 行级权限 | 按 catalog/表授权 |

### P2

本体版本回滚、a11y、Error Prone、视觉回归、前端 Storybook。

## 总评（第 5 轮）

已达到**可对外开源协作的坚实中早期水位**：安全模型可扩展到 JWT、错误契约标准化、前端核心 API 类型化、质量门禁可强制执行。  

距「顶尖」仍差完整身份联邦产品化、更广容器化 E2E 与全仓 TypeScript——但若以「能否作为严肃开源项目维护」为标准，主干工程实践已对齐。目标需在剩余 P1 关闭并复评通过后才能宣称完成。

验证：本地 `mvn -B verify` + `cd web && npm run lint && npm run typecheck && npm test && npm run build`。
