package com.pupurin.loom.bridge

import android.content.Context
import com.google.gson.Gson
import com.pupurin.loom.SharedStorage
import java.io.File
import java.util.UUID

/**
 * 项目元数据 + 角色/变量数据存储。
 * 与桌面版语义对齐：
 *  - 项目元数据持久化到 filesDir/projects.json（等同 Electron userData/projects.json）
 *  - 项目根目录 = 公共共享目录 PupurinLoom/projects/<项目名>，其下含 game/script.rpy，
 *    方便用户在文件管理器中直接访问、备份（见 SharedStorage）
 *  - 角色存储到 <项目根>/game/characters.json，变量存储到 <项目根>/game/variables.json
 */
class ProjectStore(private val context: Context) {

    private val gson = Gson()

    fun newId(): String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)

    // ---- 路径 ----

    fun defaultProjectsDir(): File = SharedStorage.projectsDir()

    private fun projectsFile(): File = File(context.filesDir, "projects.json")

    // ---- 项目元数据 ----

    private fun readStore(): List<ProjectMeta> {
        return try {
            val raw = projectsFile().readText(Charsets.UTF_8)
            val arr = gson.fromJson(raw, ProjectsWrapper::class.java)
            arr?.projects ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun writeStore(projects: List<ProjectMeta>) {
        val tmp = File(context.filesDir, "projects.json.tmp")
        tmp.writeText(gson.toJson(ProjectsWrapper(projects)), Charsets.UTF_8)
        tmp.renameTo(projectsFile())
    }

    private class ProjectsWrapper(val projects: List<ProjectMeta>)

    fun listProjects(): List<ProjectMeta> {
        val projects = readStore().toMutableList()
        val valid = projects.map { p ->
            if (File(p.path).exists()) p else p.copy(missing = true)
        }
        return valid.sortedByDescending { it.lastOpenedAt }
    }

    fun openProject(id: String): ProjectMeta? {
        val projects = readStore().toMutableList()
        val idx = projects.indexOfFirst { it.id == id }
        if (idx < 0) return null
        projects[idx] = projects[idx].copy(lastOpenedAt = System.currentTimeMillis())
        writeStore(projects)
        return projects[idx]
    }

    fun deleteProject(id: String) {
        writeStore(readStore().filter { it.id != id })
    }

    // ---- 创建项目（复制模板 + 覆盖 options/script） ----

    fun createProject(
        name: String,
        parentPath: String?,
        title: String?,
        buildName: String?,
        resolution: String?,
        scriptTemplate: String?
    ): ProjectMeta {
        val projectName = name.trim()
        if (projectName.isEmpty()) throw Exception("项目名称不能为空")

        val parent = if (parentPath.isNullOrBlank()) defaultProjectsDir() else File(parentPath)
        val root = File(parent, projectName).canonicalFile

        if (root.exists()) throw Exception("目录「$projectName」已存在。请更换项目名。")

        // 复制模板（assets/template -> root）
        parent.mkdirs()
        copyTemplate(root)

        val titleValue = title?.trim()?.ifEmpty { null } ?: projectName
        val buildNameValue = (buildName?.trim()?.ifEmpty { null }
            ?: projectName.replace(Regex("[^a-zA-Z0-9]"), "")).ifEmpty { "game" }

        // 更新 options.rpy
        val optionsPath = File(root, "game/options.rpy")
        if (optionsPath.exists()) {
            try {
                var content = optionsPath.readText(Charsets.UTF_8)
                content = content.replace(
                    Regex("""define config\.name = _\(".*?"\)"""),
                    "define config.name = _(\"$titleValue\")"
                )
                content = content.replace(
                    Regex("""define build\.name = ".*?""""),
                    "define build.name = \"$buildNameValue\""
                )
                val saveDir = buildNameValue.lowercase() + "-" + System.currentTimeMillis()
                content = content.replace(
                    Regex("""define config\.save_directory = ".*?""""),
                    "define config.save_directory = \"$saveDir\""
                )
                if (resolution != null && resolution.contains('x')) {
                    val parts = resolution.split('x')
                    val w = parts[0].trim().toIntOrNull()
                    val h = parts.getOrNull(1)?.trim()?.toIntOrNull()
                    if (w != null && h != null) {
                        content += "\n# 由 铃言织机° 向导设置的屏幕分辨率\n" +
                            "define config.screen_width = $w\ndefine config.screen_height = $h\n"
                    }
                }
                optionsPath.writeText(content, Charsets.UTF_8)
            } catch (e: Exception) {
                android.util.Log.w("ProjectStore", "更新 options.rpy 失败", e)
            }
        }

        // 写入 script.rpy 骨架
        val scriptPath = File(root, "game/script.rpy")
        try {
            scriptPath.parentFile?.mkdirs()
            scriptPath.writeText(scriptSkeleton(scriptTemplate ?: "basic", titleValue), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.w("ProjectStore", "写入 script.rpy 骨架失败", e)
        }

        val now = System.currentTimeMillis()
        val project = ProjectMeta(newId(), projectName, root.absolutePath, now, now)
        val projects = readStore().toMutableList()
        projects.add(project)
        writeStore(projects)
        return project
    }

    private fun scriptSkeleton(template: String, title: String): String = when (template) {
        "minimal" -> "# 由 铃言织机° 创建的开场脚本\nlabel start:\n    \"故事从这里开始……\"\n    return\n"
        "branch" -> "# 由 铃言织机° 创建的开场脚本（带选项分支示例）\ndefine e = Character(\"艾琳\", color=\"#c8ffc8\")\n\nlabel start:\n    \"清晨，阳光透过窗帘洒进房间。\"\n    e \"「$title」的第一天，想做些什么呢？\"\n\n    menu:\n        \"出门走走\":\n            \"你决定出门散步，呼吸新鲜空气。\"\n        \"待在家里\":\n            \"你决定待在家里，享受悠闲的一天。\"\n\n    e \"这一天，就这样开始了。\"\n    return\n"
        else -> "# 由 铃言织机° 创建的开场脚本（可编辑或替换）\ndefine e = Character(\"艾琳\", color=\"#c8ffc8\")\n\nlabel start:\n    \"清晨，阳光透过窗帘洒进房间。\"\n    e \"欢迎来到「$title」的世界！\"\n    return\n"
    }

    // 从 assets/template 递归复制到目标目录
    private fun copyTemplate(dest: File) {
        copyAssetsDir(context, "template", dest)
    }

    // ---- 导入项目 ----

    fun importProject(sourcePath: String): ProjectMeta {
        val sourceRoot = File(sourcePath).canonicalFile
        val standardScript = File(sourceRoot, "game/script.rpy")
        val flatScript = File(sourceRoot, "script.rpy")
        val layout = when {
            standardScript.exists() -> "standard"
            flatScript.exists() -> "flat"
            else -> throw Exception("路径「$sourcePath」下既没有 game/script.rpy，也没有直接的 script.rpy，不是有效的 Ren'Py 项目目录。")
        }

        val projectName = sourceRoot.name.ifEmpty { "导入的项目" }
        val targetRoot = File(defaultProjectsDir(), projectName)
        if (targetRoot.exists()) throw Exception("项目「$projectName」已存在于应用目录中，请先删除或重命名。")

        defaultProjectsDir().mkdirs()
        if (layout == "flat") {
            File(targetRoot, "game").mkdirs()
            sourceRoot.copyRecursively(File(targetRoot, "game"), overwrite = false)
        } else {
            sourceRoot.copyRecursively(targetRoot, overwrite = false)
        }

        val now = System.currentTimeMillis()
        val project = ProjectMeta(newId(), projectName, targetRoot.absolutePath, now, now)
        val projects = readStore().toMutableList()
        projects.add(project)
        writeStore(projects)
        return project
    }

    // ---- 角色 ----

    private fun charFile(root: String) = File(root, "game/characters.json")

    fun loadCharacters(root: String): List<CharacterMeta> {
        return try {
            val raw = charFile(root).readText(Charsets.UTF_8)
            gson.fromJson(raw, CharactersWrapper::class.java)?.characters ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveCharacters(root: String, characters: List<CharacterMeta>) {
        val f = charFile(root)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "characters.json.tmp")
        tmp.writeText(gson.toJson(CharactersWrapper(characters)), Charsets.UTF_8)
        tmp.renameTo(f)
    }

    private class CharactersWrapper(val characters: List<CharacterMeta>)

    fun newCharacter(name: String): CharacterMeta = CharacterMeta(
        id = newId(),
        name = name.trim().ifEmpty { "新角色" },
        varName = "",
        color = "#FFE4A6",
        description = "",
        sprites = emptyList(),
        avatar = AvatarConfig("initial")
    )

    fun newSprite(name: String): SpriteMeta = SpriteMeta(
        id = newId(),
        name = name.trim().ifEmpty { "新差分" },
        path = ""
    )

    // 从 script.rpy（及所有 .rpy）解析 Character 定义与 image 差分，合并到 characters.json
    fun parseCharactersFromScript(root: String): List<CharacterMeta> {
        val gameDir = File(root, "game")
        val rpyFiles = gameDir.listFiles()?.filter { it.name.endsWith(".rpy") } ?: emptyList()

        val defineCharRe = Regex("""define\s+(\w+)\s*=\s*Character\s*\(([\s\S]*?)\)""")
        val imageDefRe = Regex("""^\s*image\s+(\w+)\s+(\w+)\s*:\s*\n\s*"([^"]+)"\s*$""", RegexOption.MULTILINE)

        data class ParsedChar(val varName: String, val name: String, val color: String?)
        data class ParsedImage(val charVar: String, val sprite: String, val path: String)

        val chars = mutableListOf<ParsedChar>()
        val images = mutableListOf<ParsedImage>()
        val seenCharVars = mutableSetOf<String>()

        for (f in rpyFiles) {
            val content = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { continue }
            for (m in defineCharRe.findAll(content)) {
                val varName = m.groupValues[1]
                val args = m.groupValues[2]
                if (args.trim().startsWith("None")) continue
                if (varName in seenCharVars) continue
                seenCharVars.add(varName)
                val name = Regex("""["']([^"']+)["']""").find(args)?.groupValues?.get(1) ?: ""
                val color = Regex("""who_color\s*=\s*["']([^"']+)["']""").find(args)?.groupValues?.get(1)
                if (name.isNotEmpty()) chars.add(ParsedChar(varName, name, color))
            }
            for (m in imageDefRe.findAll(content)) {
                images.add(ParsedImage(m.groupValues[1], m.groupValues[2], m.groupValues[3].replace("\\", "/")))
            }
        }

        val existing = loadCharacters(root).toMutableList()
        val existingVars = existing.map { it.varName.lowercase() }.toSet()
        val imagesByVar = images.groupBy { it.charVar.lowercase() }

        val newChars = mutableListOf<CharacterMeta>()
        for (c in chars) {
            if (c.varName.lowercase() in existingVars) continue
            val matched = imagesByVar[c.varName.lowercase()] ?: emptyList()
            val sprites = matched.map { SpriteMeta(newId(), it.sprite, it.path) }
            newChars.add(
                CharacterMeta(
                    newId(), c.name, c.varName, c.color ?: "#FFE4A6", "",
                    sprites,
                    if (sprites.isNotEmpty()) AvatarConfig("sprite", sprites[0].id) else AvatarConfig("initial")
                )
            )
        }
        val all = existing + newChars
        if (newChars.isNotEmpty()) saveCharacters(root, all)
        return all
    }

    // ---- 变量 ----

    private fun varFile(root: String) = File(root, "game/variables.json")

    fun loadVariables(root: String): List<VariableMeta> {
        return try {
            val raw = varFile(root).readText(Charsets.UTF_8)
            gson.fromJson(raw, VariablesWrapper::class.java)?.variables ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveVariables(root: String, variables: List<VariableMeta>) {
        val f = varFile(root)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "variables.json.tmp")
        tmp.writeText(gson.toJson(VariablesWrapper(variables)), Charsets.UTF_8)
        tmp.renameTo(f)
    }

    private class VariablesWrapper(val variables: List<VariableMeta>)

    fun newVariable(name: String): VariableMeta = VariableMeta(
        id = newId(),
        name = name.trim().ifEmpty { "新变量" },
        varName = "",
        type = "int",
        defaultValue = "0",
        description = ""
    )

    // 从 game/ 下所有 .rpy 解析 default 语句，推断类型
    fun parseVariablesFromScript(root: String): List<VariableMeta> {
        val gameDir = File(root, "game")
        val systemNames = setOf("options.rpy", "screens.rpy", "gui.rpy")
        val rpyFiles = mutableListOf<File>()
        fun walk(dir: File) {
            val entries = dir.listFiles() ?: return
            for (e in entries) {
                if (e.isDirectory) walk(e)
                else if (e.name.endsWith(".rpy") && !isSystemRpy(e.name, systemNames)) rpyFiles.add(e)
            }
        }
        walk(gameDir)

        val defaultRe = Regex("""^default\s+(\w+)\s*=\s*(.+?)\s*(?:#.*)?$""", RegexOption.MULTILINE)
        val seen = mutableSetOf<String>()
        val variables = mutableListOf<VariableMeta>()
        for (f in rpyFiles) {
            val content = try { f.readText(Charsets.UTF_8) } catch (_: Exception) { continue }
            for (m in defaultRe.findAll(content)) {
                val varName = m.groupValues[1]
                if (varName in seen) continue
                seen.add(varName)
                val valueStr = m.groupValues[2].trim()
                var type = "int"
                var defaultValue = valueStr
                when {
                    valueStr == "True" || valueStr == "False" -> {
                        type = "bool"; defaultValue = if (valueStr == "True") "true" else "false"
                    }
                    valueStr.startsWith('"') && valueStr.endsWith('"') && valueStr.length >= 2 -> {
                        type = "str"; defaultValue = valueStr.substring(1, valueStr.length - 1)
                    }
                    valueStr.contains('.') -> type = "float"
                    Regex("""^-?\d+$""").matches(valueStr) -> type = "int"
                }
                variables.add(VariableMeta(newId(), varName, varName, type, defaultValue, ""))
            }
        }
        return variables
    }

    private fun isSystemRpy(name: String, systemNames: Set<String>): Boolean {
        if (name.startsWith("00") || name.startsWith("_")) return true
        return name in systemNames
    }

    companion object {
        // 递归复制 assets 下的目录到本地文件系统
        fun copyAssetsDir(context: Context, assetPath: String, dest: File) {
            val list = try { context.assets.list(assetPath) } catch (_: Exception) { emptyArray() }
            if (list.isNullOrEmpty()) {
                // 是文件
                try {
                    dest.parentFile?.mkdirs()
                    context.assets.open(assetPath).use { input ->
                        dest.outputStream().use { output -> input.copyTo(output) }
                    }
                } catch (_: Exception) { /* ignore */ }
                return
            }
            dest.mkdirs()
            for (child in list) {
                copyAssetsDir(context, "$assetPath/$child", File(dest, child))
            }
        }
    }
}