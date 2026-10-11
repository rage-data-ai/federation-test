# 跨源联邦查询网关

按《跨源联邦查询应用技术选型 v2》的推荐方案实现：**Java 自研轻量网关 + Trino 联邦主引擎**。

网关负责鉴权、审计、配额、结果缓存、SQL 解析改写和智能路由；跨源 JOIN 交给 Trino。能单源执行的查询不走联邦。

## 架构

```
HTTP /api/v1/query
        │
        ▼
┌────────────────── 自研网关（Java 17 / Spring Boot） ──────────────────┐
│ 鉴权 / 配额 / 审计 / 结果缓存                                         │
│ Calcite 解析 · Kafka 落湖映射 · vector_search() 两阶段召回            │
│ 路由：单源直连 MySQL/PG/CK/Doris → 跨源 Trino → 超大批 Spark（未接入） │
└───────────────┬───────────────────┬──────────────────────────────────┘
                │                   │
         直连 JDBC              Trino 476
         MySQL/PG/CK            mysql / postgresql / clickhouse / mongodb
```

## 快速开始

环境：JDK 17、Maven 3.9+。数据源用你本机已安装的 MySQL / ClickHouse / PostgreSQL / Doris，在控制台里注册，不依赖 Docker。

```bash
mvn test
mvn spring-boot:run
cd web && npm install && npm run dev
```

打开 http://localhost:5173 → **添加数据库** → 填主机/端口/库名/账号 → 测试连接 → 保存。SQL 使用三部分表名：`catalog.schema.table`，例如 `mysql.shop.customer`。配置落在 `data/catalogs.json`。

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

请求体：

```json
{
  "sql": "SELECT * FROM mysql.shop.customer",
  "parameters": {},
  "engine": null,
  "explain": false
}
```

`engine` 可强制指定 `trino` / `direct` / `spark`。生产可把 `federation.security.enabled` 设为 `true`，并带 `Authorization: Bearer demo-token`。

## 示例

单源（网关直连 ClickHouse）：

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

向量两阶段（内存召回演示，可切 Milvus REST）：

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

1. 用户指定 `engine`：按提示走。
2. 只涉及一个 `DIRECT` catalog（mysql / postgresql / clickhouse / doris）：剥掉 catalog 前缀后直连 JDBC。
3. 多 catalog，或 Iceberg / Hive / ES / Mongo / Redis / Kafka / Paimon / Lance：交给 Trino。
4. `vector_search(...)`：网关先召回 `(id, score)`，改写成 `VALUES` 再 JOIN。
5. `kafka.events.orders` 默认改写为 `paimon.ods.orders`（见 `federation.kafka-mappings`）。

SQL 以 `EXPLAIN` 开头时，前缀会原样下发给引擎，用来观察 Trino 下推。`/explain` 只返回网关计划。

## 配置要点

- Catalog 定义在 `src/main/resources/application.yml`。
- Trino catalog 在 `docker/trino/catalog/`。
- 接入真实 Milvus：设置 `federation.vector.milvus-uri`，例如 `http://localhost:19530`。
- Doris 走 MySQL 协议直连（默认 `localhost:9030`）；未启动时不要把 Doris 主导查询打过去。
- Spark 路由已识别，PoC 未接 Thrift/JDBC，调用会返回 501。

## 文档中尚未纳入默认 Compose 的部分

Hive Metastore、Iceberg REST、Doris、Milvus、Kafka、ES、Flink、Spark 可按选型文档第 8 节继续加。网关侧 catalog 和改写入口已经留好。
