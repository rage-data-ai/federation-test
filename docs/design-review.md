# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite 等成熟开源项目的工程与架构实践。  
评估日期：2026-11-13（Asia/Shanghai）  
代码规模：约 69 个主源文件 / ~8.6k LOC；最大类 `OntologyDraftService` 1767 行。

## 优点

1. **包边界清晰**：`api / ask / ontology / catalog / sql / engine / store / security / llm / metadata / vector` 分层可读；查询主路径直观。
2. **问数护栏设计正确**：`SqlGuard` 强制只读、表白名单、大表时间过滤与 Trino `EXPLAIN (TYPE VALIDATE)`；`InsightSession` 限制 SQL/恢复次数。
3. **本体域模型有结构**：Jena TDB2 + 词汇表、Repository、确定性工具检索与路径发现；`docs/ontology-design.md` 记录了设计决策。
4. **持久化抽象与密钥面有意识**：Store 接口 + JDBC 实现；`CatalogView` / LLM 视图不回传明文密钥。
5. **核心逻辑可测**：Ask / Ontology / SqlGuard / Analyzer 等有单元测试，部分服务支持包内构造注入，无需 Spring 容器。
6. **前端功能闭环**：数据源 / 本体 / 问数三模块 + SSE 过程展示，依赖极简（Vue 3 + Vite）。

## 缺点（按优先级）

### P0 — 正确性 / 安全 / 可移植

| # | 问题 | 证据 |
|---|------|------|
| 1 | 查询管线断路：Kafka 映射、向量召回、DIRECT 路由未接入 `QueryService` | `QueryService` 仅 analyze→route→Trino；`QueryRouter` 恒返回 TRINO |
| 2 | 鉴权未实现：`federation.security` 配置存在但无 Filter；Audit/Quota 无调用点 | `security` 包空转 |
| 3 | 500 异常原样外泄 | `GlobalExceptionHandler.failed` |
| 4 | 内网地址硬编码 | `application.yml` / `FederationProperties.Repository` 默认 `192.168.19.70` |
| 5 | 文档与实现偏离（鉴权、直连路由、catalog 配置位置） | README vs 代码 |

### P1 — 可维护性

| # | 问题 |
|---|------|
| 1 | God Class：`OntologyDraftService`（1767）、`OntologyTools`（646）、`OntologyRepository`（526） |
| 2 | `CatalogDataSourceRegistry` / DIRECT 引擎半成品；`CatalogRegistry` 强制 `route=TRINO` |
| 3 | API 错误契约不统一（`QueryResponse` / 本地 Map / SSE） |
| 4 | 结果缓存无用户维度 |

### P2 — 工程化

| # | 问题 |
|---|------|
| 1 | 无 CI、无静态分析、无 LICENSE / CONTRIBUTING / .editorconfig |
| 2 | 无网关 Dockerfile；Compose 仅 Trino |
| 3 | 可观测性弱（Actuator 仅 health/info，无业务指标） |
| 4 | 前端无测试 / 无 ESLint；巨型 SFC |
| 5 | 无 `@SpringBootTest` / MockMvc 主链路回归 |

## 对标顶尖开源的差距

安全默认可启用、查询管线与文档一致、12-factor 配置、CI 质量门禁、可观测、社区元数据、生产交付物（镜像/健康探针）、RFC 7807 错误模型、测试金字塔。

**总评：** 领域方向与局部子系统（SqlGuard、本体、起草流水线）达到扎实 PoC 水准；距可对外开源 / 生产联邦网关，差一整层「管线连通 + 安全接线 + 工程门禁 + 运维面」。

## 优化路线（本仓库持续迭代）

1. 接通查询执行管线（改写 / 向量 / 直连 / 路由）
2. 最小可用鉴权 + Audit/Quota + 异常脱敏
3. 配置外置与 README 纠偏
4. 拆分 God Class + CI / LICENSE / editorconfig
5. 可观测、Docker 一键体验、前端工程化与契约测试
