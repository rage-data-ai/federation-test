# ADR 0002：问数 ChatFlow 与可发布应用

## 状态

已采纳（2026-11-16 Asia/Shanghai）

## 背景

现网问数是 `AskService` 内写死的线性流水线。产品目标是接近 Dify ChatFlow：可编排节点、发布成应用、按应用运行。完全重写成通用工作流引擎成本高，且要保住 SQL 护栏与现有 SSE 契约。

## 决策

1. **领域对象**：Flow（草稿图）→ Flow Version（发布快照）→ App（入口）→ App Release（钉死某版本）。**生产运行只读 Release，不读草稿。**
2. **预览通道（补充）**：`POST /api/v1/ask/preview` 可读草稿或请求内 `graph`，供编排页调试；须带 `preview` 标记且默认不落正式问数历史。详见 `docs/chatflow-preview.md`。
3. **节点**：白盒阶段图（knowledge / ontology / metadata / llm_chat / if_else 等）；旧 `builtin_ask` 已移除。
4. **兼容入口**：`POST /api/v1/ask` 可选 `appId`；缺省走内置「智能问数」当前发布。
5. **存储**：MySQL 表 `fed_flow` / `fed_flow_version` / `fed_app` / `fed_app_release`；`fed_ask_run` 可空 `app_id`。
6. **不做（预览 P2 之前）**：单步断点、开始节点自定义会话变量表单、对外发布令牌、HTTP/Webhook 节点。

## 后果

- 用户可复制默认流程、改成「仅 LLM 对话」等变体并发布为应用。
- 内置问数仍走同一套 `AskService` 护栏与 SSE 事件，不双轨实现。
- 后续拆节点时，默认 Flow Version 只改图、不改调用方 API。
