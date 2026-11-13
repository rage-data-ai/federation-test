# Changelog

## Unreleased

### Added

- `/api/v1/health` 附带问数嵌入 / pgvector 运维信号（无密钥）
- Playwright + axe 关键页面 WCAG 扫描；`docs/a11y-checklist.md` 人工 AT 清单
- `scripts/verify-all.sh` 一键对齐 CI 门禁
- 可选 pgvector ANN（`hnsw` / `ivfflat` / `none`，失败降级精确扫描）
- JDBC / file / both 术语嵌入持久化；DraftDialog 与数据源对话框焦点陷阱
- Playwright 冒烟与视觉基线（CI ubuntu-24.04）；Error Prone；CycloneDX SBOM
- HTTP/NIM + 哈希嵌入；ADR 0001；springdoc；Redis denylist；SpotBugs；版本/certify UI

### Changed

- Dependabot 分组；`minimatch`/`tinypool` overrides；生产 `npm audit --omit=dev` 目标 0
- 问数关键路径 ARIA（log / samples / composer / trace）
- 亮色主题 `--muted` 对比度达标；数据源状态点 `role=img` + `aria-label`
- 本体变更时清理术语向量内存缓存；无字面命中时嵌入 → n-gram

### Security

- `SECURITY.md` 声明 vitest@3 剩余 moderate 为开发链接受风险
