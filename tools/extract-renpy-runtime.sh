#!/usr/bin/env bash
#
# extract-renpy-runtime.sh
#
# 从「任意一个官方生成的 Ren'Py 游戏 APK」中提取 Ren'Py 安卓引擎运行时，
# 放入本工程的 jniLibs 与 assets，使 App 具备「内嵌运行时」能力。
#
# 为什么这样做：Ren'Py 引擎原生库 + 打包的 Python 运行时只存在于桌面 SDK 或
# 已构建的游戏 APK 中，无法凭空生成。本脚本把这一步标准化、可重复。
#
# 用法：
#   bash tools/extract-renpy-runtime.sh <某个-RenPy-游戏.apk>
#
# 提取内容：
#   lib/<abi>/*.so  →  app/src/main/jniLibs/<abi>/*.so   （引擎原生库）
#   assets/         →  app/src/main/assets/renpy/        （Python 运行时与引擎资源）
#
# 注意：Ren'Py 版本不同，Python 运行时在 assets 中的目录名（如 x-pythonlib、
# assets/x-renpy 等）可能不同；本脚本原样保留整棵树，避免遗漏。
set -euo pipefail

APK="${1:?用法: bash tools/extract-renpy-runtime.sh <renpy-game.apk>}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JNILIBS="$ROOT/app/src/main/jniLibs"
RENPY_ASSETS="$ROOT/app/src/main/assets/renpy"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

command -v unzip >/dev/null 2>&1 || { echo "缺少 unzip，请先安装"; exit 1; }

echo "==> 解包 $APK"
unzip -q "$APK" -d "$TMP/apk"

echo "==> 复制原生库到 jniLibs"
mkdir -p "$JNILIBS"
if [ -d "$TMP/apk/lib" ]; then
  cp -R "$TMP/apk/lib/." "$JNILIBS/"
  echo "    已复制 ABI：$(ls "$TMP/apk/lib")"
else
  echo "    !!! 未在 APK 中找到 lib/（可能该 APK 不含原生库）"
fi

echo "==> 复制引擎 assets 到 assets/renpy"
mkdir -p "$RENPY_ASSETS"
if [ -d "$TMP/apk/assets" ]; then
  cp -R "$TMP/apk/assets/." "$RENPY_ASSETS/"
  echo "    已复制 assets 顶层条目：$(ls "$TMP/apk/assets" | tr '\n' ' ')"
else
  echo "    !!! 未在 APK 中找到 assets/"
fi

echo "==> 完成。"
echo "    请同步提取该 APK 的 Java 引导代码（classes.dex → 反编译得到 SDL/Ren'Py 引导类），"
echo "    按实际入口实现/校准 PlayerActivity 的本地初始化逻辑。"
echo "    完成后在 MainActivity 中 hasRenpyEngine() 将返回 true，即可走内嵌运行时路径。"