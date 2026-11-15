# ADR 0002：问数 ChatFlow 与可发布应用

## 状态

已采纳（2026-11-16 Asia/Shanghai）

## 背景

现网问数是 `AskService` 内写死的线性流水线。产品目标是接近 Dify ChatFlow：可编排节点、发布成应用、按应用运行。完全重写成通用工作流引擎成本高，且要保住 SQL 护栏与现有 SSE 契约。

## 决策

1. **领域对象**：Flow（草稿图）→ Flow Version（发布快照）→ App（入口）→ App Release（钉死某版本）。运行只读 Release，不读草稿。
2. **第一期节点**：`start` / `builtin_ask`（整段现有问数）/ `llm_chat`（无 SQL 的通用回复）/ `end`。边条件：`default`、`answered`、`clarify`、`continue`、`error`。后续再拆 `knowledge` / `ontology` / `metadata` / `insight` 为独立节点，默认流程图可平滑迁移。
3. **兼容入口**：`POST /api/v1/ask` 增加可选 `appId`；缺省走内置「智能问数」应用的当前发布。控制台「问数」模块行为不变。
4. **存储**：MySQL 表 `fed_flow` / `fed_flow_version` / `fed_app` / `fed_app_release`；`fed_ask_run` 增加可空 `app_id`。图结构以 JSON 存节点与边。
5. **不做（本期）**：可视化拖拽画布（先提供模板 + 节点列表编辑）、租户/工作空间隔离、应用级独立知识库与本体、Webhook/HTTP 节点、多渠道发布。

## 后果

- 用户可复制默认流程、改成「仅 LLM 对话」等变体并发布为应用。
- 内置问数仍走同一套 `AskService` 护栏与 SSE 事件，不双轨实现。
- 后续拆节点时，默认 Flow Version 只改图、不改调用方 API。
