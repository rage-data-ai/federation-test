# 无障碍验收清单（人工 AT）

自动化（Playwright + axe）覆盖结构与常见 WCAG 规则，**不能替代** VoiceOver / NVDA / TalkBack 真人走查。

## 自动化已覆盖

- 对话框：`role=dialog` / `aria-modal`、焦点陷阱、Escape（鉴权、起草、数据源表单等）
- 问数：对话 `role=log`、默认问题 `role=group`、提问区 `region`、发送按钮语义
- 数据源：网关状态点 `role=img` + `aria-label`；亮色 muted 文本对比度
- CI：`npm run test:e2e` 含 axe（wcag2a/aa，阻断 serious/critical）

## 人工 AT 必做路径

用屏幕阅读器依次确认：

1. **Skip link** → 跳到 `main`，焦点可见。
2. **模块轨**：播报「数据源 / 本体 / 问数 / 身份 / 模型」；`aria-current` 正确。
3. **鉴权对话框**：打开后焦点进输入框；Tab 循环不逃逸；Escape 关闭并还焦。
4. **问数**：欢迎文案与默认问题可到达；发送/停止状态可感知；过程展开按钮播报 expanded。
5. **本体起草 / 数据源添加**：对话框标题、错误 `alert`、忙状态。

记录工具、OS、浏览器与结论；通过后可在 PR 注明「人工 AT：通过 YYYY-MM-DD」。
