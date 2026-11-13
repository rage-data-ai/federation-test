# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite / NVIDIA Auto Ontology 等成熟开源实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 17 轮（进行中）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**：answerable、码值过滤校验、sqlCondition/sqlExpression、certified、steps、编辑距离 / n-gram / **本地哈希嵌入召回**。
3. **安全栈**：Bearer + RBAC + JWT + PKCE + ACL + 行级过滤 + denylist（内存 / 文件 / **Redis**）+ back-channel。
4. **工程门禁**：CI、JaCoCo、Spotless、SpotBugs（High）、ESLint、Vitest、全量 vue-tsc；`CODE_OF_CONDUCT.md`。
5. **本体运维**：版本快照/回滚（API + 控制台）、certify UI、起草前 checkpoint。
6. **可访问性**：skip-link、main landmark、问数 aria-live。

## 已关闭差距（累计至第 17 轮）

| 问题 | 状态 |
|------|------|
| 安全 / ACL / 行级 / denylist / Discovery | ✅ |
| 问数 answerable / filter / steps / sqlExpression | ✅ |
| 全量 vue-tsc | ✅ |
| 本体版本回滚 + 控制台 UI | ✅ |
| certify 治理标志 + 控制台 UI | ✅ |
| OntologyStore 无 Docker 单测 | ✅ |
| 近邻词模糊检索 + n-gram | ✅ |
| SpotBugs High | ✅ |
| 控制台 a11y 基础 | ✅ |
| **本地向量式术语召回（哈希嵌入）** | ✅ |
| **Redis 令牌 denylist** | ✅ |

## 距顶尖开源仍待项

### P1

| # | 问题 | 建议 |
|---|------|------|
| 1 | 无外接真 embedding / pgvector 持久索引 | 可选 NIM EMBED API 或 pgvector 替换本地哈希 |

### P2

视觉回归、更广 a11y、Error Prone（需 JDK 模块导出）、OpenAPI/springdoc。

## 总评（第 17 轮）

术语召回已具备「向量检索」工程形态（固定维嵌入 + 余弦），Redis denylist 补齐多实例吊销。距顶尖开源仍差外接语义模型与体验/文档门禁深化。目标在证据充分前**不标记完成**。

验证：`mvn -B verify` + `cd web && npm run lint && npm run typecheck && npm test && npm run build`。
