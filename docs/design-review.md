# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite / NVIDIA Auto Ontology 等成熟开源实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 16 轮（进行中）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**：answerable、码值过滤校验、sqlCondition/sqlExpression、**certified 优先**、steps、编辑距离 + **n-gram 术语召回**。
3. **安全栈**：Bearer + RBAC + JWT + PKCE（OIDC Discovery）+ ACL + 行级过滤 + denylist/back-channel。
4. **工程门禁**：CI、JaCoCo、Spotless、**SpotBugs（High）**、ESLint、Vitest、全量 vue-tsc；`CODE_OF_CONDUCT.md`。
5. **本体运维**：版本快照/回滚（API + **控制台版本面板**）、审定 API + certify UI、起草前 checkpoint。
6. **可访问性**：跳到主内容、主区域 landmark、问数 aria-live、导航 aria-label。

## 已关闭差距（累计至第 16 轮）

| 问题 | 状态 |
|------|------|
| 安全 / ACL / 行级 / denylist / Discovery | ✅ |
| 问数 answerable / filter / steps / sqlExpression | ✅ |
| 全量 vue-tsc | ✅ |
| 本体版本回滚 + 控制台 UI | ✅ |
| certify 治理标志 + 控制台 UI | ✅ |
| OntologyStore 无 Docker 单测 | ✅ |
| 近邻词模糊检索（编辑距离） | ✅ |
| 轻量 n-gram 术语召回 | ✅ |
| **SpotBugs High 门禁**（替代受阻的 Error Prone） | ✅ |
| **控制台 a11y 基础深化** | ✅ |

## 距顶尖开源仍待项

### P1

| # | 问题 | 建议 |
|---|------|------|
| 1 | 无真向量术语召回 | 可选 embedding / pgvector 替换 n-gram |

### P2

视觉回归、Redis denylist、更广 a11y（键盘陷阱审计）、Error Prone（需 JDK 模块导出）。

## 总评（第 16 轮）

工程静态分析与本体运维体验补齐后，网关更接近严肃开源生产水位。真向量检索仍是主要能力差距。目标在证据充分前**不标记完成**。

验证：`mvn -B verify`（含 SpotBugs）+ `cd web && npm run lint && npm run typecheck && npm test && npm run build`。
