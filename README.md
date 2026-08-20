<div align="center">

<img src="assets/icon.svg" width="96" alt="LauSo 应用图标" />

# LauSo

**ColorOS Launcher 长按菜单标语模块**

[![Release](https://img.shields.io/github/v/release/TheKingBucket001/Lauso?display_name=tag&label=release&color=brightgreen)](https://github.com/TheKingBucket001/Lauso/releases/latest)
[![CI](https://github.com/TheKingBucket001/Lauso/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/TheKingBucket001/Lauso/actions/workflows/android.yml)
[![License](https://img.shields.io/badge/license-GPL--3.0--only-blue.svg)](https://github.com/TheKingBucket001/Lauso/blob/main/LICENSE)
[![ColorOS](https://img.shields.io/badge/ColorOS-16-1677FF.svg)](https://github.com/TheKingBucket001/Lauso)

[下载模块](https://github.com/TheKingBucket001/Lauso/releases/latest) · [源代码](https://github.com/TheKingBucket001/Lauso) · [问题反馈](https://github.com/TheKingBucket001/Lauso/issues)

</div>

---

## 项目简介

LauSo 为 ColorOS Launcher 的应用图标长按菜单添加可配置标语，并提供紧凑菜单、视觉适配和自定义气泡设置。

模块只在 `com.android.launcher` 进程中工作，不修改系统 APK，不常驻后台服务，也不读取其它应用的内容。

## 模块功能

- 为应用图标长按菜单添加主标语和副标语。
- 支持普通菜单、紧凑菜单和视觉适配选项。
- 视觉适配只缩小标语下方空间约 35%。
- 支持为空或自定义的点击气泡，空气泡不会显示替代提示。
- 标语点击后关闭菜单并恢复图标状态，不跳转到桌面或其它应用。
- 启动时检查 LSPosed Launcher 作用域和 Root 状态。
- 关于页面提供源码、更新入口和版本信息。

## 构建

```powershell
.\gradlew.bat :app:assembleDebug --offline --no-daemon
```

正式签名构建使用项目根目录中被 Git 忽略的 `lauso-release.keystore` 和 `local.properties`，或在 CI 中提供 `RELEASE_*` 环境变量。正式发布由 `.github/workflows/android.yml` 的 `v*` 标签流程完成。

## 发布

在已登录 GitHub CLI 且本机 SSH 签名密钥可用的终端中运行：

```powershell
node .\scripts\publish-github.mjs
```

脚本使用 `git@github.com:TheKingBucket001/Lauso.git` 推送，并用账户 SSH 签名密钥签署提交；它只提交源代码和配置。keystore、`local.properties`、APK 与构建目录均被 `.gitignore` 排除。仓库名为 `Lauso`，应用显示名保持 `LauSo`。

## 项目链接

| 链接 | 地址 |
| --- | --- |
| 主页 | [TheKingBucket001/Lauso](https://github.com/TheKingBucket001/Lauso) |
| 源代码 | [TheKingBucket001/Lauso](https://github.com/TheKingBucket001/Lauso) |
| 问题反馈 | [Issues](https://github.com/TheKingBucket001/Lauso/issues) |

## 许可证

本项目采用 [GNU General Public License v3.0](https://github.com/TheKingBucket001/Lauso/blob/main/LICENSE)。
