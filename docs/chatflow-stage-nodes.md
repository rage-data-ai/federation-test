# ChatFlow：将 `builtin_ask` 拆为阶段节点

调研结论（相对 `docs/chatflow.md` 阶段 2「后续」、ADR `docs/adr/0002-chatflow-apps.md`）。
术语：`GLOSSARY.md` 明确 **SSE `stage` 事件名 ≠ Node 类型**；节点类型 ID 可复用 `knowledge` / `ontology` / `metadata` / `insight`，但领域词仍叫 Node。

## 1. 现状 vs 目标图（智能问数）

### 当前（已落地）

```text
start ──default──► builtin_ask ──default──► end
```

- 模板：`DefaultFlows.builtinAsk()`（`src/main/java/.../chatflow/DefaultFlows.java`）
- 运行：`FlowRunner` 对 `builtin_ask` 整段调用 `AskService.ask(...)`；`llm_chat` 另走一次 LLM
- 边条件枚举已在图模型 / 前端存在（`default` / `answered` / `clarify` / `continue` / `error`），但 **Runner 对所有已支持节点都写死 `lastOutcome = "default"`**，分支边尚未生效

### 目标（Dify 式阶段节点）

```text
start ──default──► knowledge ──answered──► end
                      │
                      └──continue──► ontology ──clarify──► end
                                        │
                                        └──continue──► metadata ──error──► end
                                                          │
                                                          └──continue──► insight ──answered──► end
                                                                            ├──clarify──► end
                                                                            └──error────► end
```

线性兜底（仅 `default`）也可：

```text
start → knowledge → ontology → metadata → insight → end
```

短路与澄清应优先用显式 `when`，与 ADR / `EDGE_WHEN_OPTIONS` 对齐。

对照「简单对话」模板保持不变：`start → llm_chat → end`。

## 2. 已存在的阶段节点类型名

| 来源 | 状态 |
|---|---|
| ADR 0002、`docs/chatflow.md` | 规划名：`knowledge` / `ontology` / `metadata` / `insight` |
| `web/src/flow/nodeCatalog.ts` | **已写入 catalog**；`addable: false`；文案「即将支持」 |
| `FlowRunner` | **不可运行**；未知类型 → `IllegalArgumentException("暂不支持的节点类型：…")` |
| `AskService` SSE | 同名 `stage` / 业务事件：`knowledge`、`ontology`、`metadata`、以及 insight 阶段的 `sql`/`result`/`answer` |

**结论：类型 ID 与标题已定稿，勿再发明别名；缺口在 Runner + Ask 管线可拆方法 + 默认图迁移。**

## 3. 可运行节点 vs catalog-only

| type | 画布 catalog | Runner 今天 |
|---|---|---|
| `start` | 有（不可再添加） | 透传 `default` |
| `end` | 有 | 终止 |
| `builtin_ask` | 可添加 | → `AskService.ask` |
| `llm_chat` | 可添加 | → `runLlmChat` |
| `knowledge` | 有，不可添加 | 抛错 |
| `ontology` | 有，不可添加 | 抛错 |
| `metadata` | 有，不可添加 | 抛错 |
| `insight` | 有，不可添加 | 抛错 |

`FlowGraph.validate()` **不校验**节点类型白名单，只校验起点、边引用、至少一个 `end`。因此用户若手改 JSON 放入阶段节点并发布，运行会直接失败。

## 4. Ask 管线 ↔ 可抽成节点的服务面

入口：`AskController` → `ChatflowRuntime.ask` → `FlowRunner` →（今日）`AskService.ask` → 私有 `answer(...)`。

`AskService.answer` 内顺序（`src/main/java/.../ask/AskService.java`）：

| 阶段 Node | 现有逻辑（私有/包内） | 建议 outcome |
|---|---|---|
| `knowledge` | `tryAnswerFromKnowledge`：`AskKnowledgeService.search` + LLM `KNOWLEDGE_PROMPT`；命中且 `answered` 则 `emit("answer")` 并 return | `answered`（短路结束）/ `continue`（无命中、判定失败、解析失败） |
| `ontology` | `ontologyTools.businessContext` + `AnswerabilityChecker`；低置信则 `explore(...)`（`OntologyTools` 工具）；空概念 → `clarify` | `continue` / `clarify` |
| `metadata` | `MetadataVerifier.verify(context, recover?)`；无表 → `error` | `continue` / `error` |
| `insight` | 构造 `InsightSession` + `Analyst`（`INSIGHT_PROMPT`）；`clarify` 或 `answer` | `answered` / `clarify` / `error` |

依赖面（不宜复制第二套实现）：

- `AskKnowledgeService`
- `OntologyTools` / `BusinessContextBuilder`
- `MetadataVerifier`
- `InsightSession`（包内，含 SQL 护栏与恢复工具）
- `SqlGuard` / `QueryService`（经 AskService 注入的 runner/validator）
- `AskPromptService` / `PreferenceTools`（系统提示与偏好工具）

公开可复用入口今天几乎只有整段 `ask` 与 `answerability`；阶段逻辑全是 private。

## 5. 最小后端改动路径

### 方案 A — 扩图但保持单 Runner 路径（一期，已落地后被 B 取代）

画布四阶段 + Runner 折叠为一次 `AskService.ask`（边条件当时未生效）。

### 方案 B — 阶段真正可运行（已落地）

1. `AskService` 暴露 `runKnowledge` / `runOntology` / `runMetadata` / `runInsight`，返回 outcome；`AskFlowState` 在节点间传递。
2. `FlowRunner` 对阶段图白盒拆跑：单次 `beginRun`/`finishRun`，按 outcome 选边。
3. 节点 `config.skills` 绑定工具技能：`ontology` / `preference` / `knowledge` / `sql`（见 `AskSkillIds`）。
4. `llm_chat` 可勾选本体/偏好工具（Agent 模式）；知识走知识库节点，SQL 走洞察节点；`insight` 必选 `sql`。
5. `insight` 仍唯一执行 SQL 的节点，继续走 `InsightSession` + `SqlGuard`；SSE 事件名不变。

### 明确不要做的

- 在 Runner 里再写一套问数逻辑（违背 ADR：「内置问数仍走同一套 AskService 护栏与 SSE」）。
- 改 SSE 事件名（前端 `useAskStream.ts` / `AskView.vue` 已按 stage 名绑定）。

## 6. 建议的节点 ID / 标题 / 默认边

| type（稳定 ID） | 默认 title | 建议默认 config | 出边 `when` |
|---|---|---|---|
| `knowledge` | 知识库 | `{}` 或日后 `topK` / `enabled` | `answered` → end；`continue` → ontology；`default` → ontology |
| `ontology` | 本体 | 可选 `minConfidence` 覆盖全局 | `continue` → metadata；`clarify` → end；`default` → metadata |
| `metadata` | 元数据 | 可选 `recover: true` | `continue` → insight；`error` → end；`default` → insight |
| `insight` | SQL 洞察 | 可选 `resultPreviewRows`、系统提示覆盖 | `answered`/`clarify`/`error` → end；`default` → end |
| `builtin_ask` | 智能问数 | 保留 | `default` → end（兼容） |

模板节点坐标可沿用现间距（约 x+=220），与画布 `DEFAULT_GAP_X` 一致。

## 7. 前端 catalog / 编辑器

已有：`nodeCatalog.ts`、`FlowDagEditor.vue`、`FlowNodeCard.vue`、`graphAdapter.ts`（未知 type 仍渲染，颜色走 fallback）。

建议改动：

1. 方案 A 落地后：阶段节点 `addable: true`，去掉「即将支持」；`builtin_ask` 可标为「整段问数（兼容）」或逐步降级为不可添加。
2. 属性面板：阶段暂无必填 config；`llm_chat` 已有 `systemPrompt` 可对照扩展。
3. 可选：从调色板拖入四阶段时给出「问数流水线」预设边。
4. `FlowController` `/templates` 描述改为「知识库 → 本体 → 元数据 → SQL 洞察」节点列表（文案已接近）。
5. 测试：`graphAdapter.test.ts`、e2e 冒烟；后端 `AppServiceTest` 今日断言 `node("ask").type() == "builtin_ask"` 需随模板更新。

## 8. 风险

| 风险 | 说明 |
|---|---|
| 已发布应用 | Release **钉死图 JSON**；改 `DefaultFlows` 只影响**新种子**/新模板创建，**不会**自动改 `app-builtin-ask` 已发布图。`JdbcChatflowStore.seedBuiltinIfEmpty` 仅在内置 app 不存在时播种。 |
| 内置「智能问数」 | `app-builtin-ask` / `flow-builtin-ask` 不可归档、不可换绑 flow；要换默认行为需改草稿并 **重新 publish**，或做一次性迁移把当前 Release 图换成阶段图。 |
| 提前扩图未改 Runner | 阶段类型会令 `FlowRunner` 抛错 → 问数全挂。扩图与 Runner 折叠/实现必须同发。 |
| 双发 `run` / 双落库 | `AskService.ask` 与 `FlowRunner.runLlmChat` 各自 `AskRunRecorder`；拆阶段时必须统一外壳。 |
| 边条件名存实亡 | UI 已能设 `answered` 等，Runner 不用则用户误以为可分支；方案 A 应文档说明，或暂时隐藏非 `default`。 |
| 测试与文档漂移 | `AppServiceTest`、`FlowRunnerTest`、`docs/chatflow.md` 默认图需同步。 |
| 术语混淆 | GLOSSARY：勿把 Node 叫 stage；SSE 仍发 `stage` 事件。 |

## 9. 建议落盘编辑路径

### 文档

- `docs/chatflow.md` — 阶段 2 验收与默认图
- `docs/adr/0002-chatflow-apps.md` — 若采纳方案 A/B 补「后果」
- `GLOSSARY.md` — 可为四阶段 Node 补词条（可选）
- 本文件 `docs/chatflow-stage-nodes.md`

### 后端

- `src/main/java/.../chatflow/DefaultFlows.java`
- `src/main/java/.../chatflow/FlowRunner.java`（+ 可选 `AskFlowState`）
- `src/main/java/.../ask/AskService.java`（抽 stage；方案 B）
- `src/main/java/.../ask/InsightSession.java` / `MetadataVerifier.java` / `knowledge/AskKnowledgeService.java`（方案 B 调用面）
- `src/main/java/.../chatflow/MemoryChatflowStore.java` / `JdbcChatflowStore.java`（仅当要迁移已种子图）
- `src/main/java/.../api/FlowController.java`（templates 文案）
- 测试：`FlowRunnerTest.java`、`AppServiceTest.java`、`FlowGraphTest.java`

### 前端

- `web/src/flow/nodeCatalog.ts`
- `web/src/components/flow/FlowDagEditor.vue`
- `web/src/components/flow/FlowNodeCard.vue`（若区分「即将支持」样式）
- `web/src/flow/graphAdapter.ts` / `graphAdapter.test.ts`
- `web/src/AppsView.vue`（编辑入口，一般无需逻辑改）

## 10. 推荐实施顺序

1. **方案 A（已落地）**：Runner 折叠 + 新默认图 + catalog `addable` + 测试/文档；`BuiltinAskGraphMigrator` 启动迁移内置 app。
2. 观测：用户是否要「跳过知识库 / 仅本体+SQL」等编排（边条件真正按 outcome 生效需方案 B）。
3. **方案 B**：抽 pipeline、真实 outcome、状态在节点间传递；再考虑应用级提示词/知识范围（`docs/chatflow.md` 阶段 3）。
