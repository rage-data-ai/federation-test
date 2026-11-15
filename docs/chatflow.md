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
- 内置模板：「智能问数」=`start → builtin_ask → end`；「简单对话」=`start → llm_chat → end`
- API：流程 CRUD、发布版本；应用 CRUD、发布；列表已发布应用
- 运行：`AskRequest.appId` → 解析当前 Release → `FlowRunner` 执行图
- 控制台：「应用」模块：选模板创建、编辑节点配置、发布、打开对话（复用 Ask 流）
- 测试：服务单测 + API WebTest；前端类型与 e2e 冒烟

### 阶段 2 — 拆分问数阶段节点

- 将 `builtin_ask` 拆为 `knowledge` / `ontology` / `metadata` / `insight`
- 默认流程图改为四段串联；边条件表达短路（知识命中 → end）
- 可视化简易画布（节点调序、开关某段）

### 阶段 3 — 应用能力加深

- 应用级提示词 / 知识库范围
- 变量与会话记忆节点
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
POST   /api/v1/apps                  { name, flowId, description? }
GET    /api/v1/apps/{id}
PUT    /api/v1/apps/{id}
POST   /api/v1/apps/{id}/publish     → AppRelease（钉当前 flow 最新版本或指定 versionId）
GET    /api/v1/apps/published        运行入口列表

POST   /api/v1/ask                   { question, history, conversationId?, appId? }
```

## 默认图

```text
智能问数:  start ──default──► builtin_ask ──default──► end
简单对话:  start ──default──► llm_chat     ──default──► end
```

## 验收（阶段 1）

1. 新装网关自动有内置「智能问数」应用；原问数页不传 `appId` 行为与现在一致。
2. 用户可基于「简单对话」模板发布第二个应用，对话走 `llm_chat` 不跑 SQL。
3. 未发布应用不能被 `appId` 调用（4xx）。
4. 单元测试覆盖图校验、发布、默认回退；桌面可点通创建 → 发布 → 提问。
