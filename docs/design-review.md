# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite 等成熟开源项目的工程与架构实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 2 轮（同日）

代码规模：主源约 8.6k+ LOC；`OntologyDraftService` 已从 2432 行降至约 834 行编排器 + `ontology.draft.*` 协作类。

## 优点

1. **包边界清晰**：`api / ask / ontology / catalog / sql / engine / store / security / llm / metadata / vector` 分层可读。
2. **问数护栏设计正确**：`SqlGuard` 强制只读、表白名单、大表时间过滤与 Trino 预校验；`InsightSession` 限制预算。
3. **本体域模型有结构**：Jena TDB2 + 词汇表、Repository、确定性工具检索；设计文档已与实现对齐。
4. **持久化抽象与密钥面有意识**：Store 接口 + JDBC；视图不回传明文密钥。
5. **核心逻辑可测**：路由、改写、鉴权、问数、本体起草等单测（当前约 104 个）。
6. **前端功能闭环**：数据源 / 本体 / 问数 + SSE；依赖极简。

## 第一轮已关闭的 P0/P1

| 原问题 | 状态 |
|--------|------|
| 查询管线断路（Kafka / vector / DIRECT） | ✅ `QueryService` 全链路接通；`QueryRouter` 单源直连 |
| 鉴权 / Audit / Quota 空转 | ✅ `BearerAuthFilter` + 入口接线 |
| 500 异常外泄 | ✅ 脱敏 + 日志 |
| 内网地址硬编码 | ✅ `FEDERAL_DB_*` 必填；提供 `.env.example` / `application-dev.yml` |
| 文档与实现偏离 | ✅ README / ontology-design 状态已纠偏 |
| 无 CI / LICENSE / editorconfig | ✅ |
| God Class `OntologyDraftService` | ✅ 拆为 `DraftJson` / `Heuristics` / `Descriptions` / `Dimensions` / `Applier` |
| 无网关镜像 / Compose 不完整 | ✅ Dockerfile 多阶段 + Compose（mysql/trino/gateway） |
| CORS 写死 | ✅ 可配置 `FEDERATION_CORS_ORIGINS` |

## 第二轮增量

- Prometheus：`/actuator/prometheus`，业务指标 `federation.query` / `federation.query.latency`
- 前端 Vitest：`api.js` 请求错误与 SSE 解析
- Compose 一键：`mysql` + `trino` + `gateway`（含 UI）

## 仍待达到顶尖开源水准的差距

### P1（下一轮优先）

| # | 问题 | 建议 |
|---|------|------|
| 1 | `OntologyTools`（~722）、`OntologyRepository`（~821）仍偏大 | 按查询面 / 写入面或 RDF 适配层再拆 |
| 2 | 鉴权仅为共享 Bearer，无 RBAC / OAuth2 | 引入 Spring Security 资源服务器或网关前置 IdP |
| 3 | 尚无分布式 tracing | OpenTelemetry / Micrometer Tracing |
| 4 | 前端无 ESLint/TS；巨型 SFC | ESLint + 渐进 TS；拆 Datasource/Ontology 视图 |
| 5 | 无集成测试（MockMvc / Testcontainers） | 对鉴权与 `/query` `/explain` 加切片测试 |

### P2

| # | 问题 |
|---|------|
| 1 | 静态分析门禁（Spotless / SpotBugs / Error Prone）与覆盖率门槛 |
| 2 | Dependabot / Renovate |
| 3 | 巨型 Vue SFC 拆分与 a11y |
| 4 | RFC 7807 Problem Details 统一错误模型 |
| 5 | 本体版本管理 / 回滚（设计已知风险） |

## 总评（复评）

已从「功能密集 PoC」提升到「可对外演示、可 CI 协作的早期开源项目」水位：管线与文档一致、安全可开关、配置可移植、起草复杂度已模块化、交付物含镜像。

距 Apache/CNCF 级「顶尖开源」仍差：身份模型、可观测、前端工程化、集成测试金字塔与静态分析门禁。按上表 P1 继续迭代即可收敛。
