# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite / NVIDIA Auto Ontology 等成熟开源实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 24 轮（进行中）

## 第 23 → 24 轮开放项清单

| 开放项 | 本轮处置 |
|--------|----------|
| 更广页面键盘审计 | ✅ 数据源 CatalogFormModal + 单元格预览焦点陷阱；侧栏 Escape |
| 依赖 CVE / Dependabot 例行 | ✅ vite 升级、`overrides`（tinypool/minimatch）、Dependabot 分组 |
| 文档 / 安全基线缺口 | ✅ 刷新 `SECURITY.md` / `CONTRIBUTING.md` |
| pgvector IVFFlat/HNSW 大规模调参 | 仍开（P3，默认路径无硬依赖） |
| DraftDialog 屏幕阅读器录屏验收 | 仍开（P3，需人工 AT） |

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**：answerable、filter、sqlExpression、certified、steps；嵌入索引 file/JDBC/both + 可选 pgvector 降级。
3. **安全栈**：Bearer + RBAC + JWT + PKCE + ACL + 行级 + denylist；SECURITY.md 覆盖吊销/密钥/SBOM。
4. **工程门禁**：CI ubuntu-24.04、JaCoCo、Spotless、SpotBugs、Error Prone、CycloneDX、Dependabot 分组、Playwright、vue-tsc。
5. **本体运维**：版本 UI、certify UI、checkpoint。
6. **可访问性**：控制台对话框（鉴权/LLM/本体/起草/**数据源表单与预览**）焦点陷阱 + Escape。

## 已关闭差距（累计至第 24 轮）

| 问题 | 状态 |
|------|------|
| 此前问数/安全/嵌入/Playwright/pgvector 等（至第 23 轮） | ✅ |
| **数据源页更广键盘审计** | ✅ |
| **依赖 CVE 例行（vite/overrides + Dependabot groups）** | ✅ |
| **SECURITY / CONTRIBUTING 基线** | ✅ |

## 距顶尖开源仍待项

### P3

pgvector IVFFlat/HNSW 大规模调参；屏幕阅读器端到端人工验收；剩余 eslint/minimatch 传递依赖若上游未发 patch 则继续靠 Dependabot。

## 总评（第 24 轮）

控制台对话框键盘闭环扩到数据源；依赖与安全文档达到可运营的开源例行水位。目标在证据充分前**不标记完成**。

验证：`mvn -B verify` + `cd web && npm run lint && npm run typecheck && npm test && npm run build && npm run test:e2e`。
