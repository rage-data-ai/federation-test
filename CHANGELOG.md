# Changelog

## Unreleased

### Added

- 术语向量持久索引（`OntologyEmbeddingIndex` 落盘缓存）
- 可插拔 HTTP/NIM embeddings + 本地哈希嵌入；ADR 0001
- springdoc OpenAPI；Redis denylist；SpotBugs；版本/certify UI
- 问数 answerable / filter / steps / sqlExpression；OIDC Discovery；a11y 基础

### Changed

- 字面无命中时走嵌入索引（可复用缓存），再回退 n-gram；HTTP 失败回退哈希
