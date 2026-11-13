# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite / NVIDIA Auto Ontology 等成熟开源实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 22 轮（进行中）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**：answerable、filter、sqlExpression、certified、steps；HTTP/哈希嵌入 + **可插拔持久术语索引（file / JDBC / both）**。
3. **安全栈**：Bearer + RBAC + JWT + PKCE + ACL + 行级 + denylist（内存/文件/Redis）。
4. **工程门禁**：CI、JaCoCo、Spotless、SpotBugs、Error Prone、CycloneDX、springdoc、ESLint、Vitest、Playwright 冒烟/视觉基线、vue-tsc。
5. **本体运维**：版本 UI、certify UI、checkpoint。
6. **可访问性**：skip-link、main、aria-live、dialog `aria-modal` + **焦点陷阱 / Escape**（含本体删除/重命名/版本/Turtle）。

## 已关闭差距（累计至第 22 轮）

| 问题 | 状态 |
|------|------|
| 问数 / 嵌入索引 / OpenAPI / Redis denylist | ✅ |
| SpotBugs / Error Prone / SBOM | ✅ |
| Playwright 冒烟 + 视觉基线（CI） | ✅ |
| 对话框键盘焦点陷阱 | ✅ |
| **联邦库 JDBC 术语嵌入持久化（对齐 Auto Ontology 共享索引）** | ✅ |
| **本体页对话框焦点陷阱** | ✅ |

## 距顶尖开源仍待项

### P2

真 pgvector 向量检索（当前为 LONGBLOB 指纹载荷）；DraftDialog 键盘审计；视觉基线跨 OS 字体差异调参。

## 总评（第 22 轮）

术语索引已具备 Auto Ontology 式「共享持久化」路径（联邦库 + 落盘双写），本体建设对话框键盘闭环补齐。目标在证据充分前**不标记完成**。

验证：`mvn -B verify` + `cd web && npm run lint && npm run typecheck && npm test && npm run build`。
