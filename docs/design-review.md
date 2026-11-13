# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite / NVIDIA Auto Ontology 等成熟开源实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 27 轮

## 第 26 → 27 轮开放项 / 本轮处置

| 项 | 本轮处置 |
|----|----------|
| vitest@3 剩余 2 条 moderate | ✅ 升级 `vitest@5.1`；`npm audit` 全量 0；声明 `engines.node>=22.12` |
| 发布/版本自动化 | ✅ `release.yml`（`v*` 标签 → jar + SBOM Release）+ `docs/RELEASING.md` |
| CI 生产依赖审计缺口 | ✅ frontend job 增加 `npm audit --omit=dev` |
| 人工 AT | ⚠️ 仍开（见 `docs/a11y-checklist.md`） |

## 优点

1. 包边界清晰；问数护栏与三层术语召回（本地 / 联邦 / 可选 pgvector ANN）。
2. 安全栈完整；依赖 audit 清零；Dependabot 分组。
3. CI ubuntu-24.04 + axe/Playwright + 生产 audit + 质量插件；Release 工作流。
4. 文档：设计复评、ADR、OIDC、a11y、RELEASING、一键 verify。

## 已关闭差距（累计至第 27 轮）

至第 26 轮累计项 + **vitest 5 / audit 0** + **Release 自动化** + **CI npm audit --omit=dev**。

## 距顶尖开源仍待项

### P3 / 人工

屏幕阅读器端到端**真人**验收（见 `docs/a11y-checklist.md`）。自动化 **不能**关闭此项。

### P3 / 可选后续

语义化版本从 `1.0-SNAPSHOT` 切正式版并打首个 `v*` 标签（流程已就绪，需维护者择机发版）；更细的 CODEOWNERS / 多架构镜像矩阵等可按运营需要再补。

## 总评（第 27 轮）

工程化与供应链水位已达开源候选高位；Goal 在真人 AT 与正式首发版完成前**不标记完成**。

## 验证证据（第 27 轮）

- `cd web && npm audit`：0；vitest 5.1.0，12 tests passed
- `mvn -B verify`：BUILD SUCCESS
- web lint/typecheck/test/build/e2e：8 passed（含 axe）
