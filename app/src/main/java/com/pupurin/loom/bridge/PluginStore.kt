package com.pupurin.loom.bridge

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * 插件系统 + 插件商城（与桌面版 pluginManager.ts / pluginStore.ts 语义对齐）。
 * 目录：filesDir/plugins/<id>/{manifest.json, main.js}
 * 状态：filesDir/plugin-state.json；数据：filesDir/plugin-data/<id>.json
 */
class PluginStore(private val context: Context) {

    private val gson = Gson()

    companion object {
        val ID_RE = Regex("^[a-z0-9][a-z0-9._-]{0,63}$", RegexOption.IGNORE_CASE)
    }

    // ---- 路径 ----

    private fun pluginsDir(): File = File(context.filesDir, "plugins").apply { mkdirs() }
    private fun stateFile(): File = File(context.filesDir, "plugin-state.json")
    private fun dataFile(id: String): File = File(context.filesDir, "plugin-data/$id.json")

    private fun readState(): MutableMap<String, MutableMap<String, Boolean>> {
        return try {
            val raw = stateFile().readText(Charsets.UTF_8)
            val type = object : TypeToken<Map<String, Map<String, Boolean>>>() {}.type
            val m: Map<String, Map<String, Boolean>> = gson.fromJson(raw, type) ?: emptyMap()
            m.mapValues { it.value.toMutableMap() }.toMutableMap()
        } catch (_: Exception) {
            mutableMapOf()
        }
    }

    private fun writeState(s: Map<String, Map<String, Boolean>>) {
        val tmp = File(context.filesDir, "plugin-state.json.tmp")
        tmp.writeText(gson.toJson(s), Charsets.UTF_8)
        tmp.renameTo(stateFile())
    }

    // ---- 内置示例插件「喵喵语」----

    private val exampleManifest = mapOf(
        "id" to "meow-loom",
        "name" to "喵喵语",
        "version" to "3.1.0",
        "description" to "内置示例插件：开启全局喵语，整个界面文字末尾都会加上「喵」",
        "author" to "Pupurin° Loom",
        "main" to "main.js",
        "builtin" to true
    )

    private val exampleMain = """// 喵喵语 —— 内置示例插件
// 演示 loom 插件 API：命令、面板、事件钩子、主进程 fs / http
// 全局喵语：把整个应用界面的文字末尾都加上「喵」
var G = window.__meowGlobal || (window.__meowGlobal = { on: false, observer: null, originals: new WeakMap() })

function meowEligible(node) {
  var parent = node.parentElement
  if (!parent) return false
  if (parent.closest('.monaco-editor')) return false
  if (parent.closest('.react-flow')) return false
  if (parent.closest('[contenteditable]')) return false
  if (parent.isContentEditable) return false
  return true
}

function meowTextNode(node) {
  if (node.nodeType !== 3) return
  var text = node.nodeValue || ''
  var t = text.replace(/\s+${'$'}/, '')
  if (!t) return
  if (t.charAt(t.length - 1) === '喵') return
  if (!meowEligible(node)) return
  G.originals.set(node, text)
  node.nodeValue = t + '喵' + text.slice(t.length)
}

function meowScan(root) {
  var walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT)
  var list = []
  while (walker.nextNode()) list.push(walker.currentNode)
  for (var i = 0; i < list.length; i++) meowTextNode(list[i])
}

function meowStart() {
  if (G.on) return
  G.on = true
  meowScan(document.body)
  G.observer = new MutationObserver(function (muts) {
    for (var i = 0; i < muts.length; i++) {
      var m = muts[i]
      if (m.type === 'characterData') { meowTextNode(m.target); continue }
      for (var j = 0; j < m.addedNodes.length; j++) {
        var n = m.addedNodes[j]
        if (n.nodeType === 3) meowTextNode(n)
        else if (n.nodeType === 1) meowScan(n)
      }
    }
  })
  G.observer.observe(document.body, { childList: true, subtree: true, characterData: true })
}

function meowStop() {
  if (!G.on) return
  G.on = false
  if (G.observer) { G.observer.disconnect(); G.observer = null }
  var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT)
  var list = []
  while (walker.nextNode()) list.push(walker.currentNode)
  for (var i = 0; i < list.length; i++) {
    var orig = G.originals.get(list[i])
    if (orig != null) list[i].nodeValue = orig
  }
}

loom.commands.register('meow.global.on', '全局喵语：开启', function () {
  meowStart()
  loom.store.set('globalMeow', true)
  loom.toast('全局喵语已开启：整个界面都变得喵喵喵了！', 'success')
})

loom.commands.register('meow.global.off', '全局喵语：关闭', function () {
  meowStop()
  loom.store.set('globalMeow', false)
  loom.toast('已关闭全局喵语喵', 'info')
})

loom.commands.register('meow.options', 'options.rpy 行数（fs 演示）', function () {
  return loom.fs.read('options.rpy').then(function (content) {
    if (content == null) { loom.toast('未找到 options.rpy 喵'); return }
    loom.toast('options.rpy 共 ' + content.split('\n').length + ' 行')
  }).catch(function (e) { loom.toast('读取失败：' + e, 'error') })
})

loom.panel.register('meow.preview', '喵语控制台', {
  render: function () {
    return { html:
      '<div style="padding:4px;font-size:13px">' +
      '<div style="display:flex;gap:8px;margin-bottom:10px">' +
      '<button id="meow-g-on" style="flex:1;padding:6px;border-radius:6px;border:1px solid var(--loom-border);background:var(--loom-accent);color:var(--loom-bg);cursor:pointer">开启全局喵语</button>' +
      '<button id="meow-g-off" style="flex:1;padding:6px;border-radius:6px;border:1px solid var(--loom-border);background:var(--loom-panel2);color:var(--loom-text);cursor:pointer">关闭全局喵语</button>' +
      '</div>' +
      '<button id="meow-http" style="width:100%;padding:6px;border-radius:6px;border:1px solid var(--loom-border);background:var(--loom-panel2);color:var(--loom-text);cursor:pointer">HTTP 请求测试</button>' +
      '<div id="meow-out" style="margin-top:8px;background:var(--loom-bg);border:1px solid var(--loom-border);border-radius:6px;padding:8px;min-height:24px;font-size:12px;color:var(--loom-muted);word-break:break-all"></div>' +
      '</div>' }
  },
  mount: function (el) {
    var out = el.querySelector('#meow-out')
    var httpBtn = el.querySelector('#meow-http')
    var gOn = el.querySelector('#meow-g-on')
    var gOff = el.querySelector('#meow-g-off')
    if (httpBtn && out) httpBtn.addEventListener('click', function () {
      out.textContent = '请求中…'
      loom.http.get('https://example.com').then(function (r) {
        out.textContent = 'HTTP ' + r.status + '：' + String(r.text || '').slice(0, 200)
      }).catch(function (e) { out.textContent = '请求失败：' + e })
    })
    if (gOn) gOn.addEventListener('click', function () { meowStart(); loom.store.set('globalMeow', true); loom.toast('全局喵语已开启喵！', 'success') })
    if (gOff) gOff.addEventListener('click', function () { meowStop(); loom.store.set('globalMeow', false); loom.toast('已关闭全局喵语喵', 'info') })
  }
})

if (loom.store.get('globalMeow') === true) {
  setTimeout(meowStart, 300)
}
"""

    private fun ensureBuiltinPlugins() {
        val dir = File(pluginsDir(), "meow-loom")
        val targetVersion = exampleManifest["version"] as String
        val mpf = File(dir, "manifest.json")
        val mjs = File(dir, "main.js")
        dir.mkdirs()
        mpf.writeText(gson.toJson(exampleManifest), Charsets.UTF_8)
        mjs.writeText(exampleMain, Charsets.UTF_8)
    }

    // ---- 扫描插件 ----

    fun listPlugins(): List<PluginMeta> {
        val base = pluginsDir()
        ensureBuiltinPlugins()
        val state = readState()
        val out = mutableListOf<PluginMeta>()
        for (e in base.listFiles() ?: emptyArray()) {
            if (!e.isDirectory || !ID_RE.matches(e.name)) continue
            val manifest: Map<String, Any> = try {
                gson.fromJson(File(e, "manifest.json").readText(Charsets.UTF_8),
                    object : TypeToken<Map<String, Any>>() {}.type) ?: continue
            } catch (_: Exception) { continue }

            val id = (manifest["id"] as? String) ?: e.name
            if (id != e.name) continue
            val main = (manifest["main"] as? String)?.takeIf { it.isNotEmpty() } ?: "main.js"
            val hasMain = File(e, main).exists()
            val builtin = manifest["builtin"] == true
            val st = state[id] ?: emptyMap()
            val icon = (manifest["icon"] as? String)?.takeIf { it.isNotEmpty() }
            out.add(PluginMeta(
                id = id,
                name = (manifest["name"] as? String)?.takeIf { it.isNotEmpty() } ?: id,
                version = (manifest["version"] as? String) ?: "0.0.0",
                description = (manifest["description"] as? String) ?: "",
                author = (manifest["author"] as? String) ?: "",
                main = main,
                builtin = builtin,
                enabled = st["enabled"] == true || (builtin && st["enabled"] != false),
                trusted = st["trusted"] == true || builtin,
                hasMain = hasMain,
                scaffolded = if (st["scaffolded"] == true) true else null
            ))
        }
        return out.sortedBy { it.name.lowercase() }
    }

    fun loadPluginMain(id: String): String? {
        if (!ID_RE.matches(id)) return null
        val meta = listPlugins().find { it.id == id } ?: return null
        if (!meta.hasMain || !meta.enabled) return null
        return try { File(File(pluginsDir(), id), meta.main).readText(Charsets.UTF_8) } catch (_: Exception) { null }
    }

    fun setPluginEnabled(id: String, enabled: Boolean) {
        if (!ID_RE.matches(id)) return
        val state = readState()
        state.getOrPut(id) { mutableMapOf() }["enabled"] = enabled
        writeState(state)
    }

    fun setPluginTrusted(id: String, trusted: Boolean) {
        if (!ID_RE.matches(id)) return
        val state = readState()
        state.getOrPut(id) { mutableMapOf() }["trusted"] = trusted
        writeState(state)
    }

    fun openPluginsDir() { pluginsDir() }

    fun openPluginMain(id: String): Boolean {
        if (!ID_RE.matches(id)) return false
        val meta = listPlugins().find { it.id == id } ?: return false
        return meta.hasMain && File(File(pluginsDir(), id), meta.main).exists()
    }

    fun getPluginData(id: String): Map<String, Any> {
        if (!ID_RE.matches(id)) return emptyMap()
        return try {
            gson.fromJson(dataFile(id).readText(Charsets.UTF_8),
                object : TypeToken<Map<String, Any>>() {}.type) ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    fun setPluginData(id: String, data: Map<String, Any>) {
        if (!ID_RE.matches(id)) return
        val f = dataFile(id)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(gson.toJson(data), Charsets.UTF_8)
        tmp.renameTo(f)
    }

    // ---- 创建插件（本地脚手架，写入后自动信任并启用）----

    fun createPlugin(input: Map<String, String>): Map<String, Any> {
        val id = (input["id"] ?: "").trim().lowercase()
        if (!ID_RE.matches(id)) return mapOf("ok" to false, "error" to "插件 id 不合法：小写字母/数字开头，仅含字母、数字、._-，最长 64 字符")
        val name = (input["name"] ?: "").trim()
        if (name.isEmpty()) return mapOf("ok" to false, "error" to "插件名称不能为空")
        if (name.length > 50) return mapOf("ok" to false, "error" to "插件名称过长（最多 50 字）")
        val desc = (input["description"] ?: "").trim()
        if (desc.length > 200) return mapOf("ok" to false, "error" to "描述过长（最多 200 字）")
        val author = (input["author"] ?: "").trim().ifEmpty { "Anonymous" }

        val dest = File(pluginsDir(), id)
        if (dest.exists()) return mapOf("ok" to false, "error" to "插件目录已存在：$id")

        dest.mkdirs()
        val manifest = mapOf(
            "id" to id, "name" to name, "version" to "0.1.0",
            "description" to desc, "author" to author, "main" to "main.js"
        )
        File(dest, "manifest.json").writeText(gson.toJson(manifest), Charsets.UTF_8)
        File(dest, "main.js").writeText(scaffoldMain(id, author), Charsets.UTF_8)

        val state = readState()
        state[id] = mutableMapOf("enabled" to true, "trusted" to true, "scaffolded" to true)
        writeState(state)

        val meta = listPlugins().find { it.id == id }
        return if (meta != null) mapOf("ok" to true, "meta" to meta) else mapOf("ok" to false, "error" to "创建失败")
    }

    private fun scaffoldMain(id: String, author: String): String = """// 由「创建插件」生成的插件骨架（$id）
// 作者：$author
// loom 插件 API：loom.commands / loom.panel / loom.fs / loom.http / loom.store / loom.toast
loom.commands.register('$id.hello', '你好', function () {
  loom.toast('来自插件 $id 的问候', 'success')
})
"""

    // ---- 主进程能力：受限项目文件读写 ----

    private fun resolveInProject(projectPath: String, subPath: String): File {
        val base = File(projectPath).canonicalFile
        val target = File(base, subPath).canonicalFile
        if (target != base && !target.path.startsWith(base.path + File.separator)) {
            throw Exception("路径越界: $subPath")
        }
        return target
    }

    fun pluginFsRead(projectPath: String, subPath: String): String? {
        return try { resolveInProject(projectPath, subPath).readText(Charsets.UTF_8) } catch (_: Exception) { null }
    }

    fun pluginFsWrite(projectPath: String, subPath: String, content: String) {
        val target = resolveInProject(projectPath, subPath)
        target.parentFile?.mkdirs()
        target.writeText(content, Charsets.UTF_8)
    }

    fun pluginFsList(projectPath: String, subDir: String): List<Map<String, Any>> {
        val dir = resolveInProject(projectPath, subDir)
        val entries = dir.listFiles() ?: emptyArray<File>()
        val rel = subDir.trimEnd('/', '\\')
        return entries.map {
            mapOf(
                "name" to it.name,
                "isDir" to it.isDirectory,
                "path" to if (rel.isEmpty()) it.name else "$rel/${it.name}"
            )
        }
    }

    // ---- HTTP（插件 + 商城共用）----

    fun pluginHttp(method: String, url: String, body: String?, headers: Map<String, String>): Map<String, Any> {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method.uppercase()
        conn.connectTimeout = 30000
        conn.readTimeout = 30000
        conn.instanceFollowRedirects = true
        for ((k, v) in headers) conn.setRequestProperty(k, v)
        if (body != null && method.equals("GET", true).not()) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = conn.responseCode
        val stream: InputStream = if (status in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
        conn.disconnect()
        return mapOf("ok" to (status in 200..299), "status" to status, "text" to text)
    }

    fun httpGetText(url: String): String? {
        return try {
            val r = pluginHttp("GET", url, null, emptyMap())
            if (r["ok"] == true) r["text"] as? String else null
        } catch (_: Exception) { null }
    }

    // ---- 商城 ----

    fun storeFetchIndex(indexUrl: String): Map<String, Any> {
        if (!indexUrl.startsWith("http://") && !indexUrl.startsWith("https://")) {
            return mapOf("ok" to false, "error" to "仅支持 http/https 地址")
        }
        val sep = if (indexUrl.contains('?')) '&' else '?'
        val url = indexUrl + sep + "_t=" + System.currentTimeMillis()
        return try {
            val text = httpGetText(url) ?: return mapOf("ok" to false, "error" to "索引请求失败")
            val data: Map<String, Any> = gson.fromJson(text, object : TypeToken<Map<String, Any>>() {}.type)
                ?: return mapOf("ok" to false, "error" to "索引格式错误")
            mapOf("ok" to true, "index" to data)
        } catch (e: Exception) {
            mapOf("ok" to false, "error" to (e.message ?: "索引请求失败"))
        }
    }

    fun storeInstall(entry: Map<String, Any>): Map<String, Any> {
        val id = entry["id"] as? String ?: return mapOf("ok" to false, "error" to "缺少插件 id")
        val name = entry["name"] as? String ?: id
        val version = entry["version"] as? String ?: "0.0.0"
        val description = entry["description"] as? String ?: ""
        val author = entry["author"] as? String ?: ""
        val repo = entry["repo"] as? String ?: return mapOf("ok" to false, "error" to "缺少 repo")
        val subpath = (entry["subpath"] as? String)?.trim()?.trim('/', '\\')
        val tag = entry["tag"] as? String ?: "v$version"

        val repoM = Regex("^https?://github\\.com/([^/]+)/([^/]+?)(?:\\.git)?/?$", RegexOption.IGNORE_CASE).find(repo.trim())
            ?: return mapOf("ok" to false, "error" to "repo 不是合法的 GitHub 仓库地址")
        val owner = repoM.groupValues[1]
        val repoName = repoM.groupValues[2]

        // 目标插件目录
        val dest = File(pluginsDir(), id)
        if (dest.exists()) return mapOf("ok" to false, "error" to "插件已安装：$id")

        // 下载 codeload tar.gz
        val url = "https://codeload.github.com/$owner/$repoName/tar.gz/refs/tags/$tag"
        val raw = try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 30000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "Pupurin-Loom-Android")
            if (conn.responseCode !in 200..299) return mapOf("ok" to false, "error" to "下载失败：HTTP ${conn.responseCode}")
            conn.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            return mapOf("ok" to false, "error" to "下载失败：${e.message}")
        }
        if (raw.size > 20 * 1024 * 1024) return mapOf("ok" to false, "error" to "插件包过大（>20MB）")

        // 解压 tar.gz → 提取 manifest.json / main.js（及子目录）
        val files = try { parseTarGz(raw) } catch (e: Exception) {
            return mapOf("ok" to false, "error" to "解压失败：${e.message}")
        }

        // 定位插件内容根：若指定 subpath，则剥掉该前缀；否则使用仓库根（剥掉顶层目录后）
        var manifestBytes: ByteArray? = null
        var mainPath: String? = null
        var mainBytes: ByteArray? = null
        for (f in files) {
            val rel = f.path
            if (subpath != null && !rel.startsWith("$subpath/")) continue
            val relInPlugin = if (subpath != null) rel.removePrefix("$subpath/") else rel
            when (relInPlugin) {
                "manifest.json" -> manifestBytes = f.data
                "main.js" -> { mainPath = relInPlugin; mainBytes = f.data }
            }
        }
        if (manifestBytes == null) return mapOf("ok" to false, "error" to "包内未找到 manifest.json")
        var mainText: String? = mainBytes?.toString(Charsets.UTF_8)
        if (mainText == null) {
            // 兼容只有 manifest（无 main.js）的插件
            mainText = ""
        }

        val manifestText = manifestBytes.toString(Charsets.UTF_8)
        val manifest: Map<String, Any> = try {
            gson.fromJson(manifestText, object : TypeToken<Map<String, Any>>() {}.type) ?: emptyMap()
        } catch (_: Exception) { return mapOf("ok" to false, "error" to "manifest.json 格式错误") }

        dest.mkdirs()
        File(dest, "manifest.json").writeText(manifestText, Charsets.UTF_8)
        File(dest, "main.js").writeText(mainText, Charsets.UTF_8)

        val meta = listPlugins().find { it.id == id }
        return if (meta != null) mapOf("ok" to true, "meta" to meta) else mapOf("ok" to false, "error" to "安装失败")
    }

    // ---- tar.gz 解析（零第三方依赖：GZIP + 自写 ustar）----

    private class TarFile(val path: String, val data: ByteArray)

    private fun parseTarGz(gz: ByteArray): List<TarFile> {
        val out = mutableListOf<TarFile>()
        val buf = GZIPInputStream(gz.inputStream()).use { it.readBytes() }
        var off = 0
        while (off + 512 <= buf.size) {
            val nameEnd = findZero(buf, off, 100)
            val name = String(buf, off, nameEnd, Charsets.UTF_8)
            if (name.isEmpty()) break
            val sizeFieldEnd = findZero(buf, off + 124, 12)
            val sizeField = String(buf, off + 124, sizeFieldEnd - (off + 124), Charsets.UTF_8).trim()
            val size = sizeField.toIntOrNull(8) ?: 0
            val typeflag = buf[off + 156].toInt()
            if (size < 0 || off + 512 + size > buf.size) break
            if (typeflag == 0 || typeflag == 0x30) {
                val parts = name.split('/')
                if (parts.size >= 2 && parts[0].isNotEmpty()) {
                    val rel = parts.drop(1).joinToString("/")
                    if (rel.isNotEmpty() && parts.drop(1).none { it == ".." || it == "." || it.isEmpty() }) {
                        out.add(TarFile(rel, buf.copyOfRange(off + 512, off + 512 + size)))
                    }
                }
            }
            off += 512 + ((size + 511) / 512) * 512
        }
        return out
    }

    private fun findZero(buf: ByteArray, start: Int, len: Int): Int {
        var i = start
        val end = minOf(start + len, buf.size)
        while (i < end && buf[i] != 0.toByte()) i++
        return i
    }
}