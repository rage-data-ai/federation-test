# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite / NVIDIA Auto Ontology 等成熟开源实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 20 轮（进行中）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**：answerable、filter、sqlExpression、certified、steps；HTTP/哈希嵌入 + 持久术语索引（本体变更清缓存）。
3. **安全栈**：Bearer + RBAC + JWT + PKCE + ACL + 行级 + denylist（内存/文件/Redis）。
4. **工程门禁**：CI、JaCoCo、Spotless、SpotBugs、**Error Prone（窄规则）**、**CycloneDX SBOM**、springdoc、ESLint、Vitest、vue-tsc；CoC / CHANGELOG / ADR / Issue·PR 模板。
5. **本体运维**：版本 UI、certify UI、checkpoint。
6. **可访问性**：skip-link、main、aria-live、dialog `aria-modal`。

## 已关闭差距（累计至第 20 轮）

| 问题 | 状态 |
|------|------|
| 问数 / 嵌入 / 持久索引 / OpenAPI / Redis denylist | ✅ |
| SpotBugs / Error Prone / SBOM | ✅ |
| 版本·certify UI / a11y 基础深化 / 社区模板 | ✅ |

## 距顶尖开源仍待项

### P2

视觉回归（Playwright）、更完整键盘陷阱审计、可选真 pgvector 后端。

## 总评（第 20 轮）

工程门禁与问数语义召回已达严肃开源候选水位。剩余主要为视觉回归与可选向量库后端。目标在证据充分前**不标记完成**。

验证：`mvn -B verify`（含 Error Prone / SpotBugs / CycloneDX）+ web lint/typecheck/test/build。
