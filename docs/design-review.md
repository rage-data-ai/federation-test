# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite / NVIDIA Auto Ontology 等成熟开源实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 23 轮（进行中）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**：answerable、filter、sqlExpression、certified、steps；HTTP/哈希嵌入 + 可插拔持久术语索引（file / JDBC / both）+ **可选 pgvector（失败自动降级）**。
3. **安全栈**：Bearer + RBAC + JWT + PKCE + ACL + 行级 + denylist（内存/文件/Redis）。
4. **工程门禁**：CI（**ubuntu-24.04**，规避 Playwright/ubuntu-26 不兼容）、JaCoCo、Spotless、SpotBugs、Error Prone、CycloneDX、springdoc、ESLint、Vitest、Playwright（放宽像素阈值 + 字体稳定化）、vue-tsc。
5. **本体运维**：版本 UI、certify UI、checkpoint。
6. **可访问性**：skip-link、main、aria-live、dialog `aria-modal` + 焦点陷阱 / Escape（含 **DraftDialog** 与本体删除/重命名/版本/Turtle）。

## 已关闭差距（累计至第 23 轮）

| 问题 | 状态 |
|------|------|
| 问数 / 嵌入索引 / OpenAPI / Redis denylist | ✅ |
| SpotBugs / Error Prone / SBOM | ✅ |
| Playwright 冒烟 + 视觉基线（CI） | ✅ |
| 对话框键盘焦点陷阱 | ✅ |
| 联邦库 JDBC 术语嵌入持久化 | ✅ |
| **DraftDialog 键盘/a11y（focus trap、aria、live region）** | ✅ |
| **可选 pgvector similarity + 降级** | ✅ |
| **CI ubuntu-26 / Playwright 安装失败；视觉阈值与字体稳定化** | ✅ |

## 距顶尖开源仍待项

### P2 / P3

pgvector IVFFlat/HNSW 大规模调参；DraftDialog 与屏幕阅读器的端到端录屏验收；更广页面键盘审计；依赖 CVE 例行升级。

## 总评（第 23 轮）

Auto Ontology 式术语召回已具备「本地默认 + 联邦共享 + 可选 pgvector」三层，本体起草对话框无障碍闭环，CI 前端门禁回到可安装 Chromium 的 LTS 镜像。目标在证据充分前**不标记完成**。

验证：`mvn -B verify` + `cd web && npm run lint && npm run typecheck && npm test && npm run build`（及 CI e2e）。
