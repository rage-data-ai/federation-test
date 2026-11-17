# Changelog

## Unreleased

### Added

- 本地账号：自助注册 / 密码登录（会话默认 24h、有请求续期）、管理员维护用户；控制台登录页与用户管理；见 `docs/auth.md`
- 问数对话历史：按登录用户列出并回看自己的会话（`/api/v1/ask/conversations`）
- 问数 ChatFlow 一期：可配置流程 / 发布应用（`docs/chatflow.md`）；`POST /api/v1/ask` 支持 `appId`
- 问数流程画布：Vue Flow 可拖拽 DAG（节点/连线/分支条件）
- 问数应用增删改查入口：编辑名称/说明、删除（归档）；`DELETE /api/v1/apps/{id}`
- 智能问数 DAG 展开为知识库 / 本体 / 元数据 / SQL 洞察四阶段（运行仍折叠为一次 AskService）
- 知识库独立主菜单；类型 `smart_ask` / `general`；对话记忆窗口（开始节点 / 全局配置）
- DAG 调色板支持拖到画布添加节点
- ChatFlow 白盒拆跑：knowledge/ontology/metadata/insight 真实执行并按 outcome 选边；节点可配 `skills`（本体/知识/偏好/SQL）；`llm_chat` 可勾选工具技能
- ChatFlow 节点入参/出参（Dify 变量）：`sys.*`、`{{#nodeId.field#}}`、`config.inputs`、运行时变量池与 `vars` 事件；画布 Inputs/Outputs 面板
- 知识库页「召回测试」：直接测向量/内存命中与分数；`llm_chat` 可配记忆轮数、展示并自由绑定工具包；新增 `answer` 直接回答节点；问数过程展示变量出参

### Changed

- 鉴权始终开启（`FEDERATION_SECURITY_ENABLED=false` 已忽略）；无本地账号时控制台引导首次配置管理员（`/api/v1/auth/setup`）
- 默认 CORS 放行 `127.0.0.1:5173` / `:18080`（原先仅 localhost，本机 IP 访问会改删失败）
- 内置「智能问数」启动时若仍为单节点 `builtin_ask` 图，自动迁移并重新发布
- 知识向量：Milvus 可选；写入/检索失败回退进程内索引，不阻断审核与问数
- 问数补充提示词文案对齐 Hermes：`global`≈`MEMORY.md`，`personal`≈`USER.md`

### Added

- 问数自学习：好评→待审知识、`fed_ask_knowledge` + Milvus 问题向量、命中后模型判定短路；全局/个人补充提示词；建议去向落地（知识/提示词/本体）
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
