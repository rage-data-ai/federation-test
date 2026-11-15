# 联邦问数

跨源联邦查询网关上的对话式问数与可发布应用。

## Language

**Flow（流程）**:
一份可编辑的对话编排草稿：由节点与边组成的有向图，描述一次问数如何从用户问题走到答案或澄清。
_Avoid_: Workflow, ChatFlow（产品对外可说 ChatFlow，代码与表结构用 Flow）, pipeline

**Flow Version（流程版本）**:
某次发布时冻结的流程图快照；运行时只读版本，不读草稿。
_Avoid_: revision, snapshot（除非特指备份）

**Node（节点）**:
流程里的一步，有类型与配置；执行后产生结果状态供边选择。
_Avoid_: step（叙述可用，模型用 Node）, stage（现有 SSE 事件名，不等于节点类型）

**Edge（边）**:
节点之间的有向连接，可带条件（如 `continue` / `answered` / `clarify` / `default`）。

**App（应用）**:
面向使用者的问数入口：绑定一条流程，有名称与说明；草稿态不可对外跑正式流量。
_Avoid_: bot, agent（本仓库里 agent 仅指 LLM 工具会话，不是应用）

**App Release（应用发布）**:
应用的一次上线：钉死某个 Flow Version，之后对话按该版本执行。
_Avoid_: deploy, ship

**Builtin Ask（内置问数）**:
与当前硬编码问数等价的节点类型：知识库 → 本体 → 元数据 → SQL 洞察；作为默认可发布流程的主体。
_Avoid_: AskService（实现类名，不是领域词）

**Run（问数回合）**:
一次用户提问及其流水线结果；可归入 Conversation，并可选归入某个 App。
