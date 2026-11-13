# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite / NVIDIA Auto Ontology 等成熟开源实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 21 轮（进行中）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**：answerable、filter、sqlExpression、certified、steps；HTTP/哈希嵌入 + 持久术语索引。
3. **安全栈**：Bearer + RBAC + JWT + PKCE + ACL + 行级 + denylist（内存/文件/Redis）。
4. **工程门禁**：CI、JaCoCo、Spotless、SpotBugs、Error Prone、CycloneDX、springdoc、ESLint、Vitest、**Playwright 冒烟/视觉基线**、vue-tsc。
5. **本体运维**：版本 UI、certify UI、checkpoint。
6. **可访问性**：skip-link、main、aria-live、dialog `aria-modal` + **焦点陷阱 / Escape**。

## 已关闭差距（累计至第 21 轮）

| 问题 | 状态 |
|------|------|
| 问数 / 嵌入索引 / OpenAPI / Redis denylist | ✅ |
| SpotBugs / Error Prone / SBOM | ✅ |
| **Playwright 冒烟 + 视觉基线（CI）** | ✅ |
| **对话框键盘焦点陷阱** | ✅ |

## 距顶尖开源仍待项

### P2

可选真 pgvector 后端；更广页面键盘审计；视觉基线跨 OS 字体差异调参。

## 总评（第 21 轮）

控制台已具备 CI 级视觉/冒烟与对话框键盘无障碍。剩余主要是可选向量库后端与体验深挖。目标在证据充分前**不标记完成**。

验证：`mvn -B verify` + `cd web && npm run lint && npm run typecheck && npm test && npm run build && npm run test:e2e`。
