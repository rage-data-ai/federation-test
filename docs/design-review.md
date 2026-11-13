# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite / NVIDIA Auto Ontology 等成熟开源实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 15 轮（进行中）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**：answerable、码值过滤校验、sqlCondition/sqlExpression、**certified 优先**、steps、编辑距离 + **n-gram 术语召回**。
3. **安全栈**：Bearer + RBAC + JWT + PKCE（OIDC Discovery）+ ACL + 行级过滤 + denylist/back-channel。
4. **工程门禁**：CI、JaCoCo、Spotless、ESLint、Vitest、全量 vue-tsc；`CODE_OF_CONDUCT.md`。
5. **本体运维**：版本快照/回滚、审定 API + **控制台 certify UI**、起草前 checkpoint。

## 已关闭差距（累计至第 15 轮）

| 问题 | 状态 |
|------|------|
| 安全 / ACL / 行级 / denylist / Discovery | ✅ |
| 问数 answerable / filter / steps / sqlExpression | ✅ |
| 全量 vue-tsc | ✅ |
| 本体版本回滚 | ✅ |
| certify 治理标志 + 控制台 UI | ✅ |
| OntologyStore 无 Docker 单测 | ✅ |
| 近邻词模糊检索（编辑距离） | ✅ |
| **轻量 n-gram 术语召回** | ✅ |

## 距顶尖开源仍待项

### P1

| # | 问题 | 建议 |
|---|------|------|
| 1 | 无真向量术语召回 | 可选 embedding / pgvector 替换 n-gram |
| 2 | Error Prone | 当前 JDK 镜像下 javac 插件初始化失败；需模块导出或换 SpotBugs |

### P2

a11y 深化、视觉回归、Redis denylist。

## 总评（第 15 轮）

本体建设与问数准确度已吸收 Auto Ontology 的主要工程可迁移点（answerable / certify / SQL attributes / filter validation / steps / 术语软召回）。剩余主要是真向量检索与体验深化。目标在证据充分前**不标记完成**。

验证：`mvn -B verify` + `cd web && npm run lint && npm run typecheck && npm test && npm run build`。
