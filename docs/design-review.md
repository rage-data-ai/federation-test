# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite / NVIDIA Auto Ontology 等成熟开源实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 13 轮（进行中）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**完整：可回答性预检、码值过滤校验、sqlCondition / **sqlExpression** 复用、steps 溯源。
3. **安全栈**：Bearer + RBAC + JWT + PKCE（**OIDC Discovery**）+ ACL + 行级过滤 + 可插拔 denylist + back-channel。
4. **工程门禁**：CI、`mvn verify`（JaCoCo ≥75%/70%）、Spotless、ESLint、Vitest、全量 `vue-tsc`。
5. **本体运维**：起草前自动版本快照，支持列表与 restore。

## 已关闭差距（累计至第 13 轮）

| 问题 | 状态 |
|------|------|
| 管线 / 鉴权 / RBAC / JWT / PKCE / refresh / IdP 注销 | ✅ |
| catalog / 表级 ACL · 行级 JOIN 别名 · denylist / back-channel | ✅ |
| 问数 answerable / filter 校验 / steps | ✅ |
| **全量 vue-tsc** | ✅ |
| **OIDC Discovery** | ✅ |
| **本体版本快照与回滚** | ✅ |
| **sqlExpression（SQL attributes）** | ✅ |
| RFC 7807 · WebMvc/IT · 覆盖率 ≥75% | ✅ |

## 距顶尖开源仍待项

### P1

| # | 问题 | 建议 |
|---|------|------|
| 1 | store 无 Docker 单测为 0% | 接受 IT-only 或抽纯逻辑 |
| 2 | 无向量术语召回 | 可选 embedding / pgvector |
| 3 | 无 certify 治理标志 | 审核态字段 + UI |

### P2

a11y、Error Prone、视觉回归、Redis `TokenRevocationStore`、本体度量草稿阶段强化。

## 总评（第 13 轮）

安全、问数准确度、类型门禁与本体可回滚性已达严肃开源网关高水位。剩余主要是召回增强、治理态与工程卫生（Error Prone / a11y）。目标在证据充分前**不标记完成**。

验证：`mvn -B verify` + `cd web && npm run lint && npm run typecheck && npm test && npm run build`。
