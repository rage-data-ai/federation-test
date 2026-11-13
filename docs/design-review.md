# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite / NVIDIA Auto Ontology 等成熟开源实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 14 轮（进行中）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**：answerable、码值过滤校验、sqlCondition/sqlExpression、**certified 优先**、steps、轻量模糊检索。
3. **安全栈**：Bearer + RBAC + JWT + PKCE（OIDC Discovery）+ ACL + 行级过滤 + denylist/back-channel。
4. **工程门禁**：CI、JaCoCo、Spotless、ESLint、Vitest、全量 vue-tsc；`CODE_OF_CONDUCT.md`。
5. **本体运维**：版本快照/回滚、审定（certify）API、起草前 checkpoint。

## 已关闭差距（累计至第 14 轮）

| 问题 | 状态 |
|------|------|
| 安全 / ACL / 行级 / denylist / Discovery | ✅ |
| 问数 answerable / filter / steps / sqlExpression | ✅ |
| 全量 vue-tsc | ✅ |
| 本体版本回滚 | ✅ |
| **certify 治理标志** | ✅ |
| **OntologyStore 无 Docker 单测** | ✅（内存 TDB2） |
| **近邻词模糊检索（编辑距离）** | ✅ |

## 距顶尖开源仍待项

### P1

| # | 问题 | 建议 |
|---|------|------|
| 1 | 无向量术语召回 | embedding / pgvector（可选） |
| 2 | Error Prone / 静态分析门禁 | 编译期检查 |

### P2

a11y 深化、视觉回归、Redis denylist、控制台 certify UI。

## 总评（第 14 轮）

网关在安全、问数准确度、本体治理与工程门禁上已接近严肃开源生产水位。剩余主要是向量召回与更强静态分析/无障碍深化。目标在证据充分前**不标记完成**。

验证：`mvn -B verify` + `cd web && npm run lint && npm run typecheck && npm test && npm run build`。
