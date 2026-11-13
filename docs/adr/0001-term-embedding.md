# ADR 0001：问数术语嵌入策略

## 状态

已采纳（2026-11-14 Asia/Shanghai）

## 背景

NVIDIA Auto Ontology 用 NIM embeddings + pgvector 做术语召回。本网关需在无强制外部向量库时仍具备可用的语义召回，并允许生产接入 NIM。

## 决策

1. `TermEmbedder` 可插拔：默认 `HashingTermEmbedder`；配置 `federation.ask.embed-endpoint` 后用 `HttpTermEmbedder`（OpenAI 兼容，含 NIM）。
2. `OntologyEmbeddingStore` 可插拔：`file` / `jdbc` / `both`（默认 `both`）。`OntologyEmbeddingIndex` 按词表指纹缓存（内存 → JDBC `fed_ontology_embedding` → 落盘），避免每次问数全量重嵌。
3. 可选 `federation.ask.pgvector-url`：启用 `PgvectorTermRetriever`（`<=>` 余弦距离）。URL 空或连接失败时自动 disabled，不硬依赖外部 Postgres。
4. 召回顺序：字面命中 → **pgvector（若启用）** → 嵌入索引 → n-gram → 编辑距离（searchEntities）。

## 后果

- 默认零外部向量库即可语义软召回；联邦库可用时跨实例共享索引（对齐 Auto Ontology 持久术语索引）。
- 生产可挂真 pgvector；开发/CI 无 Postgres 时行为不变。
- HTTP 失败自动回退哈希，问数不中断。
