package com.pupurin.loom.bridge

import android.content.Context
import com.pupurin.loom.SharedStorage
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * 云端打包客户端。
 *
 * 把项目目录打成 zip，上传到「铃言织机云端打包服务」（见仓库 loom-cloud-builder/，
 * 一个 Flask + Ren'Py CLI 的 Debian 服务），轮询直到打包完成，返回产物下载链接。
 *
 * 说明：打包依赖 aapt2/zipalign 等 x86_64 工具链，手机（arm64）无法本地完成，
 * 因此打包统一走服务器。服务器地址来自用户设置（自建服务器）或内置官方服务器。
 */
class CloudPackager(private val context: Context) {

    companion object {
        /** 官方云打包服务器地址：未在设置中填写自建服务器时使用此官方渠道。 */
        const val OFFICIAL_SERVER_URL = "https://maker.lightning-team.de5.net"

        const val POLL_INTERVAL_MS = 2000L
        const val MAX_POLL_MS = 30 * 60 * 1000L // 30 分钟硬上限
    }

    /**
     * 发起云端打包，同步阻塞直到完成或失败（调用方请放到后台线程）。
     *
     * @param serverUrl 服务地址（不带末尾斜杠），来自用户设置
     * @param projectPath 项目根目录（含 game/）
     * @param platform pc | web | android | all（对应服务端 distribute 目标）
     * @param opts 额外参数（version/packageName 等，当前仅透传，供未来扩展）
     * @return 与桌面版打包返回结构对齐的 Map，含 logs / 下载链接
     */
    fun pack(serverUrl: String, projectPath: String, platform: String, opts: Map<String, Any> = emptyMap()): Map<String, Any?> {
        val logs = mutableListOf<String>()

        if (serverUrl.isBlank()) {
            return mapOf(
                "logs" to listOf(
                    "错误：尚未配置打包服务器地址。",
                    "请在「设置 → 云端打包」中填入你的打包服务地址（自建服务器），",
                    "或选择官方打包服务器渠道（已内置）。"
                )
            )
        }

        val base = serverUrl.trimEnd('/')
        val root = File(projectPath)
        if (!root.isDirectory || !File(root, "game").isDirectory()) {
            return mapOf("logs" to listOf("错误：不是有效的 Ren'Py 项目目录（缺少 game/）"))
        }

        // 1) 打包项目为 zip
        val zipFile = File(context.cacheDir, "cloud-upload-${System.currentTimeMillis()}.zip")
        try {
            zipDir(root, zipFile)
            logs.add("已打包项目并上传到服务器：$base")
        } catch (e: Exception) {
            return mapOf("logs" to listOf("错误：项目打包失败：${e.message}"))
        }

        try {
            // 2) 上传（对瞬时网络/隧道错误自动重试，避免 DNS 抖动误报失败）
            var jobId: String? = null
            var uploadErr: Exception? = null
            for (attempt in 1..3) {
                try {
                    jobId = upload(base, zipFile, platform, opts)
                    break
                } catch (e: Exception) {
                    uploadErr = e
                    if (attempt < 3) {
                        logs.add("上传网络抖动（${e.message}），自动重试 $attempt/3……")
                        Thread.sleep(2000L * attempt)
                    }
                }
            }
            if (jobId == null) {
                return mapOf(
                    "logs" to (logs + "错误：上传失败：${uploadErr?.message}"),
                    "error" to (uploadErr?.message ?: "上传失败")
                )
            }
            logs.add("上传成功，作业 ID：$jobId")
            logs.add("服务器开始打包……")

            // 3) 轮询（对瞬时网络/DNS 错误自动重试）。
            // 关键点：客户端网络抖动绝不能判「打包失败」——打包在服务器后台照常进行，
            // 客户端只要持续重试即可；只有服务器明确返回 error 或超过硬超时才结束。
            val deadline = System.currentTimeMillis() + MAX_POLL_MS
            var lastGlitchLoggedAt = 0L
            while (true) {
                val status = try {
                    query(base, jobId)
                } catch (e: Exception) {
                    if (System.currentTimeMillis() > deadline) {
                        return mapOf(
                            "logs" to (logs + "错误：连接打包服务器持续失败（30 分钟）：${e.message}"),
                            "error" to (e.message ?: "连接打包服务器失败")
                        )
                    }
                    // 网络/DNS 抖动：重试而非判死；日志节流（每 15 秒最多记一条），避免刷屏
                    if (System.currentTimeMillis() - lastGlitchLoggedAt > 15000L) {
                        logs.add("网络抖动（${e.message}），正在重试，服务器仍在后台打包……")
                        lastGlitchLoggedAt = System.currentTimeMillis()
                    }
                    Thread.sleep(POLL_INTERVAL_MS)
                    continue
                }
                // 追加新日志（去重）
                status.logs.forEach { if (it !in logs) logs.add(it) }
                when (status.state) {
                    "done" -> {
                        logs.add("打包完成！")
                        return finalResult(status, logs, root.name)
                    }
                    "error" -> {
                        return mapOf(
                            "logs" to (logs + "错误：${status.error}"),
                            "error" to (status.error ?: "打包失败")
                        )
                    }
                    else -> {
                        if (System.currentTimeMillis() > deadline) {
                            return mapOf("logs" to (logs + "错误：打包超时（30 分钟）"))
                        }
                        Thread.sleep(POLL_INTERVAL_MS)
                    }
                }
            }
        } catch (e: Exception) {
            return mapOf("logs" to (logs + "错误：${e.message}"))
        } finally {
            zipFile.delete()
        }
    }

    /** 测试服务器连通性与 SDK 就绪状态。 */
    fun test(serverUrl: String): Map<String, Any?> {
        if (serverUrl.isBlank()) {
            return mapOf("ok" to false, "error" to "未配置服务器地址")
        }
        return try {
            val resp = httpGet("${serverUrl.trimEnd('/')}/api/health", null)
            val json = JSONObject(resp)
            mapOf(
                "ok" to json.optBoolean("ok", false),
                "renpy" to (if (json.isNull("renpy")) "" else json.optString("renpy")),
                "error" to if (json.optBoolean("ok", false)) null else "服务器已响应，但 Ren'Py SDK 未就绪"
            )
        } catch (e: Exception) {
            mapOf("ok" to false, "error" to (e.message ?: "连接失败"))
        }
    }

    // ---- 内部 ----

    private data class JobStatus(val state: String, val logs: List<String>, val files: List<Pair<String, String>>, val error: String?)

    private fun finalResult(status: JobStatus, logs: List<String>, projectName: String): Map<String, Any?> {
        // 把云端的每个产物下载到公共共享目录 /storage/emulated/0/PupurinLoom/builds/<项目名>/
        // 这样用户能在文件管理器 / 电脑上直接访问、安装、备份打包结果。
        val destDir = SharedStorage.buildsDir().resolve(projectName).apply { mkdirs() }
        val localFiles = status.files.map { (name, url) ->
            val safeName = File(name).name.ifBlank { "output-${System.currentTimeMillis()}.bin" }
            val local = try {
                download(url, File(destDir, safeName))
            } catch (e: Exception) {
                null
            }
            mapOf("name" to name, "url" to url, "path" to (local?.absolutePath))
        }.filter { it["path"] != null }

        val firstApk = localFiles.firstOrNull { (it["name"] as String).endsWith(".apk") }
        val webZip = localFiles.firstOrNull { (it["name"] as String).endsWith(".zip") && it["name"].toString().contains("web", true) }
        val anyZip = localFiles.firstOrNull { (it["name"] as String).endsWith(".zip") }

        return mapOf(
            "logs" to (logs + "已保存到共享目录：${destDir.absolutePath}"),
            "buildsDir" to destDir.absolutePath,
            "webDir" to if (webZip != null) destDir.absolutePath else null,
            "outDir" to (firstApk?.get("path") ?: anyZip?.get("path") ?: if (localFiles.isNotEmpty()) destDir.absolutePath else null),
            "downloadUrl" to (firstApk?.get("path") ?: anyZip?.get("path") ?: emptyList<Any>()),
            "files" to localFiles
        )
    }

    /** 把服务器返回的文件 URL 下载到本地 dest（对瞬时网络错误重试 3 次）。 */
    private fun download(urlStr: String, dest: File): File {
        dest.parentFile?.mkdirs()
        var lastErr: Exception? = null
        for (attempt in 1..3) {
            try {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 15000
                    readTimeout = 30000
                }
                return try {
                    conn.inputStream.use { ins ->
                        FileOutputStream(dest).use { out -> ins.copyTo(out) }
                    }
                    dest
                } finally {
                    conn.disconnect()
                }
            } catch (e: Exception) {
                lastErr = e
                if (attempt < 3) Thread.sleep(2000L * attempt)
            }
        }
        throw lastErr ?: Exception("下载失败")
    }

    private fun upload(base: String, zipFile: File, platform: String, opts: Map<String, Any>): String {
        val boundary = "----LoomBoundary${System.nanoTime()}"
        val url = URL("$base/api/pack")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            doInput = true
            connectTimeout = 15000
            readTimeout = 60000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        DataOutputStream(BufferedOutputStream(conn.outputStream)).use { out ->
            writeField(out, boundary, "platform", platform)
            val project = opts["project"] as? String
            if (!project.isNullOrBlank()) writeField(out, boundary, "project", project)
            // 文件
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"project.zip\"\r\n")
            out.writeBytes("Content-Type: application/zip\r\n\r\n")
            FileInputStream(zipFile).use { it.copyTo(out) }
            out.writeBytes("\r\n")
            out.writeBytes("--$boundary--\r\n")
            out.flush()
        }
        val code = conn.responseCode
        val body = readStream(if (code in 200..299) conn.inputStream else conn.errorStream)
        conn.disconnect()
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(body).optString("error") }.getOrNull() ?: "HTTP $code"
            throw Exception(msg.ifBlank { "上传失败 HTTP $code" })
        }
        val jobId = JSONObject(body).optString("job_id")
        if (jobId.isBlank()) throw Exception("服务器未返回 job_id")
        return jobId
    }

    private fun query(base: String, jobId: String): JobStatus {
        val body = httpGet("$base/api/pack/$jobId", null)
        val json = JSONObject(body)
        val state = json.optString("status", "unknown")
        val logs = mutableListOf<String>()
        val arr = json.optJSONArray("logs") ?: JSONArray()
        for (i in 0 until arr.length()) logs.add(arr.optString(i))
        val files = mutableListOf<Pair<String, String>>()
        val farr = json.optJSONArray("files") ?: JSONArray()
        for (i in 0 until farr.length()) {
            val f = farr.optJSONObject(i) ?: continue
            files.add(f.optString("name") to f.optString("url"))
        }
        val err = if (json.isNull("error")) null else json.optString("error")
        return JobStatus(state, logs, files, err)
    }

    private fun writeField(out: DataOutputStream, boundary: String, name: String, value: String) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.writeBytes(value)
        out.writeBytes("\r\n")
    }

    private fun httpGet(urlStr: String, token: String?): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
        }
        return try {
            val code = conn.responseCode
            val body = readStream(if (code in 200..299) conn.inputStream else conn.errorStream)
            if (code !in 200..299) throw Exception("HTTP $code: $body")
            body
        } finally {
            conn.disconnect()
        }
    }

    private fun readStream(ins: java.io.InputStream?): String {
        if (ins == null) return ""
        return ins.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    private fun zipDir(root: File, out: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(out))).use { zos ->
            zipInto(zos, root, "")
        }
    }

    private fun zipInto(zos: ZipOutputStream, dir: File, base: String) {
        val entries = dir.listFiles() ?: return
        for (f in entries) {
            if (f.name == "cloud-upload" || f.name.startsWith(".")) continue
            val entryName = if (base.isEmpty()) f.name else "$base/${f.name}"
            if (f.isDirectory) {
                zos.putNextEntry(ZipEntry("$entryName/"))
                zos.closeEntry()
                zipInto(zos, f, entryName)
            } else {
                zos.putNextEntry(ZipEntry(entryName))
                FileInputStream(f).use { it.copyTo(zos) }
                zos.closeEntry()
            }
        }
    }
}