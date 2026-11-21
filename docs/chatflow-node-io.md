# ChatFlow 节点入参 / 出参（对齐 Dify 变量）

## 目标

让每个节点像 Dify 一样声明 **输入（可绑定变量或字面量）** 与 **输出（写入变量池）**，下游用 `{{#节点名称.字段#}}` / `{{#sys.query#}}` 引用。

## 默认智能问数图

```text
start → 知识库 → 知识判定(llm_chat) → 能否用知识作答(canAnswer==true)
                                         ├─是 → 知识回答(answer)
                                         └─否 → 本体 → 元数据 ─出错→（叶子）
                                                      └─默认→ 大模型(sql) → 回答(answer)
```

ChatFlow **无结束节点**（对齐 Dify）：校验要求至少有一个**回答节点**；`llm_chat`/insight **只写变量池**，最终 `answer` SSE **仅由回答节点**发出（可并入上游 sql/rows）。正式问数与编排预览共用同一套 `FlowRunner` DAG。旧图含 `end` 且无回答节点时，会把 end 转为 answer。

1. **知识库**：只检索，写出唯一出参 `result`（`Array[Object]`）
2. **知识判定**：普通 `llm_chat`；提示词插入 `{{#知识库.result#}}`，并约定模型输出含 `canAnswer` 的 JSON
3. **能否用知识作答**：读 `{{#知识判定.canAnswer#}}` 分流

画布：单击节点配置；**节点名称不可重复**；变量路径用**节点名称**（不展示内部 id）。提示词用 `{x}` 在光标处插入；**只能引用本节点上游出参**；侧栏提示本节点输出字段名。

## 端口目录来源

前端通过 `GET /api/v1/flows/node-ports` 拉取；权威定义在后端 `FlowNodePorts`，运行时解析在 `FlowVarRefs` / `FlowRunner`（按节点名称写入变量池）。

## 变量引用（唯一方式）

```text
召回知识：
{{#知识库.result#}}
首条问题：{{#知识库.result.0.question#}}
用户问题：{{#sys.query#}}
```

## llm_chat 出参（通用）

配置面板不展示固定「输出」清单。运行时：模型回复写入 `answer`；若回复含 JSON 对象，其字段一并写入变量池（如提示词约定的 `canAnswer`）。下游用 `{{#节点名称.字段#}}` 引用。

## 知识库 `result` 字段（对齐 `fed_ask_knowledge`）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | Number | 主键 |
| `score` | Number | 召回分 |
| `question` | String | 问题 |
| `answer` | String | 答案 |
| `sql` | String | sql_text |
| `caliber` | Array | 口径 |
| `evidence` | Object | 证据 |
| `kind` | String | smart_ask / general |
| `status` | String | pending / approved / rejected |
| `year` | String | 业务年 |
| `version` | String | 知识版本 |
| `sourceRunId` | String | 来源问数 run |

## 回答节点（`answer`）

- 模板取自 `config.inputs.text` 或顶层 `config.text`（二者等价，读写时会互相同步）。
- 支持固定文案 + `{{#上游.字段#}}` 自由拼接；可添加多个回答节点。
- **空值安全**：引用的上游变量为 `null`、缺失或 `undefined` 时按**空字符串**拼入，不抛错、不中断、不显示错误（预览与正式问答相同）。

## 节点端口（摘要）

| type | 关键 outputs |
|---|---|
| `knowledge` | `result` Array[Object] |
| `llm_chat` | `answer`（JSON 其它字段运行时动态写入） |
| `answer` | `answer`（模板插值后的最终回复） |
| `if_else` | `outcome`=`true`/`false`, `result` |
