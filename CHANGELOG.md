# Changelog

## Unreleased

### Added

- Playwright 冒烟与视觉基线（CI）；对话框焦点陷阱（`useFocusTrap`）
- Error Prone；CycloneDX SBOM；GitHub Issue/PR 模板
- 术语向量持久索引；HTTP/NIM + 哈希嵌入；ADR 0001；springdoc；Redis denylist
- SpotBugs；版本/certify UI；问数护栏；a11y 基础

### Changed

- 本体变更时清理术语向量内存缓存
- 字面无命中时走嵌入索引，再回退 n-gram

