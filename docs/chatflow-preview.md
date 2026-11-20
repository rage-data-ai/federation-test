# ChatFlow DAG 预览（全阶段方案）

## 目标

在「问数应用」编排页对**草稿 / 当前画布脏图**做 Dify 式调试预览，不必先发布、不必跳到应用广场。

生产入口（智能问数 / 应用广场）仍**只读 App Release**（见 ADR 0002）。

## 阶段总览

| 阶段 | 范围 | 状态 |
|---|---|---|
| **P0** | `POST /ask/preview`；草稿/脏图可跑；`node_start`/`node_finish`；编排页预览抽屉；节点高亮 | 本变更交付 |
| **P1** | 选中节点出参检查器；走过边高亮；预览不落正式 run 历史 | 本变更交付 |
| **P2** | 单步/断点；开始节点自定义输入变量；用例重放 | 规划保留，未实现 |

## P0/P1 行为

1. 请求体可带 `graph`（当前画布）；缺省读应用绑定流程的草稿图。
2. SSE 对齐 `/ask`，额外：
   - `run.preview=true`
   - `node_start` / `node_finish`（含 `nodeId`/`title`/`type`/`outcome`）
   - `start` 也发 `vars`
3. 预览 **不写入** `fed_ask_run`（避免污染广场/历史）。
4. UI：画布旁预览对话；节点 `running/ok/error`；选中节点展示最近 outputs。

## API

```text
POST /api/v1/ask/preview  SSE
Body: { appId, question, history?, conversationId?, graph? }
```

## 非目标（P2）

Runner 协作式暂停、开始节点表单变量、与 Release 的并排 diff。
