# 自建云端打包服务（cloud-builder）指引

织机 App 的「云端打包」把用户项目上传到一台 x86_64 服务器，由服务器调用 Ren'Py SDK 完成分发打包（PC / Web / Android），App 端再轮询下载产物。这样手机本身不需要安装 Ren'Py 工具链。

> 内置官方服务器地址：`https://maker.lightning-team.de5.net`（见 `CloudPackager.kt` 的 `OFFICIAL_SERVER_URL`）。
> 生产环境如果不想用官方服务器，或想用更大的机器、私有化部署，可自建服务器后在 App 设置里填自己的地址。

## 为什么需要服务器打包

- 移动端（arm64）无法本地运行 `aapt2` / `zipalign` / Android SDK 工具链（这些主要是 x86_64 二进制）。
- 手机内存 / 磁盘有限，Ren'Py 全平台分发产物较大。
- 统一在服务器打包，产物与更新一致，也方便接入构建队列。

## 推荐的服务器技术栈

| 组件 | 说明 |
| --- | --- |
| 系统 | Debian 12 / Ubuntu 22.04+（x86_64） |
| 语言 | Python 3.9+（Flask） |
| 构建引擎 | Ren'Py SDK（`renpy.sh` / `renpy` 命令行，含 `distribute` 目标） |
| Android 目标 | Android SDK 的 `aapt2` / `zipalign` / `build-tools`（供 Ren'Py 打包 Android 使用） |
| 持久化 | 本地磁盘即可（作业用 UUID 目录），可用 Redis/Database 做队列 |

参考仓库 `loom-cloud-builder/`（Flask + Ren'Py CLI 的 Debian 服务）。

## 约定 API 契约

App 端（`bridge/CloudPackager.kt`）会调用以下三个接口，自建服务器必须实现并响应对应的 JSON 结构。

### 1) 健康检查

```
GET /api/health
```

返回：

```json
{
  "ok": true,
  "renpy": "8.1.3.22052007"
}
```

- `ok`（bool）：Ren'Py SDK 是否就绪。
- `renpy`（string）：Ren'Py 版本号。
- App 的「测试服务器」按钮调用此接口判断连通性与 SDK 状态。

### 2) 上传并开始打包

```
POST /api/pack
Content-Type: multipart/form-data
```

表单项：

| 字段 | 必选 | 说明 |
| --- | --- | --- |
| `platform` | 是 | `pc` / `web` / `android`（App 的 `packageGame/packageWeb/packageMobile` 分别传 `pc` / `web` / `android`） |
| `file` | 是 | 项目 zip（multipart 文件字段，App 上传的键名固定为 `file`） |
| `project` | 否 | 项目名（可选，透传给构建流程） |

返回（HTTP 200）：

```json
{ "job_id": "xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" }
```

### 3) 轮询打包进度

```
GET /api/pack/<job_id>
```

返回：

```json
{
  "status": "done",
  "logs": ["...", "..."],
  "files": [
    { "name": "mygame-1.0-android.apk", "url": "/api/files/<job_id>/mygame-1.0-android.apk" },
    { "name": "mygame-1.0-web.zip", "url": "/api/files/<job_id>/mygame-1.0-web.zip" }
  ],
  "error": null
}
```

字段说明：

- `status`：`done` / `error` / 其它（进行中，App 每 2 秒轮询，最长 30 分钟）。
- `logs`（数组）：构建日志，App 会追加去重展示。
- `files`（数组）：构建完成的产物，`name` 为文件名，`url` 为可下载的相对/绝对地址。
- `error`：失败时的错误信息。

`files[].url` 需要真实可下载（App 会把 release 分包、web zip、APK 等直接拼下载链接）。

## 本地搭建步骤（概览）

```bash
# 1) 安装系统依赖
sudo apt-get update
sudo apt-get install -y python3 python3-pip unzip zip openjdk-17-jdk

# 2) 安装 Ren'Py SDK
# 下载 Linux 版 Ren'Py SDK，解压后目录内即含 renpy / renpy.sh

# 3) 安装 Android build-tools（供 Ren'Py 打 Android 包）
#   使用 aapt2 / zipalign / build-tools 工具链

# 4) 实现上述三个 API（可基于 loom-cloud-builder 仓库模板）

# 5) 启动服务（示例：Gunicorn / Flask）
python3 -m pip install flask gunicorn
gunicorn app:app --bind 0.0.0.0:8090 --workers 4
```

## 在 App 里配置自建服务器

打开设置 → 云端打包，填入你的服务器地址（不带末尾斜杠，例如 `https://build.example.com`），点「测试」确认连通后即可打包。

## 相关代码

- App 端接口实现：`app/src/main/java/com/pupurin/loom/bridge/CloudPackager.kt`
- App 端前端 `packageGame` 入口：`app/src/main/assets/www/`（渲染层）