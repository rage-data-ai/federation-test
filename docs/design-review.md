# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite / NVIDIA Auto Ontology 等成熟开源实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 19 轮（进行中）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**：answerable、filter、sqlExpression、certified、steps；本地/HTTP 嵌入 + **持久术语向量索引** + n-gram / 编辑距离。
3. **安全栈**：Bearer + RBAC + JWT + PKCE + ACL + 行级 + denylist（内存/文件/Redis）。
4. **工程门禁**：CI、JaCoCo、Spotless、SpotBugs、springdoc、ESLint、Vitest、vue-tsc；CoC / CHANGELOG / **ADR**。
5. **本体运维**：版本 UI、certify UI、checkpoint。
6. **可访问性**：skip-link、main、aria-live、数据源 SQL 标签。

## 已关闭差距（累计至第 19 轮）

| 问题 | 状态 |
|------|------|
| 安全 / denylist Redis / Discovery | ✅ |
| 问数护栏 + HTTP/哈希嵌入 | ✅ |
| **术语向量持久索引（落盘缓存）** | ✅ |
| SpotBugs / OpenAPI / vue-tsc / 版本·certify / a11y 基础 | ✅ |
| **ADR：术语嵌入策略** | ✅ |

## 距顶尖开源仍待项

### P2

视觉回归、更广 a11y 审计、Error Prone、可选真 pgvector 后端替换落盘格式。

## 总评（第 19 轮）

问数术语召回链路（可插拔嵌入 + 持久索引）已对齐 Auto Ontology 工程形态。剩余多为体验与静态分析深化。目标在证据充分前**不标记完成**。

验证：`mvn -B verify` + web lint/typecheck/test/build。
