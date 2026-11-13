# Changelog

## Unreleased

### Added

- 打 `v*` 标签触发 GitHub Release（jar + CycloneDX SBOM）；`docs/RELEASING.md`
- `/api/v1/health` 附带问数嵌入 / pgvector 运维信号（无密钥）
- Playwright + axe 关键页面 WCAG 扫描；`docs/a11y-checklist.md` 人工 AT 清单
- `scripts/verify-all.sh` 一键对齐 CI 门禁
- 可选 pgvector ANN（`hnsw` / `ivfflat` / `none`，失败降级精确扫描）
- JDBC / file / both 术语嵌入持久化；DraftDialog 与数据源对话框焦点陷阱
- Playwright 冒烟与视觉基线（CI ubuntu-24.04）；Error Prone；CycloneDX SBOM
- HTTP/NIM + 哈希嵌入；ADR 0001；springdoc；Redis denylist；SpotBugs；版本/certify UI

### Changed

- 控制台升级 `vitest@5`（Node ≥ 22.12）；移除无用 `tinypool` override；保留 `minimatch@^11`
- CI frontend 增加 `npm audit --omit=dev`
- Dependabot 分组；生产 `npm audit --omit=dev` 目标 0
- 问数关键路径 ARIA（log / samples / composer / trace）
- 亮色主题 `--muted` 对比度达标；数据源状态点 `role=img` + `aria-label`
- 本体变更时清理术语向量内存缓存；无字面命中时嵌入 → n-gram

### Security

- `npm audit` 全量 0（vitest 5 清除原 `@vitest/mocker` moderate）
