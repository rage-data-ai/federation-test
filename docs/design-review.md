# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite / NVIDIA Auto Ontology 等成熟开源实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 25 轮（进行中）

## 第 24 → 25 轮开放项清单

| 开放项 | 本轮处置 |
|--------|----------|
| pgvector IVFFlat/HNSW | ✅ 可配 `hnsw`/`ivfflat`/`none`；建索引失败 → 精确扫描 |
| eslint moderate transitive | ✅ 钉 `minimatch@^11`；剩余 eslint/vitest 工具链 **文档接受风险**（仅 dev） |
| 屏幕阅读器 AT | ✅ 问数关键路径 ARIA + e2e 语义断言；**仍需人工 AT（VoiceOver/NVDA）验收** |

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**：嵌入 file/JDBC/both + 可选 pgvector（HNSW/IVFFlat 可降级）。
3. **安全栈** + SECURITY.md（含依赖风险声明）。
4. **工程门禁**：CI ubuntu-24.04、质量插件、Dependabot 分组、Playwright。
5. **可访问性**：对话框焦点陷阱；问数 log/composer/samples ARIA；e2e 覆盖关键语义。

## 已关闭差距（累计至第 25 轮）

| 问题 | 状态 |
|------|------|
| 至第 24 轮累计项 | ✅ |
| **pgvector ANN（HNSW/IVFFlat）+ 降级** | ✅ |
| **dev 依赖 CVE 策略（overrides + 接受剩余 moderate）** | ✅ |
| **问数关键路径 ARIA/e2e（人工 AT 仍开）** | ✅ 工程侧；人工 AT 待办 |

## 距顶尖开源仍待项

### P3 / 人工

**屏幕阅读器端到端人工验收**（VoiceOver / NVDA / TalkBack）：需真人走问数、起草、鉴权对话框；自动化无法替代。

## 总评（第 25 轮）

可自动落地的 P3 已收口；唯一明确残留是人工 AT 验收。目标在证据充分前**不标记完成**。

验证：`mvn -B verify` + `cd web && npm run lint && npm run typecheck && npm test && npm run build && npm run test:e2e`。
