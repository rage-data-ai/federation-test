# 本体驱动的智能问数：设计决策

状态：设计已确认，待实现（2026-10-20）

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
- **指标处理"角色中立"**：本体不写聚合公式，只写属性含义、单位、是否唯一标识这类事实。比如可以写"`alert.id` 唯一标识一条告警"，至于怎么聚合由 DataInsight 判断。
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

## 5. 版本基线

- Spring Boot 4.1.1，已于 2026-10-20 从 3.4.5 升级，Jackson 迁移到 3.x（`tools.jackson`）。
- **Java 升级到 21**，Jena 用 **6.2.x**（Jena 6 要求 Java 21）。
- 智能体框架：**LangChain4j**。它的 Spring Boot starter 是否支持 Boot 4.1 需要核实；如果不支持，就直接用核心库。

## 6. 本体构建流程

1. 用户在控制台勾选表，按业务域分批起草。
2. 网关为每张表取列名、类型和少量抽样行（抽样行可以发给大模型），由大模型按第 3 节的映射规则起草三元组，直接写入 TDB2。
3. **起草即生效**：不区分草稿和正式版，人可以在控制台随时修改。
4. **不做版本管理**。
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
  - 用现有的 `MetadataStore`（`data/metadata.json`）核对表、列、类型是否真实存在。
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

### 7.6 后续再做

- 治理 SQL 模板快速通道（Part 3 的 `analytics-spec`）。
- Kafka 数据源的本体映射。

## 8. 已知风险

- 只用已映射的表，所以问到本体里还没起草的表时，系统会直接说答不了。
- 起草即生效，又没有版本管理。大模型起草出错或被人误改时没有回滚手段，只能重新起草那批表。
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
