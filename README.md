# 铃言织机° · Pupurin° Loom — Android 版

将桌面端 [PupurinOfficial/PupurinLoom](https://github.com/PupurinOfficial/PupurinLoom)（Electron + React + TypeScript + Monaco + Tailwind，Python 后端解析 Ren'Py 脚本）移植为 Android 原生应用，保留原版全部能力，并针对手机/平板做了竖屏适配、共享目录存储与云端打包。

> 仓库地址：<https://github.com/Explore0416/PupurinLoomAndroid>

---

## 一、功能特性

- **项目管理**：新建（多种脚本模板 + 分辨率向导）、打开、删除、导入 Ren'Py 工程
- **脚本编辑**：Monaco 编辑器全量语言支持，`.rpy` 文件增删改 / 移动 / 重命名，故事文件标记
- **角色 / 变量**：可视化管理与「从脚本解析」合并（`Character` / `image` / `default` 语句）
- **资源管理**：图片 / 音频 base64 读写、批量导入图片、非 ASCII 文件名检测与批量修复
- **插件系统**：内置示例插件、创建脚手架、启用 / 信任、受限项目文件读写、HTTP、插件商城（GitHub 安装）
- **运行已导出游戏**：导入 Ren'Py 游戏 APK 一键安装并自动启动；内嵌引擎探测
- **云端打包**：把项目上传到打包服务器，产出 PC / Web / Android 安装包（支持官方与自建服务器）
- **竖屏适配**：手机竖屏下不横向溢出、文字不撑爆宽度
- **共享目录存储**：项目等用户数据存放在公共目录 `/storage/emulated/0/PupurinLoom/`，而非应用私有 `data` 目录，便于用户直接访问、备份

详细能力映射与取舍详见 [docs/能力映射与取舍.md](docs/能力映射与取舍.md)。

## 二、技术架构

| 桌面版 | Android 版 |
| --- | --- |
| Electron 主进程 + preload IPC | `WebView` + `WebViewAssetLoader`（离线加载打包进 assets 的渲染层） |
| `window.pupurin.{method}`（`ipcRenderer.invoke`） | JS Bridge `window.pupurin.{method}` → `addJavascriptInterface` → 原生 Kotlin 分发 |
| Python `server.py` 解析 Ren'Py | 解析逻辑本地化为 TypeScript（渲染层）与 Kotlin（`ProjectStore`），无需独立后端进程 |
| 项目存储在任意磁盘目录 | 项目存储在公共共享目录 `/storage/emulated/0/PupurinLoom/projects/` |
| 文件对话框 | SAF（Storage Access Framework）系统文件 / 图片选择器 |
| 本地跨平台打包（Ren'Py SDK + JDK） | 云端打包（服务器侧调用 Ren'Py 工具链） |

渲染层通过独立 Vite 构建产物打包进 `assets/www/`，Ren'Py 工程模板打包进 `assets/template/`，bridge 存根打包进 `assets/bridge.js`。

## 三、工程结构

```
PupurinLoomAndroid/
├── .github/workflows/         # GitHub Actions（push 自动构建 APK）
├── app/src/main/
│   ├── assets/
│   │   ├── bridge.js              # window.pupurin JS Bridge（document-start 注入）
│   │   ├── www/                   # 渲染层构建产物（前端 UI）
│   │   └── template/              # Ren'Py 新工程模板（game/ 目录）
│   ├── java/com/pupurin/loom/
│   │   ├── MainActivity.kt        # WebView + JS Bridge 分发 + 选择器 + 存储授权
│   │   ├── SharedStorage.kt       # 公共共享目录 /storage/emulated/0/PupurinLoom/
│   │   └── bridge/
│   │       ├── Model.kt           # 数据模型（camelCase，与前端 TS 对齐）
│   │       ├── ProjectStore.kt    # 项目/角色/变量存储 + 创建/导入 + 解析
│   │       ├── FsStore.kt         # 项目内文件系统全部 API
│   │       ├── PluginStore.kt     # 插件系统 + 插件商城 + HTTP + tar.gz 解包
│   │       └── CloudPackager.kt   # 云端打包客户端（官方/自建服务器）
│   ├── res/                       # 主题/字符串/颜色/图标
│   └── AndroidManifest.xml        # 自适应 / 存储权限等关键配置
├── app/build.gradle.kts
├── build.gradle.kts / settings.gradle.kts / gradle.properties
├── gradlew / gradle/wrapper/      # Gradle Wrapper
├── docs/                          # 能力对表、云打包服务说明
└── dist/                          # 本机构建产物（不入库）
```

## 四、本地构建

环境要求：JDK 17、Android SDK（`platforms;android-34`、`build-tools;34.0.0`、`platform-tools`）。

```bash
# 在项目根目录，配置 SDK 路径（local.properties，不入库）
echo 'sdk.dir=<你的 Android SDK 路径>' > local.properties

# 编译可安装的 release APK（已用调试签名，便于直接安装）
./gradlew assembleRelease

# 或 debug 包
./gradlew assembleDebug
```

产物输出到 `app/build/outputs/apk/{debug,release}/`。

## 五、自动构建（GitHub Actions）

推送到 `main` 分支（或发起 PR）时，`.github/workflows/android-build.yml` 会自动执行：

1. 拉取 JDK 17 + Android SDK
2. `./gradlew assembleDebug assembleRelease`
3. 上传 `apk-debug` / `apk-release` / lint 报告为构建产物

在仓库 **Actions** 页签即可查看每次构建结果并下载最新 APK。

## 六、云端打包服务器

App 内置官方打包服务器地址：`https://maker.lightning-team.de5.net`。未配置自建服务器时，打包默认走官方渠道。

- **API 契约**：`POST /api/pack`（上传项目 zip）、`GET /api/pack/<job_id>`（轮询）、`GET /api/health`（健康检查）
- **自建服务器**：需要一台 x86_64 Debian/Ubuntu，Python + Flask + Ren'Py SDK，可选 Android 工具链支持打 Android 包
- **详细搭建指引**：见 [docs/cloud-builder.md](docs/cloud-builder.md)
- **在 App 配置**：设置 → 云端打包 → 填写自建服务器地址

## 七、存储与权限说明

- 项目数据保存在 `/storage/emulated/0/PupurinLoom/projects/`
- Android 11+（API 30）需要授予「所有文件访问」（`MANAGE_EXTERNAL_STORAGE`）权限
- Android 10 及以下需要「存储权限」（`WRITE_EXTERNAL_STORAGE`）
- 首次启动会自动引导授权

## 八、版本

| 版本 | 说明 |
| --- | --- |
| 0.4.2 | 共享目录存储、官方云打包服务器、竖屏文字溢出适配、移除后台画中画(PiP)悬挂窗 |

## 九、许可

本项目目前未指定开源许可证。如需对外发布，请先与作者确认授权方式。