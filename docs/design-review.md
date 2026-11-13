# 软件设计评估报告

评估基准：Trino / Spring Boot / Apache Calcite 等成熟开源项目的工程与架构实践。  
初评日期：2026-11-13（Asia/Shanghai）  
复评轮次：第 12 轮（进行中；已吸收 NVIDIA Auto Ontology 问数加固）

## 优点

1. **包边界清晰**，查询 / 问数 / 本体主链路可读。
2. **问数护栏**与联邦路由设计正确；对齐 Auto Ontology 的可回答性预检、过滤值校验与步骤溯源。
3. **安全栈**：Bearer + RBAC + JWT + PKCE（refresh/注销）+ catalog/表 ACL + **Calcite 行级 WHERE 注入（JOIN 别名限定）** + **可插拔令牌 denylist**（内存 / 共享文件）+ **back-channel logout**；Prometheus + OTLP。
4. **工程门禁**：CI、`mvn verify`（JaCoCo ≥75%/70%）、Spotless、ESLint、Vitest、`tsc` + `vue-tsc`。
5. **前端**：逻辑层 TypeScript；主要 SFC 已标 `lang="ts"`；共享 `types.ts` 收敛中。

## 已关闭差距（累计至第 12 轮）

| 问题 | 状态 |
|------|------|
| 管线 / 鉴权 / RBAC / JWT / PKCE / refresh / IdP 注销跳转 | ✅ |
| catalog / 表级 ACL | ✅ |
| 行级谓词自动注入（Calcite WHERE） | ✅ |
| **JOIN 别名限定行级谓词** | ✅ |
| 令牌吊销 denylist + **跨实例 Store + back-channel** | ✅ |
| **问数可回答性预检 / 码值过滤校验 / answer.steps** | ✅（参考 NVIDIA Auto Ontology） |
| RFC 7807 · `/auth/me` | ✅ |
| composable TS · SFC `lang="ts"` | ✅ |
| WebMvc + Engine + Store IT | ✅ |
| 覆盖率 ≥75% | ✅（~78% instruction） |

## 距顶尖开源仍待项

### P1

| # | 问题 | 建议 |
|---|------|------|
| 1 | vue-tsc 尚未对全部 `.vue` 零错误通过 | 扩大 include 后系统性补类型 |
| 2 | store 无 Docker 单测为 0% | 接受 IT-only 或抽纯逻辑单测 |
| 3 | 无可复用度量 SQL attributes / 向量术语召回 | 扩展 DefinedConcept 或属性表达式；可选 embedding |

### P2

本体版本回滚、a11y、Error Prone、OIDC Discovery、视觉回归、Redis `TokenRevocationStore`。

## 总评（第 12 轮）

安全与问数准确度工程已明显抬升；本体起草同义词与问数 Validate 环路对齐严肃语义层实践。剩余阻塞顶尖标签的主要是**全量 Vue 类型检查**与**度量表达式/召回**。目标在证据充分前**不标记完成**。

验证：`mvn -B verify` + `cd web && npm run lint && npm run typecheck && npm test && npm run build`。
