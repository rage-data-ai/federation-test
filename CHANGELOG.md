# Changelog

## Unreleased

### Added

- Error Prone（窄规则）编译门禁；CycloneDX SBOM；GitHub Issue/PR 模板
- 术语向量持久索引；HTTP/NIM + 哈希嵌入；ADR 0001；springdoc；Redis denylist
- SpotBugs；版本/certify UI；问数护栏；a11y 基础

### Changed

- 本体变更时清理术语向量内存缓存
- 字面无命中时走嵌入索引，再回退 n-gram
