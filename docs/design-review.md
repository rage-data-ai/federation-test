# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite / NVIDIA Auto Ontology 等成熟开源实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 18 轮（进行中）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**：answerable、filter、sqlExpression、certified、steps、本地哈希嵌入 + **可选 HTTP/NIM embeddings**、n-gram / 编辑距离。
3. **安全栈**：Bearer + RBAC + JWT + PKCE + ACL + 行级 + denylist（内存/文件/Redis）+ back-channel。
4. **工程门禁**：CI、JaCoCo、Spotless、SpotBugs、**springdoc OpenAPI**、ESLint、Vitest、vue-tsc；CoC / CHANGELOG。
5. **本体运维**：版本 UI、certify UI、checkpoint。
6. **可访问性**：skip-link、main、aria-live。

## 已关闭差距（累计至第 18 轮）

| 问题 | 状态 |
|------|------|
| 安全 / ACL / 行级 / denylist / Discovery / Redis | ✅ |
| 问数护栏 + 本地/HTTP 嵌入召回 | ✅ |
| SpotBugs / vue-tsc / a11y 基础 / 版本·certify UI | ✅ |
| **OpenAPI（springdoc 3.2）** | ✅ |
| **可插拔 HTTP embedding（NIM 兼容）** | ✅ |

## 距顶尖开源仍待项

### P1

| # | 问题 | 建议 |
|---|------|------|
| 1 | 无 pgvector 持久术语索引 | 可选入库缓存 embedding，避免每次全量重算 |

### P2

视觉回归、更广 a11y、Error Prone。

## 总评（第 18 轮）

外接语义嵌入与 OpenAPI 文档门禁已落地，问数/工程面接近严肃开源水位。pgvector 持久索引与体验深化仍可继续。目标在证据充分前**不标记完成**。

验证：`mvn -B verify` + web lint/typecheck/test/build；Swagger UI `/swagger-ui.html`。
