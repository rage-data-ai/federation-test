# 问数 ChatFlow 规划

目标：把写死的问数流水线提升为可配置、可发布的对话流程（对齐 Dify ChatFlow 的「编排 → 发布应用 → 按应用聊天」），同时保住现有本体/SQL 护栏与 SSE 契约。

术语见根目录 `GLOSSARY.md`；关键取舍见 `docs/adr/0002-chatflow-apps.md`。

## 对照 Dify（我们要什么）

| Dify ChatFlow | 本仓库落地 |
|---|---|
| 画布编排节点 | Flow 草稿：节点 + 边（一期列表/模板编辑，二期画布） |
| 发布 | Flow Version + App Release |
| 应用 | App：名称、说明、当前发布 |
| 调试 / 对话 | 按 `appId` 走 `POST /api/v1/ask` SSE |
| 知识库 / 工具 | 复用现有知识库、本体工具、SqlGuard；按应用隔离列为后续阶段 |

## 阶段

### 阶段 1 — 流程骨架 + 发布应用最小闭环（本分支）

- 域模型与表：Flow / Flow Version / App / App Release；`fed_ask_run.app_id`
- 内置模板：「智能问数」=白盒 DAG（知识/判定/条件/本体/元数据/SQL → 回答节点）；「简单对话」=`start → llm_chat → answer`
- API：流程 CRUD、发布版本；应用 CRUD、发布；列表已发布应用
- 运行：`AskRequest.appId` → 解析当前 Release → `FlowRunner` 执行图
- 控制台：「问数应用」编排发布；「应用广场」只列已发布应用并打开对话；「智能问数」固定内置应用
- 测试：服务单测 + API WebTest；前端类型与 e2e 冒烟

### 阶段 2 — 可拖拽 DAG 画布

- 控制台用 Vue Flow 画布拖拽节点、连线、编辑分支条件（`when`）
- 节点坐标写入 `config.x` / `config.y`，与一期 JSON 兼容
- 「智能问数」默认图展开为 `knowledge → ontology → metadata → llm_chat(+sql)`（含短路/澄清边）；旧 `insight` 节点仍兼容；见 [`chatflow-stage-nodes.md`](chatflow-stage-nodes.md)

### 阶段 2.6 — 节点入参 / 出参（Dify 变量，已落地）

见 [`chatflow-node-io.md`](chatflow-node-io.md)：`sys.*` + `{{#nodeId.field#}}`、`config.inputs`、变量池、`vars` SSE。

### 阶段 2.7 — DAG 预览（P0/P1，已落地）

见 [`chatflow-preview.md`](chatflow-preview.md)：编排页对草稿/脏图 `POST /ask/preview`，节点高亮与出参检查；生产仍只读 Release。

### 阶段 3 — 应用能力加深

- 应用级提示词 / 知识库范围
- 自定义会话变量（开始节点）；条件边表达式
- 预览 P2：单步/断点、用例重放
- 对外发布令牌（不登录控制台也能打应用）
- HTTP / Webhook 节点（若有真实需求再加）

## API 草图（阶段 1）

```
GET    /api/v1/flows
POST   /api/v1/flows                 { name, template? }
GET    /api/v1/flows/{id}
PUT    /api/v1/flows/{id}            { name, graph }
POST   /api/v1/flows/{id}/publish    → FlowVersion

GET    /api/v1/apps
POST   /api/v1/apps                  { name, flowId?, description?, template? }
GET    /api/v1/apps/{id}
PUT    /api/v1/apps/{id}             { name?, description?, flowId? }
DELETE /api/v1/apps/{id}             软删除（归档）；内置「智能问数」不可删
POST   /api/v1/apps/{id}/publish     → AppRelease（钉当前 flow 最新版本或指定 versionId）
GET    /api/v1/apps/published        运行入口列表

POST   /api/v1/ask                   { question, history, conversationId?, appId? }
```

## 默认图

```text
智能问数 (rev≥22，模板名 builtin_ask，已无黑盒节点):
  start → knowledge → llm_kb → if_kb ─true→ answer_kb
                                   └false→ ontology → metadata ─error→（叶子）
                                                        └default→ llm_chat(+sql) → answer_sql
  （正式问数 = 与编排预览同一套 DAG；上游只写池；最终输出必须经回答节点）

简单对话:  start ──default──► llm_chat ──default──► answer
```

## 验收（阶段 1）

1. 新装网关自动有内置「智能问数」应用；原问数页不传 `appId` 行为与现在一致。
2. 用户可基于「简单对话」模板发布第二个应用，对话走 `llm_chat` 不跑 SQL。
3. 未发布应用不能被 `appId` 调用（4xx）。
4. 单元测试覆盖图校验、发布、默认回退；桌面可点通创建 → 发布 → 提问。
