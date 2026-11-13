# Changelog

## Unreleased

### Added

- 可插拔 HTTP/NIM embeddings（`HttpTermEmbedder`）+ 本地哈希嵌入
- springdoc OpenAPI / Swagger UI（`/swagger-ui.html`）
- Redis 令牌吊销 denylist；SpotBugs；本体版本/certify UI；n-gram / 模糊检索
- 问数 answerable / filter validation / steps / sqlExpression；OIDC Discovery
- 控制台 a11y（skip-link、aria-live）

### Changed

- 字面无命中时优先嵌入召回，再回退 n-gram；HTTP 失败自动回退哈希
