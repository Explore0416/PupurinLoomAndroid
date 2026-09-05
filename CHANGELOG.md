# Changelog

## [0.4.2] - 2026-09-05

- **存储**：项目数据从私有目录迁移到公共共享目录 `/storage/emulated/0/PupurinLoom/projects/`，Android 11+ 需「所有文件访问」授权，首启引导。
- **云端打包**：内置官方打包服务器 `maker.lightning-team.de5.net`；支持自建服务器（见 `docs/cloud-builder.md`）。
- **竖屏适配**：手机竖屏下不再横向溢出、文字不撑爆宽度。
- **后台行为**：移除画中画(PiP)悬浮窗，按 Home / 切后台正常退到后台。
- **工程化**：接入 GitHub Actions，push 自动构建 APK；打 `v*` 标签自动发布 GitHub Release。

## [0.4.1] - （历史基线）

- 桌面版 [PupurinOfficial/PupurinLoom](https://github.com/PupurinOfficial/PupurinLoom) 的 Android 移植初版：WebView 渲染层 + JS Bridge + 本地化解析，横竖屏 / 小米自由窗口适配、插件系统、运行已导出游戏等。