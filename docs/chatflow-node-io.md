# ChatFlow 节点入参 / 出参（对齐 Dify 变量）

## 目标

让每个节点像 Dify 一样声明 **输入（可绑定变量或字面量）** 与 **输出（写入变量池）**，下游用 `{{#nodeId.field#}}` / `{{#sys.query#}}` 引用，而不是写死读 `AskRequest`。

## 对照 Dify

| Dify | 本仓库 |
|---|---|
| `sys.query` / 会话变量 | `sys.*`（见下） |
| 节点输出变量 `{{#llm.text#}}` | `{{#<nodeId>.<field>#}}` |
| 节点 Inputs 面板绑定 | `config.inputs`：`{ port: "{{#ref#}}" \| literal }` |
| 节点 Outputs 只读列表 | 类型目录 `FlowNodePorts` / 前端 `nodePorts.ts` |
| 提示词里插变量 | `systemPrompt` 等字符串做模板替换 |

## 系统变量 `sys.*`

| 路径 | 含义 |
|---|---|
| `sys.query` | 当前用户问题 |
| `sys.history` | 截断后的对话历史（Turn 列表） |
| `sys.conversation_id` | 会话 id |
| `sys.app_id` | 应用 id |
| `sys.user` | 当前用户名 |

## 节点端口（一期）

| type | inputs | outputs |
|---|---|---|
| `start` | — | `query`, `history`, `conversation_id`（写入 sys 同源） |
| `knowledge` | `query`（默认 `{{#sys.query#}}`）, `topK` | `outcome`, `answered`, `answer`, `matches` |
| `ontology` | `query`, `skills` | `outcome`, `context`, `answerability` |
| `metadata` | `recover` | `outcome`, `report`, `tables` |
| `insight` | `query`, `skills` | `outcome`, `answer`, `sql`, `rowCount` |
| `llm_chat` | `query`, `systemPrompt`, `skills` | `outcome`, `answer` |
| `builtin_ask` | `query` | `outcome`（整段，输出有限） |
| `end` | — | — |

未配置的 input 使用上表默认绑定；字面量可覆盖（如 `topK: 8`）。

## 图 JSON 形状

```json
{
  "id": "insight",
  "type": "insight",
  "title": "SQL 洞察",
  "config": {
    "x": 900,
    "y": 140,
    "skills": ["sql", "preference"],
    "inputs": {
      "query": "{{#sys.query#}}",
      "skills": ["sql", "preference"]
    }
  }
}
```

`systemPrompt` 示例：

```text
基于上游结论作答。知识库答案：{{#knowledge.answer#}}
用户问题：{{#sys.query#}}
```

## 运行时

1. `FlowRunner` 创建 `FlowVariablePool`，灌入 `sys.*`。
2. 进入节点前：按 `FlowNodePorts` + `config.inputs` 解析得到 `ResolvedInputs`。
3. 执行阶段（白盒 AskService）；把结果 `pool.put(nodeId, outputs)`。
4. 选边仍用 outcome；字符串配置（如 prompt）经 `FlowVarRefs.interpolate`。

## 分期

1. **本期（已实现）**：变量池 + 引用解析 + 端口目录 + Runner 接线 + 画布 Inputs/Outputs 面板。
2. **下期**：开始节点「会话变量」自定义；条件边表达式（`{{#x.outcome#}} == clarify`）；UI 变量选择器按类型过滤。
3. **更后**：并行分支变量合并、循环节点、HTTP 节点响应映射。
