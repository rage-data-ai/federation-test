# Changelog

## Unreleased

### Added

- 本地哈希词嵌入术语召回（`HashingTermEmbedder` / `EmbeddingTermRetriever`）
- Redis 令牌吊销 denylist（`federation.security.revocation-redis-uri`）
- SpotBugs High 门禁；本体版本控制台；certify UI；n-gram / 模糊检索
- 问数 answerable / filter validation / steps / sqlExpression；OIDC Discovery
- 控制台 a11y（skip-link、aria-live）

### Changed

- 字面命中不足时优先嵌入召回，再回退 n-gram
