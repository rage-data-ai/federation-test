# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite 等成熟开源项目的工程与架构实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 4 轮（同日）

## 优点

1. **包边界清晰**：`api / ask / ontology / catalog / sql / engine / store / security / llm / metadata / vector` 分层可读。
2. **问数护栏设计正确**：`SqlGuard` + `InsightSession` 预算限制。
3. **本体域模型有结构**：Jena TDB2 + 词汇表、Repository、确定性工具检索。
4. **持久化抽象与密钥面有意识**：Store 接口；视图不回传明文密钥。
5. **核心逻辑可测**：单测 + MockMvc 切片；JaCoCo 门禁（指令 ≥60%、行 ≥55%）。
6. **前端功能闭环且已开始模块化**：数据源 / 本体 / 问数；composable + 子组件拆分。

## 已关闭差距（累计）

| 问题 | 状态 |
|------|------|
| 查询管线断路 | ✅ |
| 鉴权 / Audit / Quota 空转 | ✅ Bearer + 审计/配额 |
| 粗粒度 RBAC | ✅ `QUERY` / `ADMIN` + `@RequireRole` |
| 500 异常外泄 | ✅ |
| 内网硬编码 | ✅ |
| God Class（Draft/Tools/Repository） | ✅ 拆分 |
| 巨型 Vue SFC | ✅ DatasourceView / OntologyView 抽出 composable |
| CI / LICENSE / Dependabot | ✅ |
| Docker Compose 一键 | ✅ |
| Prometheus 指标 | ✅ |
| OTLP Tracing | ✅ Micrometer OTel bridge（endpoint 可配） |
| Spotless / JaCoCo 门槛 | ✅ `mvn verify` |
| MockMvc 切片测试 | ✅ `QueryControllerWebTest` |
| 前端 ESLint / Vitest | ✅ |

## 距顶尖开源仍待项

### P1

| # | 问题 | 建议 |
|---|------|------|
| 1 | 非完整 OAuth2/OIDC | Spring Authorization Server / 外部 IdP 资源服务器 |
| 2 | 无 Testcontainers 端到端 | MySQL +（可选）Trino 容器化回归 |
| 3 | 前端仍有大 SFC、无 TypeScript | 继续拆 AskView/OntologyGraph；渐进 TS |
| 4 | 覆盖率阈值可再抬高 | 补 MetadataService / FederalDataSource 测试后提到 70%+ |
| 5 | 统一 Problem Details | RFC 7807 替换异构错误体 |

### P2

本体版本/回滚、a11y 深化、Error Prone、前端视觉回归测试。

## 总评（第 4 轮）

已从 PoC 提升到**可协作、可门禁、可观测、带 RBAC 的早期生产候选**水位。  
距 Apache/CNCF「顶尖开源」仍差完整身份联邦、容器化 E2E 与更高覆盖率/类型化前端——但主架构与工程基线已对齐一流项目的主干实践。

验证证据（本轮）：`mvn -B verify` → 111 tests、JaCoCo check、Spotless check 通过；`cd web && npm run lint && npm test && npm run build` 通过。
