# 发布指南

打 `v*` 标签后，`.github/workflows/release.yml` 会跑后端 `mvn verify`、前端生产 audit + build，并创建 GitHub Release（附 jar 与 CycloneDX `bom.json`）。

## 步骤

1. 确认 `main` 绿：`./scripts/verify-all.sh`（或等价 CI）。
2. 把 `CHANGELOG.md` 的 `## Unreleased` 落成 `## x.y.z — YYYY-MM-DD`（Asia/Shanghai），并开新的 Unreleased。
3. 将 `pom.xml` 的 `<version>` 从 `*-SNAPSHOT` 改为 `x.y.z`；可选同步 `web/package.json` 的 `version`。
4. 提交：`chore: release x.y.z`。
5. 打标签并推送：

```bash
git tag -a "vx.y.z" -m "vx.y.z"
git push origin main "vx.y.z"
```

6. 在 GitHub Releases 核对产物；若需继续开发，立刻把 `pom.xml` 调回下一版 `*-SNAPSHOT` 并提交。

## 注意

- 标签必须以 `v` 开头（例如 `v1.0.0`）。
- Release 工作流会执行完整 `mvn verify`（含 Testcontainers IT，需 runner Docker）。
- 不要把密钥写入 Release notes；敏感配置仅通过环境变量注入（见 `SECURITY.md`）。
