# ADR 0001：问数术语嵌入策略

## 状态

已采纳（2026-11-14 Asia/Shanghai）

## 背景

NVIDIA Auto Ontology 用 NIM embeddings + pgvector 做术语召回。本网关需在无强制外部向量库时仍具备可用的语义召回，并允许生产接入 NIM。

## 决策

1. `TermEmbedder` 可插拔：默认 `HashingTermEmbedder`；配置 `federation.ask.embed-endpoint` 后用 `HttpTermEmbedder`（OpenAI 兼容，含 NIM）。
2. `OntologyEmbeddingIndex` 按词表指纹缓存向量（内存 + 落盘 `ontology-embeddings/`），避免每次问数全量重嵌。
3. 召回顺序：字面命中 → 嵌入索引 → n-gram → 编辑距离（searchEntities）。

## 后果

- 默认零外部依赖即可语义软召回。
- HTTP 失败自动回退哈希，问数不中断。
- 真 pgvector 可作为后续替换落盘格式，接口保持 `TermEmbedder` / 索引边界。
