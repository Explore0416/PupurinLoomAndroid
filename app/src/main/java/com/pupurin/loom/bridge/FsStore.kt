package com.pupurin.loom.bridge

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * 项目内文件系统操作（与桌面版 index.ts 的 fs:* IPC 语义对齐）。
 * 路径约定：projectPath 为项目根目录（含 game/）；subPath / subDir 均相对 game/。
 */
class FsStore(private val context: Context) {

    private val gson = Gson()

    // ---- 路径工具 ----

    private fun resolve(projectPath: String, sub: String): File {
        val base = File(projectPath, "game")
        if (sub.isEmpty()) return base
        // 容错：合并 <名字>/<同名>（如 font/font.ttf）这类重复段，避免「找不到文件」的裸 ENOENT
        val parts = sub.trim('/').split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return base
        val collapsed = buildString {
            var last: String? = null
            for (p in parts) {
                if (p == last) continue // 连续重复段只保留一个
                if (isNotEmpty()) append('/')
                append(p)
                last = p
            }
        }
        return File(base, collapsed).canonicalFile
    }

    private fun combine(dir: String, name: String): String =
        if (dir.isEmpty()) name else "$dir/$name"

    fun sanitizeName(name: String): String = name.trim().ifEmpty { "file" }

    // ---- 列表 ----

    fun listFiles(projectPath: String, subDir: String): List<FsEntry> {
        val target = resolve(projectPath, subDir)
        val marks = readStoryMarks(projectPath)
        val entries = target.listFiles() ?: return emptyList()
        val nodes = entries
            .filter { !it.name.startsWith(".") && it.name.trim().isNotEmpty() }
            .map { e ->
                val rel = combine(subDir, e.name)
                if (e.isDirectory) {
                    FsEntry(e.name, true, rel, 0, false)
                } else {
                    var isStoryFile = false
                    if (e.name.endsWith(".rpy")) {
                        val mark = marks[rel]
                        isStoryFile = if (mark != null) mark == "story" else {
                            isAutoStoryFile(tryRead(e))
                        }
                    }
                    FsEntry(e.name, false, rel, e.length(), isStoryFile)
                }
            }
        return nodes.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    // ---- 故事/代码标记（持久化到 <项目根>/.pupurin-marks.json）----

    private fun marksFile(projectPath: String) = File(projectPath, ".pupurin-marks.json")

    private fun readStoryMarks(projectPath: String): Map<String, String> {
        return try {
            val raw = marksFile(projectPath).readText(Charsets.UTF_8)
            val type = object : TypeToken<Map<String, String>>() {}.type
            val data: Map<String, Any> = gson.fromJson(raw, object : TypeToken<Map<String, Any>>() {}.type)
            @Suppress("UNCHECKED_CAST")
            (data["marks"] as? Map<String, String>) ?: emptyMap()
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun writeStoryMarks(projectPath: String, marks: Map<String, String>) {
        val f = marksFile(projectPath)
        val tmp = File(f.parentFile, ".pupurin-marks.json.tmp")
        tmp.writeText(gson.toJson(mapOf("marks" to marks)), Charsets.UTF_8)
        tmp.renameTo(f)
    }

    private fun isAutoStoryFile(content: String): Boolean =
        Regex("""(?m)^\s*label\s+\w+""").containsMatchIn(content)

    fun setStoryMark(projectPath: String, filePath: String, mark: String?) {
        val marks = readStoryMarks(projectPath).toMutableMap()
        if (mark == null) marks.remove(filePath) else marks[filePath] = mark
        writeStoryMarks(projectPath, marks)
    }

    private fun migrateStoryMarks(projectPath: String, oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        val marks = readStoryMarks(projectPath)
        val changed = mutableMapOf<String, String>()
        var dirty = false
        for ((k, v) in marks) {
            if (k == oldKey || k.startsWith("$oldKey/")) {
                changed[k.replace(oldKey, newKey)] = v
                dirty = true
            } else changed[k] = v
        }
        if (dirty) writeStoryMarks(projectPath, changed)
    }

    private fun removeStoryMarks(projectPath: String, key: String) {
        val marks = readStoryMarks(projectPath)
        val changed = mutableMapOf<String, String>()
        var dirty = false
        for ((k, v) in marks) {
            if (k == key || k.startsWith("$key/")) dirty = true else changed[k] = v
        }
        if (dirty) writeStoryMarks(projectPath, changed)
    }

    // ---- 目录/文件增删改 ----

    fun createDir(projectPath: String, subDir: String) {
        resolve(projectPath, subDir).mkdirs()
    }

    fun createFile(projectPath: String, subPath: String, content: String = "") {
        val target = resolve(projectPath, subPath)
        target.parentFile?.mkdirs()
        target.writeText(content, Charsets.UTF_8)
    }

    fun rename(projectPath: String, oldPath: String, newName: String) {
        val oldFull = resolve(projectPath, oldPath)
        val newFull = File(oldFull.parentFile, newName)
        if (!oldFull.renameTo(newFull) && !(oldFull.exists() && newFull.exists())) {
            throw Exception("重命名失败")
        }
        val idx = oldPath.lastIndexOf('/')
        val parent = if (idx >= 0) oldPath.substring(0, idx) else ""
        migrateStoryMarks(projectPath, oldPath, combine(parent, newName))
    }

    fun delete(projectPath: String, subPath: String) {
        val target = resolve(projectPath, subPath)
        if (target.isDirectory) target.deleteRecursively() else target.delete()
        removeStoryMarks(projectPath, subPath)
    }

    fun move(projectPath: String, srcPath: String, destDir: String) {
        val srcFull = resolve(projectPath, srcPath)
        val fileName = srcPath.substringAfterLast('/')
        val destFull = resolve(projectPath, combine(destDir, fileName))
        destFull.parentFile?.mkdirs()
        if (!srcFull.renameTo(destFull)) throw Exception("移动失败")
        migrateStoryMarks(projectPath, srcPath, combine(destDir, fileName))
    }

    // ---- 读/写 ----

    fun readFile(projectPath: String, subPath: String): String =
        resolve(projectPath, subPath).readText(Charsets.UTF_8)

    fun saveScript(projectPath: String, content: String) {
        atomicWrite(resolve(projectPath, "script.rpy"), content)
    }

    fun saveRpyFile(projectPath: String, subPath: String, content: String) {
        atomicWrite(resolve(projectPath, subPath), content)
    }

    private fun atomicWrite(target: File, content: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        tmp.renameTo(target)
    }

    // ---- 资源 base64 ----

    fun readImageBase64(projectPath: String, subPath: String): String {
        val target = resolve(projectPath, subPath)
        val data = target.readBytes()
        val ext = subPath.substringAfterLast('.', "png").lowercase()
        val mime = when (ext) {
            "jpg", "jpeg" -> "jpeg"
            "webp" -> "webp"
            "gif" -> "gif"
            "bmp" -> "bmp"
            else -> "png"
        }
        return "data:image/$mime;base64,${android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)}"
    }

    fun writeImageBase64(projectPath: String, subPath: String, dataUrl: String) {
        val m = Regex("""^data:image/(png|jpeg|webp|gif|bmp);base64,(.+)$""", RegexOption.DOT_MATCHES_ALL).find(dataUrl)
            ?: throw Exception("invalid image data url")
        val target = resolve(projectPath, subPath)
        target.parentFile?.mkdirs()
        target.writeBytes(android.util.Base64.decode(m.groupValues[2], android.util.Base64.DEFAULT))
    }

    fun readAudioBase64(projectPath: String, subPath: String): String {
        val target = resolve(projectPath, subPath)
        val data = target.readBytes()
        val ext = subPath.substringAfterLast('.', "ogg").lowercase()
        val mime = when (ext) {
            "opus", "ogg" -> "ogg"
            "mp3", "mp2" -> "mpeg"
            "flac" -> "flac"
            "wav" -> "wav"
            else -> "ogg"
        }
        return "data:audio/$mime;base64,${android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)}"
    }

    // ---- 导入外部文件（支持 content:// URI 与文件路径）----

    fun importFile(projectPath: String, destSubDir: String, srcFilePath: String): String {
        val destDir = resolve(projectPath, destSubDir)
        destDir.mkdirs()
        val rawName = srcFilePath.substringAfterLast('/').substringAfterLast('\\').ifEmpty { "imported" }
        val fileName = sanitizeName(rawName)
        val destFull = File(destDir, fileName)
        if (srcFilePath.startsWith("content://")) {
            val input = context.contentResolver.openInputStream(Uri.parse(srcFilePath))
                ?: throw Exception("无法读取所选文件")
            input.use { ins -> destFull.outputStream().use { outs -> ins.copyTo(outs) } }
        } else {
            File(srcFilePath).copyTo(destFull, overwrite = true)
        }
        return combine(destSubDir, fileName)
    }

    // 把多个图片 content URI 复制到 game/images/，返回 {path, name}（同名自动 _1/_2）
    fun importImagesFrom(projectPath: String, uris: List<String>): List<Map<String, String>> {
        val imagesDir = File(File(projectPath, "game"), "images")
        imagesDir.mkdirs()
        val out = mutableListOf<Map<String, String>>()
        for (u in uris) {
            val uri = Uri.parse(u)
            val name = queryDisplayName(uri) ?: "image.png"
            val ext = name.substringAfterLast('.', "").lowercase()
            val base = name.substringBeforeLast('.', name)
                .replace(Regex("""[\\/:*?"<>|]"""), "_").ifEmpty { "image" }
            val extSuffix = if (ext.isEmpty()) ".png" else ".$ext"
            var target = File(imagesDir, "$base$extSuffix")
            var n = 1
            while (target.exists()) {
                target = File(imagesDir, "${base}_${n}${extSuffix}")
                n++
            }
            val input = context.contentResolver.openInputStream(uri)
                ?: throw Exception("无法读取图片")
            input.use { ins -> target.outputStream().use { outs -> ins.copyTo(outs) } }
            val fileName = target.name
            out.add(mapOf("path" to "images/$fileName", "name" to fileName.substringBeforeLast('.')))
        }
        return out
    }

    // 上传单张图片到 game/gallery/（插件常见场景），返回 {path, name, cancelled}
    fun uploadImageToGallery(projectPath: String, uri: String): Map<String, Any> {
        val u = Uri.parse(uri)
        // 未选择即取消
        val name = queryDisplayName(u)
        if (name == null) return mapOf("path" to "", "name" to "", "cancelled" to true)
        val galleryDir = File(File(projectPath, "game"), "gallery")
        galleryDir.mkdirs()
        val ext = name.substringAfterLast('.', "").lowercase()
        val base = name.substringBeforeLast('.', name)
            .replace(Regex("""[\\/:*?"<>|]"""), "_").ifEmpty { "image" }
        val extSuffix = if (ext.isEmpty()) ".png" else ".$ext"
        var target = File(galleryDir, "$base$extSuffix")
        var n = 1
        while (target.exists()) {
            target = File(galleryDir, "${base}_${n}${extSuffix}")
            n++
        }
        val input = context.contentResolver.openInputStream(u) ?: throw Exception("无法读取图片")
        input.use { ins -> target.outputStream().use { outs -> ins.copyTo(outs) } }
        val fileName = target.name
        return mapOf("path" to "gallery/$fileName", "name" to fileName, "cancelled" to false)
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            var name: String? = null
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
            name
        } catch (_: Exception) {
            null
        }
    }

    // ---- .rpy 文件树 ----

    fun listRpyFiles(projectPath: String): List<RpyFileNode> {
        val game = File(projectPath, "game")
        val marks = readStoryMarks(projectPath)
        return scanRpyDir(game, "", marks)
    }

    private fun scanRpyDir(dir: File, relPath: String, marks: Map<String, String>): List<RpyFileNode> {
        val entries = dir.listFiles() ?: return emptyList()
        val nodes = mutableListOf<RpyFileNode>()
        for (e in entries) {
            if (e.name.startsWith(".") || e.name.trim().isEmpty()) continue
            val rel = combine(relPath, e.name)
            if (e.isDirectory) {
                val children = scanRpyDir(e, rel, marks)
                if (children.isNotEmpty()) {
                    nodes.add(RpyFileNode(e.name, rel, true, false, children))
                }
            } else if (e.name.endsWith(".rpy")) {
                val mark = marks[rel]
                val isStory = if (mark != null) mark == "story" else isAutoStoryFile(tryRead(e))
                nodes.add(RpyFileNode(e.name, rel, false, isStory, null))
            }
        }
        return nodes.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    }

    private fun tryRead(f: File): String = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { "" }

    // ---- 非 ASCII 文件名检查与修复 ----

    fun scanNonAsciiFiles(projectPath: String): List<Map<String, Any>> {
        val gameDir = File(projectPath, "game")
        val items = mutableListOf<Map<String, Any>>()
        val usedByDir = mutableMapOf<String, MutableSet<String>>()

        fun usedSet(dir: String): MutableSet<String> {
            var s = usedByDir[dir]
            if (s == null) {
                s = mutableSetOf()
                try {
                    val full = if (dir.isEmpty()) gameDir else File(gameDir, dir)
                    for (ex in full.listFiles() ?: emptyArray()) s.add(ex.name.lowercase())
                } catch (_: Exception) { /* ignore */ }
                usedByDir[dir] = s
            }
            return s
        }

        fun walk(relDir: String) {
            val full = if (relDir.isEmpty()) gameDir else File(gameDir, relDir)
            val entries = try { full.listFiles() } catch (_: Exception) { return }
            for (e in entries ?: emptyArray()) {
                if (e.name == ".DS_Store") continue
                val relPath = combine(relDir, e.name)
                if (!Regex("""^[\x00-\x7F]*$""").matches(e.name)) {
                    val used = usedSet(relDir)
                    var final = suggestAsciiName(e.name)
                    var n = 2
                    while (used.contains(final.lowercase())) {
                        final = suggestAsciiName(e.name).replace(Regex("""(\.[^.]*)?$"""), "-$n$1")
                        n++
                    }
                    used.add(final.lowercase())
                    items.add(mapOf("dir" to relDir, "oldName" to e.name, "suggested" to final, "isDir" to e.isDirectory))
                }
                if (e.isDirectory) walk(relPath)
            }
        }

        walk("")
        return items
    }

    private fun asciiFallbackPrefix(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "ttf", "otf", "ttc" -> "font"
            "png", "jpg", "jpeg", "webp", "gif", "bmp" -> "image"
            "webm", "mp4", "mov" -> "video"
            "wav", "ogg", "mp3", "m4a" -> "audio"
            else -> "asset"
        }
    }

    private fun suggestAsciiName(name: String): String {
        val ext = name.substringAfterLast('.', "")
        val stem = name.substringBeforeLast('.', name)
        val cleaned = stem.replace(Regex("[^A-Za-z0-9_-]"), "")
        return (cleaned.ifEmpty { asciiFallbackPrefix(name) }) + if (ext.isEmpty()) "" else ".$ext"
    }

    data class RenameItem(val dir: String, val oldName: String, val newName: String, val isDir: Boolean)

    fun applyNonAsciiRename(projectPath: String, items: List<Map<String, Any>>): Map<String, Any> {
        val gameDir = File(projectPath, "game")
        val logs = mutableListOf<String>()

        val mappings = items
            .map {
                RenameItem(
                    it["dir"] as? String ?: "",
                    it["oldName"] as? String ?: "",
                    it["newName"] as? String ?: "",
                    it["isDir"] as? Boolean ?: false
                )
            }
            .filter { it.oldName != it.newName }
            .map {
                Records(
                    it.dir, it.oldName, it.newName, it.isDir,
                    if (it.dir.isEmpty()) it.oldName else "${it.dir}/${it.oldName}",
                    if (it.dir.isEmpty()) it.newName else "${it.dir}/${it.newName}"
                )
            }

        // 校验
        for (m in mappings) {
            if (m.newName.isBlank()) throw Exception("存在空的名称")
            if (!Regex("""^[\x00-\x7F]*$""").matches(m.newName)) throw Exception("新名称仍含非 ASCII 字符: ${m.newName}")
            if (m.newName.contains('/') || m.newName.contains('\\') || m.newName.contains("..")) {
                throw Exception("非法名称: ${m.newName}")
            }
        }

        // 冲突检查
        val dirs = mappings.map { it.dir }.distinct()
        for (dir in dirs) {
            val group = mappings.filter { it.dir == dir }
            val existing = mutableSetOf<String>()
            try {
                val full = if (dir.isEmpty()) gameDir else File(gameDir, dir)
                for (ex in full.listFiles() ?: emptyArray()) existing.add(ex.name.lowercase())
            } catch (_: Exception) { /* ignore */ }
            val removed = group.map { it.oldName.lowercase() }.toSet()
            val added = mutableSetOf<String>()
            for (m in group) {
                val k = m.newName.lowercase()
                if (added.contains(k) || (existing.contains(k) && !removed.contains(k))) {
                    throw Exception("名称冲突: ${m.newName}（同目录已有同名文件）")
                }
                added.add(k)
            }
        }

        // 1) 重命名文件，目录最后（按路径长度倒序）
        val files = mappings.filter { !it.isDir }
        val dirsToRename = mappings.filter { it.isDir }.sortedByDescending { it.oldPath.length }
        for (m in files) {
            File(gameDir, m.oldPath).renameTo(File(gameDir, m.newPath))
            logs.add("重命名 ${m.oldPath} → ${m.newPath}")
        }
        for (m in dirsToRename) {
            File(gameDir, m.oldPath).renameTo(File(gameDir, m.newPath))
            logs.add("重命名目录 ${m.oldPath} → ${m.newPath}")
        }

        // 2) 迁移故事标记
        for (m in mappings) migrateStoryMarks(projectPath, m.oldPath, m.newPath)

        // 3) 修改 .rpy 引用
        val replacements = mutableListOf<Pair<String, String>>()
        for (m in mappings) {
            replacements.add(m.oldPath to m.newPath)
            if (m.dir.isEmpty() && !m.isDir) replacements.add(m.oldName to m.newName)
        }
        replacements.sortByDescending { it.first.length }

        var patchedFiles = 0
        fun patchRpy(relDir: String) {
            val full = if (relDir.isEmpty()) gameDir else File(gameDir, relDir)
            val entries = try { full.listFiles() } catch (_: Exception) { return }
            for (e in entries ?: emptyArray()) {
                val relPath = combine(relDir, e.name)
                if (e.isDirectory) {
                    patchRpy(relPath)
                } else if (Regex("""\.rpy$""", RegexOption.IGNORE_CASE).containsMatchIn(e.name)) {
                    val fp = File(gameDir, relPath)
                    val content = try { fp.readText(Charsets.UTF_8) } catch (_: Exception) { continue }
                    var out = content
                    for (r in replacements) out = out.split(r.first).joinToString(r.second)
                    if (out != content) {
                        fp.writeText(out, Charsets.UTF_8)
                        patchedFiles++
                        logs.add("已更新引用: $relPath")
                    }
                }
            }
        }
        patchRpy("")

        return mapOf("logs" to logs, "count" to mappings.size, "patchedFiles" to patchedFiles)
    }

    private class Records(val dir: String, val oldName: String, val newName: String, val isDir: Boolean, val oldPath: String, val newPath: String)
}