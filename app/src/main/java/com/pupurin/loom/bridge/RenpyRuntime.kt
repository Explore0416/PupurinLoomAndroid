package com.pupurin.loom.bridge

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import java.io.File

/**
 * Ren'Py 运行时：负责探测是否已内嵌引擎，以及在设备上启动「已导出」的 Ren'Py 游戏。
 *
 * 说明：
 * - Ren'Py 引擎的原生二进制（各 ABI 的 librenpy.so 等）+ 打包的 Python 运行时，
 *   需要从 Ren'Py 官方产物（SDK 或任意官方生成的游戏 APK）提取后放入
 *   `app/src/main/jniLibs/` 与 `assets/renpy/`。见 tools/extract-renpy-runtime.sh。
 * - 未内嵌引擎时，本类仍可通过「导入 API → 安装 → 启动」运行已导出的游戏 APK
 *   （APK 自身携带引擎，无需额外二进制）。
 */
class RenpyRuntime(private val context: Context) {

    /** 是否已内嵌 Ren'Py 引擎原生库。 */
    fun hasEngine(): Boolean {
        val nd = try {
            context.applicationInfo.nativeLibraryDir
        } catch (_: Exception) {
            return false
        }
        if (nd.isNullOrBlank()) return false
        val files = File(nd).listFiles() ?: return false
        return files.any { it.name.contains("renpy", ignoreCase = true) }
    }

    /** 内嵌引擎详细信息（供前端展示诊断）。 */
    fun engineInfo(): Map<String, Any> {
        val nd = try {
            context.applicationInfo.nativeLibraryDir
        } catch (_: Exception) { null }
        return mapOf(
            "present" to hasEngine(),
            "nativeLibraryDir" to (nd ?: ""),
            "platform" to "android"
        )
    }

    /** 通过包名启动一个已安装的游戏。 */
    fun launchPackage(pkg: String): Map<String, Any> {
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return mapOf("ok" to false, "error" to "未找到可启动入口：$pkg")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            mapOf("ok" to true, "package" to pkg)
        } catch (e: Exception) {
            mapOf("ok" to false, "error" to (e.message ?: "启动失败"))
        }
    }

    /**
     * 尽力而为地列出设备上可能为 Ren'Py 游戏的可启动应用。
     * 由于跨应用无法读取对方 assets，这里基于包名/应用名的启发式判断，仅作辅助。
     */
    fun listRenpyLikeGames(): List<Map<String, String>> {
        val out = mutableListOf<Map<String, String>>()
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = try {
            pm.queryIntentActivities(intent, 0)
        } catch (_: Exception) { emptyList() }
        for (ri in resolveInfos) {
            val pkg = ri.activityInfo.packageName
            val label = try { ri.loadLabel(pm).toString() } catch (_: Exception) { pkg }
            val flags = ri.activityInfo.applicationInfo?.flags ?: 0
            val system = (flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val hit = pkg.contains("renpy", ignoreCase = true) ||
                label.contains("ren'py", ignoreCase = true) ||
                label.contains("renpy", ignoreCase = true) ||
                pkg.contains("thequestion") || pkg.contains("tutorial", ignoreCase = true)
            if (hit && !system) out.add(mapOf("label" to label, "package" to pkg))
        }
        return out
    }
}