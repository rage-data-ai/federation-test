# 跨源联邦查询网关

按《跨源联邦查询应用技术选型 v2》的推荐方案实现：**Java 自研轻量网关 + Trino 联邦主引擎**。

网关负责鉴权、审计、配额、结果缓存、SQL 解析改写和智能路由；跨源 JOIN 交给 Trino。能单源执行的查询不走联邦。控制台另提供**本体驱动的智能问数**（Ontology → Metadata → DataInsight）。

## 架构

```
HTTP /api/v1/query  ·  /api/v1/ask (SSE)
        │
        ▼
┌────────────────── 自研网关（Java 21 / Spring Boot） ──────────────────┐
│ Bearer 鉴权 / 配额 / 审计 / 结果缓存                                    │
│ Calcite 解析 · Kafka 落湖映射 · vector_search() 两阶段召回            │
│ 路由：单源直连 MySQL/PG/CK/Doris → 跨源 Trino → Spark（未接入，501）   │
│ 本体（Jena TDB2）· 元数据 · LangChain4j 问数编排                      │
└───────────────┬───────────────────┬──────────────────────────────────┘
                │                   │
         直连 JDBC              Trino
         MySQL/PG/CK            mysql / postgresql / clickhouse / …
```

设计评估见 [`docs/design-review.md`](docs/design-review.md)；本体设计见 [`docs/ontology-design.md`](docs/ontology-design.md)；OIDC/JWT 见 [`docs/oidc.md`](docs/oidc.md)。

## 快速开始

环境：JDK 21、Maven 3.9+、Node 20+。需要一台 MySQL 作为网关配置库（`federal`），以及可选的 Trino。

```bash
cp .env.example .env   # 填写 FEDERAL_DB_* 等
# 或本地：
export FEDERAL_DB_URL='jdbc:mysql://127.0.0.1:3306/federal?sslMode=DISABLED&allowPublicKeyRetrieval=true&characterEncoding=utf8'
export FEDERAL_DB_USER=federation
export FEDERAL_DB_PASSWORD=federation

mvn test
mvn spring-boot:run
# 也可：SPRING_PROFILES_ACTIVE=dev mvn spring-boot:run（见 application-dev.yml）

cd web && npm install && npm run dev
```

打开 http://localhost:5173 → **添加数据库** → 测试连接 → 保存。SQL 使用三部分表名：`catalog.schema.table`，例如 `mysql.shop.customer`。

数据源、元数据和模型配置存在配置库的 `fed_catalog`、`fed_meta_table`、`fed_meta_column`、`fed_llm`。本体图在 `data/tdb2`。

一键体验（MySQL + Trino + 网关，控制台打进网关镜像，打开 http://localhost:18080）：

```bash
docker compose up -d --build
```

仅启动 Trino 时也可 `docker compose up -d trino`。

## 接口

| 方法 | 路径 | 说明 |
|------|------|------|
| POST | `/api/v1/query` | 执行 SQL |
| POST | `/api/v1/explain` | 只看网关路由和改写，不下发引擎 |
| GET | `/api/v1/catalogs` | 列出已注册数据源 |
| POST | `/api/v1/catalogs` | 新增数据源 |
| PUT | `/api/v1/catalogs/{name}` | 更新数据源 |
| DELETE | `/api/v1/catalogs/{name}` | 删除数据源 |
| POST | `/api/v1/catalogs/test` | 测试连接 |
| GET | `/api/v1/health` | 健康检查 |
| GET | `/api/v1/auth/me` | 当前身份（Bearer / JWT） |
| GET | `/api/v1/llm/status` | 大模型是否已配置 |
| PUT | `/api/v1/llm/config` | 写入大模型配置 |
| POST | `/api/v1/ask` | 智能问数（SSE） |
| POST | `/api/v1/ontology/draft` | 按表起草本体 |
| GET/POST/PUT/DELETE | `/api/v1/ontology/**` | 本体读写与推理 |
| PUT | `/api/v1/ontology/{name}/certified` | 审定概念/派生概念（问数优先） |
| GET/POST | `/api/v1/ontology/versions/**` | 本体快照与回滚 |

请求体：

```json
{
  "sql": "SELECT * FROM mysql.shop.customer",
  "parameters": {},
  "engine": null,
  "explain": false
}
```

`engine` 可强制指定 `trino` / `direct` / `spark`。生产可把 `FEDERATION_SECURITY_ENABLED=true`，并带 `Authorization: Bearer <token>`：

| 用户 | 默认令牌环境变量 | 角色 |
|------|------------------|------|
| `demo` | `FEDERATION_DEMO_TOKEN` | `QUERY,ADMIN` |
| `reader` | `FEDERATION_READER_TOKEN` | `QUERY`（只读查询/问数） |

写入数据源、元数据、本体、模型配置需要 `ADMIN`。可选 `OTEL_EXPORTER_OTLP_ENDPOINT` 导出 tracing。多实例吊销可配 `FEDERATION_SECURITY_REVOCATION_REDIS_URI`（或 `federation.security.revocation-redis-uri`）。

## 示例

单源（网关直连 JDBC，catalog 为 DIRECT）：

```bash
curl -s localhost:18080/api/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT n_regionkey, count(*) AS cnt FROM clickhouse.tpch.nation GROUP BY 1"}'
```

跨源 JOIN（走 Trino）：

```bash
curl -s localhost:18080/api/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT c.name, n.n_name, r.r_name FROM mysql.shop.customer c JOIN clickhouse.tpch.nation n ON c.nationkey = n.n_nationkey JOIN postgresql.shop.region r ON n.n_regionkey = r.r_regionkey"}'
```

向量两阶段（默认内存召回演示；设置 `MILVUS_URI` 可切 Milvus REST）：

```bash
curl -s localhost:18080/api/v1/query \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT p.name, v.score FROM vector_search('\''milvus.products'\'', '\''embedding'\'', :qvec, k => 3, filter => '\''category == \"phone\"'\'') v JOIN mysql.shop.products p ON p.id = v.id","parameters":{"qvec":[1.0,0.0,0.0]}}'
```

看路由：

```bash
curl -s localhost:18080/api/v1/explain \
  -H 'Content-Type: application/json' \
  -d '{"sql":"SELECT c.name, n.n_name FROM mysql.shop.customer c JOIN clickhouse.tpch.nation n ON c.nationkey = n.n_nationkey"}'
```

更多 SQL 见 `src/main/resources/demo.sql`。

## 路由规则

1. 用户指定 `engine`：按提示走（`spark` 返回 501）。
2. `SHOW CATALOGS`：网关本地列出已注册数据源。
3. 只涉及一个 `DIRECT` 且可 JDBC 直连的 catalog（mysql / postgresql / clickhouse / doris）：剥掉 catalog 前缀后直连。
4. 多 catalog，或 Iceberg / Hive / ES / Mongo / Redis / Kafka / Paimon / Lance：交给 Trino。
5. `vector_search(...)`：网关先召回 `(id, score)`，改写成 `VALUES` 再 JOIN。
6. `kafka.events.orders` 等可在 `federation.kafka-mappings` 中改写为湖表。

SQL 以 `EXPLAIN` 开头时，前缀会原样下发给引擎。`/explain` 只返回网关计划。

## 配置要点

| 变量 | 说明 |
|------|------|
| `FEDERAL_DB_URL` / `USER` / `PASSWORD` | 网关配置库（必填） |
| `TRINO_JDBC_URL` | Trino JDBC |
| `FEDERATION_SECURITY_ENABLED` | 是否启用 Bearer 鉴权 |
| `FEDERATION_DEMO_TOKEN` | demo 用户令牌 |
| `LLM_*` | 大模型初始值（也可在控制台写入 `fed_llm`） |
| `MILVUS_URI` | 非空则用 Milvus REST，否则内存演示 |

- Trino catalog 文件目录：`federation.trino.catalog-dir`（默认 `docker/trino/catalog`）。
- Doris 走 MySQL 协议直连（默认端口 `9030`）。
- Spark 路由已识别，PoC 未接执行器，调用返回 501。

## 开发

```bash
mvn -B verify          # 单测 + JaCoCo 门槛 + Spotless
cd web && npm ci && npm run lint && npm test && npm run build
```

CI：`.github/workflows/ci.yml`（后端 `verify` + 前端 lint/test/build）。License：Apache-2.0。

## 文档中尚未纳入默认 Compose 的部分

Hive Metastore、Iceberg REST、Doris、Milvus、Kafka、ES、Flink、Spark 可按选型文档继续加。网关侧 catalog 和改写入口已经留好。
