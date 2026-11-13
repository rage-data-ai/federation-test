# 本体驱动的智能问数：设计决策

状态：设计已确认并已落地（控制台 + API + 单测）；持续按 `docs/design-review.md` 迭代工程化（2026-11-13）

参考文章（同一系列，务必先读原文）：

- Part 1 为什么需要 Ontology：https://mp.weixin.qq.com/s/-SznuaSqg5kq5y-LcPhFwg
- Part 2 Protégé 构建 Ontology（表 → OWL 映射）：https://mp.weixin.qq.com/s/KchHHsrCno22h_Hp9hZJSg
- Part 3 Ontology 驱动的 Data Agent：https://mp.weixin.qq.com/s/H4Wiox43LvRhVJZ4bLzH-Q
- 参考代码：https://github.com/tianputao/AW_Sample_Ontology 、https://github.com/tianputao/Data-Insight-Agent-With-Ontology

## 1. 背景与教训

上一版本体是从表结构自动生成的，只有表名和列名，用户用业务语言提问时对不上，已删除。

这一版按文章的思路做：OWL 本体是**语义元数据层**，业务数据仍在各数据源中，通过 Trino 用 SQL 查询。

## 2. 定位

| 项 | 决定 |
|---|---|
| 用途 | 智能问数：大模型借助本体理解业务问题并生成 SQL |
| 第一个业务域 | 告警—资产 |
| 本体由谁来建 | 大模型根据元数据和抽样行起草，人在控制台修改 |

## 3. 本体内容与存储

- **存储**：Apache Jena TDB2，嵌入网关进程，目录为 `data/tdb2`。
- **TDB2 里存的内容**：
  - 类、DatatypeProperty、ObjectProperty；
  - 中英文 `rdfs:label`、多个中文别名、`rdfs:comment`；
  - domain/range、子类、`TransitiveProperty`、`inverseOf`、`FunctionalProperty`、`disjointWith`；
  - 派生概念，用 `equivalentClass` + Restriction 表达，例如"高危告警"、"失陷主机"；
  - 码值个体，例如 `level=3` 表示高危；
  - 本体到物理 `catalog.schema.table` / 列的映射，以及 Join 路径。
- **不存的内容**：业务数据实例。抽样行只在起草时临时使用，不入库。
- **指标**：属性仍角色中立；派生概念可选 `sqlExpression`（可复用 SELECT 度量，对齐 Auto Ontology SQL attributes），过滤仍用 conditions/`sqlCondition`。
- **映射规则**：参照 Part 2 第 1.2 节：

  | 关系型 | OWL |
  |---|---|
  | 表 | Class |
  | 非外键列 | DatatypeProperty + xsd 类型 |
  | 外键 | ObjectProperty |
  | 枚举码值 | NamedIndividual |
  | 父子层级 | TransitiveProperty |

  xsd 类型约定：金额用 `decimal`，不要用 `double`。
- **NULL 处理**：参照 Part 2 第 1.3 节。数据属性为 NULL 时不生成三元组；指向枚举的属性为 NULL 时，生成 `unknown_xxx` 个体。

## 4. 推理

- 使用 **Jena 内置规则推理器**，负责子类、传递、逆属性、domain/range 推理。
- "level ≥ 3"这类数值区间的等价类，规则推理器不支持，**在编译 SQL 时转成 WHERE 条件处理**。
- 不用 Openllet。它最新的 2.6.5 发布于 2019 年，Jena 集成模块绑定 Jena 3.10，和 TDB2 新版冲突。
- **推理先在内存中做，再落到 TDB2**：
  - 断言三元组存在 TDB2 默认图，推理结果存在命名图 `urn:fed:graph:inferred`，两者分开。
  - 每次写入在同一个写事务里完成：执行修改 → 把断言图复制到内存 → OWL Micro 推理 → 推理出的新三元组整体替换推理图 → 提交。删除实体后，它的旧推理结果也随之清除。
  - 启动时如果有断言但推理图为空（例如旧数据），自动补做一次推理。也可以调用 `POST /api/v1/ontology/reason` 手动重新推理。
  - 带推理的查询直接读"默认图 + 推理图"，不再临时推理；读出的内存快照缓存到下一次写入。

## 5. 版本基线

- Spring Boot 4.1.1，已于 2026-10-20 从 3.4.5 升级，Jackson 迁移到 3.x（`tools.jackson`）。
- **Java 升级到 21**，Jena 用 **6.2.x**（Jena 6 要求 Java 21）。
- 智能体框架：**LangChain4j**。它的 Spring Boot starter 是否支持 Boot 4.1 需要核实；如果不支持，就直接用核心库。

## 6. 本体构建流程

1. 用户在控制台勾选表，按业务域分批起草。
2. 网关为每张表取列名、类型和少量抽样行（抽样行可以发给大模型），由大模型按第 3 节的映射规则起草三元组，直接写入 TDB2。
3. **起草即生效**：不区分草稿和正式版，人可以在控制台随时修改。
4. **审定（certify）**：概念/派生概念可标记 `certified`；问数优先采用已审定口径，未审定会告警。
5. **版本快照**：起草前自动写入 `federation.ontology-versions`（默认 `data/ontology-versions`）；可用 `GET/POST /api/v1/ontology/versions` 与 `POST .../versions/{id}/restore` 回滚。
5. 标签要求中英文 label，加多个中文别名和 comment。
6. 支持的数据源：MySQL、ClickHouse、Elasticsearch。**Kafka 这次不做**，因为 topic 在 Trino 里只有 `_message` 原始字段。

## 7. 智能问数链路

### 7.1 模型

兼容 OpenAI 和 Anthropic 两种协议，**部署时通过配置选一个**，不做按任务分档，也不做界面切换。

### 7.2 三个子智能体（参照 Part 3）

固定链路为 `Ontology → Metadata → DataInsight`。**本体始终开启**，没有会话级开关。每一层只对一种事实负责，不越权：

- **Ontology 智能体**：只回答业务语义问题。
  - 通过**参数化工具**查 TDB2，工具内部用 SPARQL，大模型不直接写 SPARQL。
  - 工具参照 Part 3 第 4.2 节：`search_entities`、`describe_entity`、`expand_neighbors`、`find_paths`、`get_schema_mapping`、`get_join_paths`、`get_business_context`、`list_defined_classes` 等。
  - `get_business_context` 返回 `status` 和 `confidence`。状态不是 `ok`，或者置信度低于阈值时，转入多轮工具探索（Part 3 第 3.4 节）。
- **Metadata 智能体**：只回答物理事实问题。
  - **只使用本体里已映射的表**，未映射的表一律不用。
  - 用现有的 `MetadataStore`（MySQL `federal.fed_meta_table` / `fed_meta_column`）核对表、列、类型是否真实存在。
  - 核对时按属性的声明 domain 分组（Part 3 第 5.2 节）。
- **DataInsight 智能体**：结合语义和物理两份证据规划 SQL、执行，并给出解释。规划步骤参照 Part 3 第 6.2 节的 `sql-planning` 六步。

### 7.3 SQL 代码级校验（全部启用）

1. 只允许 SELECT。
2. 用 Calcite 解析，只允许引用已注册的 `catalog.schema.table`。
3. 执行前先 explain，失败就不执行。
4. 沿用现有的 `max-rows`、`query-timeout-seconds`。
5. 大表（如 `ck81.pangu.alert`）必须带时间范围条件，否则拒绝执行。

所有执行都走现有的 `QueryService` 链路，不直连 JDBC。

### 7.4 恢复与诊断（参照 Part 3 第 6.4、6.5 节）

- 本体恢复、元数据恢复，每次请求各最多 1 次。
- 结果为空、全零、常量、两两相同等退化形态时，最多追加 1 次诊断 SQL。

### 7.5 交互与展示

- 通常直接执行。**口径缺失或有歧义时停下来问用户**，不自己编门槛（参照 Part 3 第 9.2 节）。
- 前端通过 SSE 流式展示每个子智能体的过程：命中的本体概念、核对过的表和列、SQL。最后给出结果表、结论和口径来源，口径来源要区分"本体定义"和"推断"。

### 7.6 借鉴 NVIDIA Auto Ontology 的工程加固（2026-11-14）

参考 [NVIDIA/auto-ontology](https://github.com/NVIDIA/auto-ontology) 的公开流程（Discover → Ground → Validate → Answer），在不引入 pgvector/NIM 的前提下落地：

| Auto Ontology | 本网关落地 |
|---|---|
| `check_answerable` | `POST /api/v1/ask/answerable` + SSE `answerability` + 工具 `checkAnswerable` |
| 强制复用 SQL attributes | Insight 提示强制复用 `definedConcept.sqlCondition` |
| filter value validation | 工具 `validateFilterValues` / `scanFilterLiterals`；空结果自动扫字面量 |
| Chat steps 溯源 | `answer.steps`（解析术语 / 派生口径 / 关联 / 元数据 / 过滤校验） |
| 语义编译同义词 | 起草规则要求 aliases；码值别名利于词表命中 |
| certify 审定 | `certified` 字段 + `PUT /api/v1/ontology/{name}/certified` + 控制台勾选；问数优先已审定 |
| SQL attributes | `definedConcept.sqlExpression`（度量）与 `sqlCondition`（过滤） |
| embedding 术语检索 | `TermEmbedder`：默认本地哈希；配置 `federation.ask.embed-endpoint` 后走 OpenAI 兼容 HTTP（NIM）；失败回退哈希；再辅 n-gram / 编辑距离 |

未搬迁（体量大 / 依赖不同）：pgvector 持久索引、Redis 语义目录。

### 7.7 后续再做

- 治理 SQL 模板快速通道（Part 3 的 `analytics-spec`）。
- Kafka 数据源的本体映射。
- 可选：pgvector 持久化术语向量。

## 8. 已知风险

- 只用已映射的表，所以问到本体里还没起草的表时，系统会直接说答不了。
- 起草即生效；已提供版本快照/回滚，但误改后仍依赖运维主动恢复。
- 当前环境中资产主表 `mysql70.tsoc.asset` 是 0 行，告警—资产 Join 查不出结果。资产以哪张表为准，到起草时由人来定。

## 9. 告警—资产域已知事实（2026-10-20 采集）

- 数据源：
  - `mysql70.tsoc`：586 张表，资产相关，如 `asset`、`asset_business_system`、`asset_department`、`asset_data_type`；
  - `ck81.pangu`：159 张表，告警和事件，如 `alert`、`alert_raw`、`alert_relation`、`event_all_view`、`cache_asset`；
  - `es81`；
  - `kafka81`（暂不做）。
- 存在大量噪音表：`_bak`、`_local`、`mat_phy_<数字>`、`event2026xxxx_local`。
- `ck81.pangu.alert` 有 225 列，数据量很大，`count(*)` 超过 60 秒。
- 告警和资产通过资产 ID 关联：`assetId`、`srcAsset`、`dstAsset`、`devAsset` 都关联 `mysql70.tsoc.asset.id`。这四种角色的业务含义待起草时确认。
- 告警表中的码值字段：`level`、`status`、`mergeStatus`、`vulGrade`、`alertType`。资产表中的码值字段：`type`、`status`、`protectlevel`、`risklevel`。

## 10. 实现顺序（建议）

1. 升级到 Java 21，引入 Jena 6.2 和 TDB2，实现本体存储与 SPARQL 查询服务，以及规则推理。
2. 本体编辑 API 和控制台：类、属性、映射、码值的增删改查。
3. 大模型起草：勾选表 → 抽样 → 起草 → 写入 TDB2。
4. Ontology 智能体的工具集，包括 `get_business_context` 和置信度计算。
5. Metadata 智能体与 DataInsight 智能体、SQL 五道校验、恢复和诊断。
6. `/api/v1/ask` SSE 接口和前端问数页面。

## 11. 实现情况（2026-10-20）

六步均已实现，单元测试覆盖本体存储与推理、起草写入、业务上下文与置信度、SQL 校验、三智能体编排（用脚本化的假模型跑通工具调用）。连真实数据库和大模型的端到端验证尚未做。

### 11.1 配置

大模型在控制台左侧「模型」里配置，写入 MySQL `federal.fed_llm`，保存后立即生效，不用重启。环境变量 `LLM_*` 只作为库里还没有配置时的初始值。数据源在 `fed_catalog`（自增 `id` 主键，名称唯一），元数据在 `fed_meta_table` 和 `fed_meta_column`。`fed_meta_table.catalog_id` 外键指向 `fed_catalog.id`，删除数据源时一并删除它的表和列。不再写 `data/*.json`。本体图仍在 `data/tdb2`。账号沿用已注册的 `192.168.19.70` MySQL，库为 `federal`。

| 变量 | 说明 |
| --- | --- |
| `LLM_PROTOCOL` | `openai` 或 `anthropic`，默认 `openai` |
| `LLM_BASE_URL` | 兼容接口地址，如 `https://api.openai.com/v1` |
| `LLM_API_KEY` | 密钥 |
| `LLM_MODEL` | 模型名 |

`federation.ask.*` 可调：`min-confidence`（默认 0.5，低于它转多轮工具探索）、`max-path-depth`（关系路径最大跳数，默认 4）、`sample-rows`（起草时每表样例行数，默认 3）、`result-preview-rows`（返回给大模型和页面的结果行数，默认 50）。

### 11.2 与上文设计的差异

- **Metadata 环节是确定性代码，不是大模型智能体**。它只做"核对本体映射的表和列是否在 `MetadataStore` 中存在"，用代码更可靠也更快。缺表时自动按表重采一次，算作本次请求的那 1 次元数据恢复；DataInsight 仍可在 Trino 报错时调用 `recoverMetadata`，但同样受"每次请求 1 次"约束。
- **预校验用 `EXPLAIN (TYPE VALIDATE)`**，不用 IO/分布式计划。`ck81.pangu.alert` 上 `EXPLAIN (TYPE IO)` 曾 61 秒超时，VALIDATE 只做语义检查。预校验直接走 `QueryExecutor`（`QueryService` 会把 `EXPLAIN (TYPE …)` 再包一层 `EXPLAIN`），真正的执行仍走 `QueryService`。
- **Calcite 解析使用双引号标识符、大小写不敏感、LENIENT 方言**。解析失败即拒绝，并把原因回给大模型让它改写。表范围检查遍历整棵语法树（CTE、子查询、UNION 分支、IN 子查询），表必须写 `catalog.schema.table` 全限定名。Trino 476 自带解析器需要 Java 24，故未采用。
- **大表时间过滤**：同一层 SELECT 的 WHERE 或 JOIN ON 中，必须有对该表时间列（按别名匹配）的 `>`、`>=`、`<`、`<=` 或 `BETWEEN` 比较；放在 CTE 或子查询里过滤也算。OR 需要每个分支都带时间条件。
- **多轮工具探索的输出**是一组实体名，随后用这组实体（不再做词面匹配）重建业务上下文，从而消解歧义；探索智能体也可以直接向用户澄清。
- 大模型返回 JSON 时容忍代码块和多余字段，缺失的布尔字段按 false 处理。

### 11.3 接口

- `GET /api/v1/llm/status`：是否已配置、协议、模型名。
- `POST /api/v1/ontology/draft`：`{tables: ["ck81.pangu.alert"], instructions}` → 写入数量与警告。
- `POST /api/v1/ask`（SSE）：`{question, history: [{role, content}]}`。事件依次为 `stage`、`ontology`、`metadata`、`sql`（每次尝试，状态 executed / rejected / failed）、`result`、最后是 `answer`（结论、SQL、结果、口径来源）或 `clarify`（问题与选项），出错为 `error`，结束发 `done`。
