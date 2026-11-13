# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite / NVIDIA Auto Ontology 等成熟开源实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 26 轮

## 第 25 → 26 轮开放项 / 本轮处置

| 项 | 本轮处置 |
|----|----------|
| 人工 AT 无法自动化替代 | ✅ Playwright + axe（serious/critical）+ `docs/a11y-checklist.md`；亮色 `--muted` 对比度与状态点 `role=img`；**真人 AT 仍开** |
| 运维可观测性（问数嵌入状态） | ✅ `/api/v1/health` 暴露 embedStore / embedEndpointConfigured / pgvector* 信号（无密钥） |
| README/CHANGELOG/本地门禁脚本滞后 | ✅ Node 22、health 文档、`scripts/verify-all.sh`、CHANGELOG |

## 第 25 轮已关闭的 P3（回顾）

1. pgvector IVFFlat/HNSW：可选，建索失败降级精确扫描。  
2. eslint moderate transitive：`minimatch`/`tinypool` overrides；生产 audit 0；vitest@3 两条 moderate 文档接受。  
3. 问数关键路径 ARIA/e2e 断言；design-review 标明需人工 AT。

## 优点

1. 包边界清晰；问数护栏与三层术语召回（本地 / 联邦 / 可选 pgvector ANN）。
2. 安全栈完整；SECURITY.md 含依赖风险策略。
3. CI ubuntu-24.04 + 质量插件 + axe/Playwright + Dependabot。
4. 文档：设计复评、ADR、OIDC、a11y 人工清单、一键 verify。

## 已关闭差距（累计至第 26 轮）

至第 25 轮累计项 + **axe 扫描** + **health 问数信号** + **文档/脚本对齐** + **亮色对比度/状态点 ARIA**。

## 距顶尖开源仍待项

### P3 / 人工

屏幕阅读器端到端**真人**验收（见 `docs/a11y-checklist.md`）。自动化 axe **不能**关闭此项。

### P3 / 可选后续

vitest 5 迁移以清剩余 2 条 moderate；发布/版本自动化。

## 总评（第 26 轮）

工程化与可观测性再抬一档；Goal 在真人 AT 与更广发布工程完成前**不标记完成**。

## 验证证据（第 26 轮）

- `mvn -B verify`：BUILD SUCCESS（含 HealthControllerWebTest standalone MockMvc）
- `web`：lint / typecheck / test / build / `test:e2e`（8 passed，含 axe）
- `npm audit --omit=dev`：0；全量 audit：2 moderate（vitest@3，已文档接受）
