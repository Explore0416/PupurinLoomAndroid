package com.pupurin.loom

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.provider.Settings
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat
import androidx.webkit.WebViewCompat
import androidx.documentfile.provider.DocumentFile
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import com.pupurin.loom.bridge.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Pupurin° Loom（铃言织机°）Android 版主界面。
 * 通过 WebView + WebViewAssetLoader 离线加载打包进 assets 的渲染层，
 * 并通过 JS Bridge（window.pupurin）暴露与桌面版 preload 完全一致的 API。
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var projectStore: ProjectStore
    private lateinit var fsStore: FsStore
    private lateinit var pluginStore: PluginStore
    private lateinit var cloudPackager: CloudPackager

    private val gson = Gson()
    private val executor: ExecutorService = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isFullscreen = false

    // 标记是否正在拉起外部界面（SAF 选择器 / 外部浏览器），期间禁止自动进入 PiP
    private var launchingExternal = false

    private lateinit var pickMulti: ActivityResultLauncher<Array<String>>
    private lateinit var pickSingle: ActivityResultLauncher<Array<String>>
    private lateinit var apkPicker: ActivityResultLauncher<Array<String>>
    private lateinit var pickDir: ActivityResultLauncher<Uri?>
    private lateinit var writeStoragePerm: ActivityResultLauncher<String>

    private lateinit var renpyRuntime: RenpyRuntime
    private var installReceiver: BroadcastReceiver? = null

    private class PendingPick(val id: String, val method: String, val args: JsonArray)
    private var pendingPick: PendingPick? = null

    @Suppress("unused")
    private class BridgeRequest(val id: String, val method: String, val args: JsonArray)

    companion object {
        private val PICKER_METHODS = setOf("pickFiles", "pickAudioFiles", "importImages", "pluginFsUploadImage", "pickImageFile", "installExportedGame", "pickDirectory")
        const val VERSION = "0.4.6"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        projectStore = ProjectStore(applicationContext)
        fsStore = FsStore(applicationContext)
        pluginStore = PluginStore(applicationContext)
        renpyRuntime = RenpyRuntime(applicationContext)
        cloudPackager = CloudPackager(applicationContext)

        registerPickers()
        registerInstallReceiver()
        ensureSharedStorageAccess()
        createWebView()
        setContentView(webView)
    }

    // ---- 选择器（SAF）----

    private fun registerPickers() {
        pickMulti = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            finishPick(uris.map { it.toString() })
        }
        pickSingle = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            finishPick(if (uri == null) emptyList() else listOf(uri.toString()))
        }
        apkPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            finishPick(if (uri == null) emptyList() else listOf(uri.toString()))
        }
        pickDir = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            finishPickDir(uri)
        }
        // API 29 及以下：申请可写存储权限（用于公共共享目录 PupurinLoom）
        writeStoragePerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted && Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                // 权限被拒：走系统设置页让用户手动开启（部分厂商界面差异，尽力而为）
                try {
                    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    i.data = Uri.parse("package:$packageName")
                    startActivity(i)
                } catch (_: Exception) { /* ignored */ }
            }
        }
    }

    // ---- 共享目录访问（公共共享目录 /storage/emulated/0/PupurinLoom/）----

    /** 首次进入时确保具备写入公共共享目录的权限。 */
    private fun ensureSharedStorageAccess() {
        if (SharedStorage.isWritable(this)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            SharedStorage.openSystemPermissionSettings(this)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            writeStoragePerm.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    private fun launchPicker(id: String, method: String, args: JsonArray) {
        pendingPick = PendingPick(id, method, args)
        launchingExternal = true
        when (method) {
            "pickFiles" -> pickMulti.launch(arrayOf("*/*"))
            "pickAudioFiles" -> pickMulti.launch(arrayOf("audio/*"))
            "importImages" -> pickMulti.launch(arrayOf("image/*"))
            "pluginFsUploadImage", "pickImageFile" -> pickSingle.launch(arrayOf("image/*"))
            "installExportedGame" -> apkPicker.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
            "pickDirectory" -> pickDir.launch(null)
        }
    }

    private fun finishPick(uris: List<String>) {
        val p = pendingPick ?: return
        pendingPick = null
        val method = p.method
        val args = p.args
        when (method) {
            "pickFiles", "pickAudioFiles" -> resolve(p.id, null, uris)
            "pickImageFile" -> resolve(p.id, null, uris.firstOrNull())
            "importImages" -> {
                val projectPath = args.strOrThrow(0)
                executor.execute {
                    try { resolve(p.id, null, fsStore.importImagesFrom(projectPath, uris)) }
                    catch (e: Exception) { resolve(p.id, e.message, null) }
                }
            }
            "pluginFsUploadImage" -> {
                val projectPath = args.strOrThrow(0)
                executor.execute {
                    try {
                        if (uris.isEmpty()) resolve(p.id, null, mapOf("path" to "", "name" to "", "cancelled" to true))
                        else resolve(p.id, null, fsStore.uploadImageToGallery(projectPath, uris[0]))
                    } catch (e: Exception) { resolve(p.id, e.message, null) }
                }
            }
            "installExportedGame" -> {
                if (uris.isEmpty()) {
                    resolve(p.id, null, mapOf("ok" to false, "cancelled" to true))
                } else {
                    executor.execute {
                        try {
                            val cached = copyUriToCache(uris[0])
                            resolve(p.id, null, mapOf("ok" to true, "status" to "installing", "apk" to cached))
                            runOnMain { launchInstallApk(cached) }
                        } catch (e: Exception) {
                            resolve(p.id, e.message, null)
                        }
                    }
                }
            }
        }
    }

    // 目录选择（SAF OpenDocumentTree）：返回拷贝到本地的目录绝对路径
    private fun finishPickDir(uri: Uri?) {
        val p = pendingPick ?: return
        pendingPick = null
        if (uri == null) {
            resolve(p.id, null, null)
            return
        }
        executor.execute {
            try {
                resolve(p.id, null, copyTreeToCache(uri))
            } catch (e: Exception) {
                resolve(p.id, e.message, null)
            }
        }
    }

    /** 将 SAF 目录树递归拷贝到 cacheDir/picked/<uuid>，返回本地绝对路径。 */
    private fun copyTreeToCache(uri: Uri): String {
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Exception) { /* 持久化读权限可选 */ }
        val src = DocumentFile.fromTreeUri(this, uri)
            ?: throw Exception("无法访问所选目录")
        val destRoot = File(cacheDir, "picked")
        if (!destRoot.exists()) destRoot.mkdirs()
        val dest = File(destRoot, "pick-" + UUID.randomUUID().toString())
        dest.mkdirs()
        copyDocumentTree(src, dest)
        return dest.absolutePath
    }

    private fun copyDocumentTree(
        src: DocumentFile,
        dest: File,
        visited: MutableSet<String> = java.util.HashSet()
    ) {
        // 按 uri 唯一去重：部分 SAF 提供者会把「同一节点」既当普通条目、又当目录/自身重复返回。
        // 若只在目录分支去重，该节点作为文件已落盘、又以同名目录再出现时，会往“文件里面”再写同名子项，
        // 造成 `<父级是文件>/<同名>`（如 …/SourceHanSansLite.ttf/SourceHanSansLite.ttf）并因父级非目录而报 ENOENT。
        // 对文件与目录都登记 uri，一旦同一 uri 再次出现即跳过——同一物理节点只需拷贝一次。
        if (!visited.add(src.uri.toString())) return

        if (src.isDirectory) {
            // 目标名已被「同名文件」占用时，让文件优先，跳过该伪目录
            if (dest.isFile) return
            dest.mkdirs()
            val parentName = dest.name
            for (child in src.listFiles() ?: emptyArray()) {
                val name = safeName(child.name)
                if (name.isEmpty()) continue // 忽略无有效名称的条目（避免 File(dest,"") 指回自身）
                // 跳过「目录自身」：子节点名与当前目标目录同名，视为提供者返回了自身/父节点
                if (child.isDirectory && name == parentName) continue
                copyDocumentTree(child, File(dest, name), visited)
            }
        } else {
            val name = safeName(src.name)
            if (name.isEmpty()) return
            // 兜底：若前面把 dest 误建成了空目录（同名目录/文件冲突），清掉它，确保文件落到正确路径
            if (dest.isDirectory && dest.listFiles()?.isEmpty() == true) dest.delete()
            dest.mkdirs()
            val out = File(dest, name)
            contentResolver.openInputStream(src.uri)?.use { ins ->
                out.outputStream().use { outs -> ins.copyTo(outs) }
            } ?: throw Exception("无法读取所选文件: $name")
        }
    }

    /** 清洗子项名称：去空白、拒绝 "." ".." 与分隔符，返回安全名称（可能为空则跳过）。 */
    private fun safeName(raw: String?): String {
        if (raw == null) return ""
        val s = raw.trim()
        if (s.isEmpty() || s == "." || s == ".." || s.contains('/') || s.contains('\\')) return ""
        return s
    }

    // ---- WebView ----

    private fun createWebView() {
        webView = WebView(this)
        webView.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        )
        webView.setBackgroundColor(Color.parseColor("#1F1D1A"))

        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.setGeolocationEnabled(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.textZoom = 100

        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                return assetLoader.shouldInterceptRequest(request.url)
            }
        }

        // 注入 window.pupurin JS 桥（document-start，先于渲染层执行）
        try {
            val bridgeJs = assets.open("bridge.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
            WebViewCompat.addDocumentStartJavaScript(webView, bridgeJs, setOf("*"))
        } catch (e: Exception) {
            android.util.Log.e("PupurinLoom", "注入 bridge.js 失败", e)
        }

        webView.addJavascriptInterface(Bridge(), "__PupurinBridge")
        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
    }

    // ---- JS Bridge ----

    inner class Bridge {
        @android.webkit.JavascriptInterface
        fun handle(requestJson: String) {
            val root: com.google.gson.JsonObject = try {
                JsonParser.parseString(requestJson).asJsonObject
            } catch (e: Exception) { return }
            val id = root.get("id")?.asString ?: return
            val method = root.get("method")?.asString ?: return
            val args = root.getAsJsonArray("args") ?: JsonArray()
            if (method in PICKER_METHODS) {
                mainHandler.post { launchPicker(id, method, args) }
            } else {
                executor.execute { dispatch(id, method, args) }
            }
        }
    }

    private fun resolve(id: String, err: String?, result: Any?) {
        val e = if (err != null) gson.toJson(err) else "null"
        val r = if (result == null) "null" else gson.toJson(result)
        val js = "window.__pupurinResolve(" + gson.toJson(id) + ", $e, $r)"
        mainHandler.post {
            try { webView.evaluateJavascript(js, null) } catch (_: Exception) { /* ignored */ }
        }
    }

    // ---- 分发 ----

    private fun dispatch(id: String, method: String, args: JsonArray) {
        try {
            val result: Any? = when (method) {
                // 后端（本地化后无 Python 后端）
                "getBackendPort" -> null
                "getBackendStatus" -> BackendStatus(false, null, null)

                // 项目管理
                "listProjects" -> projectStore.listProjects()
                "createProject" -> {
                    val opts = args.objOrNull(2) ?: emptyMap()
                    projectStore.createProject(
                        args.strOrThrow(0),
                        args.strOrNull(1),
                        opts["title"] as? String,
                        opts["buildName"] as? String,
                        opts["resolution"] as? String,
                        opts["scriptTemplate"] as? String
                    )
                }
                "openProject" -> projectStore.openProject(args.strOrThrow(0))
                "deleteProject" -> { projectStore.deleteProject(args.strOrThrow(0)); null }
                "getDefaultDir" -> projectStore.defaultProjectsDir().absolutePath
                "getVisibleDir" -> projectStore.defaultProjectsDir().absolutePath
                "importProject" -> projectStore.importProject(args.strOrThrow(0))
                "showProjectInFinder" -> null
                "runGame" -> startRun(args.strOrThrow(0), null, null)
                "runGameFromLine" -> startRun(args.strOrThrow(0), args.strOrNull(1), args.strOrNull(2))
                "packageGame" -> cloudPackager.pack(cloudServer(), args.strOrThrow(0), args.strOrNull(1)?.let { if (it == "all") "pc" else it } ?: "pc")
                "packageWeb" -> cloudPackager.pack(cloudServer(), args.strOrThrow(0), "web", args.objOrNull(1) ?: emptyMap())
                "packageMobile" -> cloudPackager.pack(cloudServer(), args.strOrThrow(0), "android", args.objOrNull(1) ?: emptyMap())
                "hasRenpyEngine" -> renpyRuntime.hasEngine()
                "engineInfo" -> renpyRuntime.engineInfo()
                "listRenpyLikeGames" -> renpyRuntime.listRenpyLikeGames()
                "launchPackage" -> renpyRuntime.launchPackage(args.strOrThrow(0))
                "installExportedGame" -> null // 实际走 picker

                // SDK / 系统
                "sdkStatus" -> SdkStatus(
                    found = true,
                    exe = null,
                    sdkDir = File(filesDir, "renpy").absolutePath, // 可写的自用目录，作为本地 SDK 占位
                    platform = "android",
                    downloadUrl = "https://www.renpy.org/latest.html",
                    webOk = true,     // Web 打包走云端，不依赖本机 Web 平台包
                    androidOk = true, // Android 打包走云端，不依赖本机 RAPT
                    iosOk = true,
                    androidSdkOk = true, // 均走云端，本机无需 JDK/Android SDK
                    jdkOk = true,
                    xcodeOk = true,
                    sdkWritable = true
                )
                "openPrivacySettings" -> null
                "openSdkDownload" -> { runOnMain { openExternalUrl("https://www.renpy.org/latest.html") }; null }
                "sdkOpenLauncher" -> false
                "pickImageFile" -> null // 实际走 picker
                "revealPath" -> null
                "openExternal" -> { runOnMain { openExternalUrl(args.strOrThrow(0)) }; null }

                // 设置 / 更新
                "getSettings" -> loadSettings()
                "setSetting" -> setSetting(args.strOrThrow(0), args.get(1))
                "checkUpdate" -> checkUpdateNow()
                "getCloudServer" -> mapOf("url" to cloudServer(), "official" to CloudPackager.OFFICIAL_SERVER_URL)
                "setCloudServer" -> { setCloudServerUrl(args.strOrThrow(0)); null }
                "getCloudServerSettings" -> cloudServerSettings()
                "setCloudServerSettings" -> { setCloudServerSettings(args.strOrNull(0) ?: "official", args.strOrNull(1) ?: ""); null }
                "openCloudServerSettings" -> promptCloudServer(null)
                "testCloudServer" -> cloudPackager.test(cloudServer())
                "promptCloudServer" -> promptCloudServer(args.strOrNull(0))
                "pickDirectory" -> null // 公共共享目录 PupurinLoom/projects 由应用托管，无需用户选路径
                "probeFs" -> mapOf(
                    "ok" to true, "target" to args.strOrThrow(0),
                    "testPath" to "", "processUid" to -1, "processGid" to -1,
                    "accessW" to true, "fsMkdir" to "ok", "shellMkdir" to "ok",
                    "writableLocations" to emptyList<Any>()
                )

                // 窗口
                "getIsFullscreen" -> isFullscreen
                "confirmClose" -> null
                "cancelClose" -> null

                // 插件
                "listPlugins" -> pluginStore.listPlugins()
                "loadPluginMain" -> pluginStore.loadPluginMain(args.strOrThrow(0))
                "setPluginEnabled" -> { pluginStore.setPluginEnabled(args.strOrThrow(0), args.get(1).asBoolean); null }
                "setPluginTrusted" -> { pluginStore.setPluginTrusted(args.strOrThrow(0), args.get(1).asBoolean); null }
                "openPluginsDir" -> null
                "openPluginMain" -> pluginStore.openPluginMain(args.strOrThrow(0))
                "getPluginData" -> pluginStore.getPluginData(args.strOrThrow(0))
                "setPluginData" -> {
                    val dataType = object : TypeToken<Map<String, Any>>() {}.type
                    val data: Map<String, Any> = gson.fromJson(args.get(1), dataType) ?: emptyMap()
                    pluginStore.setPluginData(args.strOrThrow(0), data); null
                }
                "pluginFsRead" -> pluginStore.pluginFsRead(args.strOrThrow(0), args.strOrThrow(1))
                "pluginFsWrite" -> { pluginStore.pluginFsWrite(args.strOrThrow(0), args.strOrThrow(1), args.strOrThrow(2)); null }
                "pluginFsList" -> pluginStore.pluginFsList(args.strOrThrow(0), args.strOrNull(1) ?: "")
                "pluginFsUploadImage" -> null // 实际走 picker
                "pluginHttp" -> pluginStore.pluginHttp(
                    args.strOrNull(0) ?: "GET",
                    args.strOrThrow(1),
                    args.strOrNull(2),
                    (args.objOrNull(3) ?: emptyMap()).mapValues { it.value.toString() }
                )
                "pluginExec" -> mapOf("code" to null, "stdout" to "", "stderr" to "Android 版出于安全考虑不支持命令执行")
                "storeFetchIndex" -> pluginStore.storeFetchIndex(args.strOrThrow(0))
                "storeInstall" -> pluginStore.storeInstall(args.objOrThrow(0))
                "createPlugin" -> pluginStore.createPlugin(
                    (args.objOrNull(0) ?: emptyMap()).mapValues { it.value.toString() }
                )

                // 角色
                "loadCharacters" -> projectStore.loadCharacters(args.strOrThrow(0))
                "saveCharacters" -> {
                    val t = object : TypeToken<List<CharacterMeta>>() {}.type
                    val chars: List<CharacterMeta> = gson.fromJson(args.get(1), t) ?: emptyList()
                    projectStore.saveCharacters(args.strOrThrow(0), chars); null
                }
                "newCharacter" -> projectStore.newCharacter(args.strOrThrow(0))
                "newSprite" -> projectStore.newSprite(args.strOrThrow(0))
                "parseCharactersFromScript" -> projectStore.parseCharactersFromScript(args.strOrThrow(0))

                // 变量
                "loadVariables" -> projectStore.loadVariables(args.strOrThrow(0))
                "saveVariables" -> {
                    val t = object : TypeToken<List<VariableMeta>>() {}.type
                    val vars: List<VariableMeta> = gson.fromJson(args.get(1), t) ?: emptyList()
                    projectStore.saveVariables(args.strOrThrow(0), vars); null
                }
                "newVariable" -> projectStore.newVariable(args.strOrThrow(0))
                "parseVariablesFromScript" -> projectStore.parseVariablesFromScript(args.strOrThrow(0))

                // 文件
                "saveScript" -> { fsStore.saveScript(args.strOrThrow(0), args.strOrThrow(1)); null }
                "listFiles" -> fsStore.listFiles(args.strOrThrow(0), args.strOrNull(1) ?: "")
                "createDir" -> { fsStore.createDir(args.strOrThrow(0), args.strOrThrow(1)); null }
                "createFile" -> { fsStore.createFile(args.strOrThrow(0), args.strOrThrow(1), args.strOrNull(2) ?: ""); null }
                "renameFile" -> { fsStore.rename(args.strOrThrow(0), args.strOrThrow(1), args.strOrThrow(2)); null }
                "deleteFile" -> { fsStore.delete(args.strOrThrow(0), args.strOrThrow(1)); null }
                "moveFile" -> { fsStore.move(args.strOrThrow(0), args.strOrThrow(1), args.strOrThrow(2)); null }
                "setStoryMark" -> { fsStore.setStoryMark(args.strOrThrow(0), args.strOrThrow(1), args.strOrNull(2)); null }
                "readFile" -> fsStore.readFile(args.strOrThrow(0), args.strOrThrow(1))
                "importFile" -> fsStore.importFile(args.strOrThrow(0), args.strOrThrow(1), args.strOrThrow(2))
                "importImages" -> null // 实际走 picker
                "pickFiles" -> null // 实际走 picker
                "pickAudioFiles" -> null // 实际走 picker
                "readImageBase64" -> fsStore.readImageBase64(args.strOrThrow(0), args.strOrThrow(1))
                "writeImageBase64" -> { fsStore.writeImageBase64(args.strOrThrow(0), args.strOrThrow(1), args.strOrThrow(2)); null }
                "readAudioBase64" -> fsStore.readAudioBase64(args.strOrThrow(0), args.strOrThrow(1))
                "scanNonAsciiFiles" -> fsStore.scanNonAsciiFiles(args.strOrThrow(0))
                "applyNonAsciiRename" -> {
                    val t = object : TypeToken<List<Map<String, Any>>>() {}.type
                    val items: List<Map<String, Any>> = gson.fromJson(args.get(1), t) ?: emptyList()
                    fsStore.applyNonAsciiRename(args.strOrThrow(0), items)
                }
                "listRpyFiles" -> fsStore.listRpyFiles(args.strOrThrow(0))
                "saveRpyFile" -> { fsStore.saveRpyFile(args.strOrThrow(0), args.strOrThrow(1), args.strOrThrow(2)); null }

                else -> throw Exception("未知桥接方法: $method")
            }
            resolve(id, null, result)
        } catch (e: Exception) {
            resolve(id, e.message ?: "操作失败", null)
        }
    }

    private fun unsupported(msg: String): Map<String, Any> = mapOf("success" to false, "error" to msg)
    private fun unsupportedLogs(msg: String): Map<String, Any> = mapOf("logs" to listOf(msg))

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun openExternalUrl(url: String) {
        try {
            launchingExternal = true
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) { /* ignored */ }
    }

    // ---- 运行已导出的游戏（APK）----

    /** 将 SAF 选择的 APK 拷贝到缓存目录，返回绝对路径。 */
    private fun copyUriToCache(uriStr: String): String {
        val uri = Uri.parse(uriStr)
        val dir = File(cacheDir, "shared")
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, "renpy-game.apk")
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { output -> input.copyTo(output) }
        } ?: throw Exception("无法读取所选文件")
        return out.absolutePath
    }

    /** 弹出系统安装器安装 APK；API 26+ 先确保「安装未知应用」权限。 */
    private fun launchInstallApk(apkPath: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (_: Exception) { /* ignored */ }
            return
        }
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", File(apkPath))
        } catch (_: Exception) { null } ?: return
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try { startActivity(intent) } catch (_: Exception) { /* ignored */ }
    }

    /** 监听安装完成事件，自动启动刚装好的游戏。 */
    private fun registerInstallReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                renpyRuntime.launchPackage(pkg)
            }
        }
        installReceiver = receiver
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    // ---- 设置（filesDir/settings.json）----

    private fun settingsFile(): File = File(filesDir, "settings.json")

    private fun loadSettings(): Map<String, Any> {
        return try {
            val t = object : TypeToken<Map<String, Any>>() {}.type
            gson.fromJson(settingsFile().readText(Charsets.UTF_8), t) ?: emptyMap()
        } catch (_: Exception) { emptyMap() }
    }

    private fun saveSettings(map: Map<String, Any>) {
        val tmp = File(filesDir, "settings.json.tmp")
        tmp.writeText(gson.toJson(map), Charsets.UTF_8)
        tmp.renameTo(settingsFile())
    }

    private fun setSetting(key: String, value: JsonElement): Map<String, Any> {
        val map = loadSettings().toMutableMap()
        val v: Any? = if (value.isJsonNull) null else gson.fromJson(value, Any::class.java)
        if (v == null) map.remove(key) else map[key] = v
        saveSettings(map)
        return map
    }

    // ---- 版本检查（GitHub Releases）----

    private val GITHUB_REPO = "Explore0416/PupurinLoomAndroid"
    private val GITHUB_LATEST_API = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"

    /**
     * 查询 GitHub 最新 Release，与本机版本比对后返回前端所需结构：
     * { configured, error, hasUpdate, current, latest, url, pageUrl, source, notes }。
     * 在后台线程（executor）上调用，可安全做阻塞网络请求。
     */
    private fun checkUpdateNow(): Map<String, Any?> {
        return try {
            val resp = httpGet(GITHUB_LATEST_API, "application/vnd.github+json")
            val obj = JsonParser.parseString(resp).asJsonObject
            val latestRaw = obj.get("tag_name")?.asString ?: throw Exception("无法解析最新版本")
            val latest = latestRaw.removePrefix("v")
            val pageUrl = obj.get("html_url")?.asString
                ?: "https://github.com/$GITHUB_REPO/releases/latest"
            val notes = obj.get("body")?.asString ?: ""
            val assets = obj.getAsJsonArray("assets") ?: JsonArray()

            // 优先取 release APK 的直链
            val apkNames = mutableListOf<String>()
            val urlByName = HashMap<String, String>()
            for (a in assets) {
                val an = a.asJsonObject.get("name")?.asString ?: continue
                val dl = a.asJsonObject.get("browser_download_url")?.asString ?: ""
                apkNames.add(an); urlByName[an] = dl
            }
            val downloadUrl = apkNames.firstOrNull { it.contains("release", true) && it.endsWith(".apk") }
                ?: apkNames.firstOrNull { it.endsWith(".apk") }
                ?: ""
            val realDownload = if (downloadUrl.isEmpty()) "" else (urlByName[downloadUrl] ?: "")

            val hasUpdate = compareVersions(latest, VERSION) > 0
            mapOf(
                "configured" to true,
                "error" to null,
                "hasUpdate" to hasUpdate,
                "current" to VERSION,
                "latest" to latest,
                "url" to (if (hasUpdate) realDownload else null),
                "pageUrl" to pageUrl,
                "source" to "github",
                "notes" to (if (hasUpdate) notes.trim() else "")
            )
        } catch (e: Exception) {
            mapOf(
                "configured" to true,
                "error" to (e.message ?: "检查更新失败"),
                "current" to VERSION,
                "hasUpdate" to false,
                "latest" to VERSION,
                "url" to null, "pageUrl" to null, "source" to "github", "notes" to ""
            )
        }
    }

    private fun httpGet(urlStr: String, accept: String): String {
        val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 20000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "PupurinLoom-Android")
        }
        return try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw Exception("获取最新版本失败 (HTTP $code)")
            body
        } finally {
            conn.disconnect()
        }
    }

    /** 简单语义化版本比较：0.4.5 < 0.4.6；返回正数表示 a > b。 */
    private fun compareVersions(a: String, b: String): Int {
        val as_ = a.trim().split(".").mapNotNull { it.toIntOrNull() ?: 0 }
        val bs = b.trim().split(".").mapNotNull { it.toIntOrNull() ?: 0 }
        val n = maxOf(as_.size, bs.size)
        for (i in 0 until n) {
            val x = if (i < as_.size) as_[i] else 0
            val y = if (i < bs.size) bs[i] else 0
            if (x != y) return x - y
        }
        return 0
    }

    // ---- 云端打包 / 运行 ----

    private fun cloudServer(): String {
        val s = loadSettings()
        val mode = s["cloudServerMode"] as? String
        if (mode == "custom") {
            val url = s["cloudServerUrl"] as? String
            if (!url.isNullOrBlank()) return url.trim()
        }
        return CloudPackager.OFFICIAL_SERVER_URL
    }

    private fun setCloudServerUrl(url: String) {
        val map = loadSettings().toMutableMap()
        if (url.isBlank()) {
            map.remove("cloudServerUrl")
            map.remove("cloudServerMode") // 回到官方打包
        } else {
            map["cloudServerUrl"] = url.trim()
            map["cloudServerMode"] = "custom" // 填了自建地址即切换为自建服务器
        }
        saveSettings(map)
    }

    /** 云端打包设置：mode = official|custom；customUrl 为自建服务器地址。 */
    private fun setCloudServerSettings(mode: String, customUrl: String) {
        val m = if (mode == "custom") "custom" else "official"
        val map = loadSettings().toMutableMap()
        if (m == "custom") map["cloudServerMode"] = "custom" else map.remove("cloudServerMode")
        if (customUrl.isBlank()) map.remove("cloudServerUrl") else map["cloudServerUrl"] = customUrl.trim()
        saveSettings(map)
    }

    private fun cloudServerSettings(): Map<String, Any> {
        val s = loadSettings()
        return mapOf(
            "mode" to (if ((s["cloudServerMode"] as? String) == "custom") "custom" else "official"),
            "customUrl" to (s["cloudServerUrl"] as? String ?: ""),
            "official" to CloudPackager.OFFICIAL_SERVER_URL,
            "active" to cloudServer()
        )
    }

    /** 弹系统对话框让用户配置云端打包渠道：官方打包 / 自建服务器 + 自建地址（持久化保存）。 */
    private fun promptCloudServer(defaultUrl: String?): String {
        val holder = java.util.concurrent.atomic.AtomicReference<String?>()
        val modeRef = java.util.concurrent.atomic.AtomicReference<String?>()
        val latch = java.util.concurrent.CountDownLatch(1)
        val current = cloudServerSettings()
        runOnUiThread {
            val urlInput = android.widget.EditText(this).apply {
                setText((defaultUrl ?: current["customUrl"] as? String) ?: "")
                hint = CloudPackager.OFFICIAL_SERVER_URL
                inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_VARIATION_URI
            }
            val radioOfficial = android.widget.RadioButton(this).apply {
                text = "使用官方打包服务器（${CloudPackager.OFFICIAL_SERVER_URL}）"
                isChecked = current["mode"] != "custom"
            }
            val radioCustom = android.widget.RadioButton(this).apply {
                text = "使用自建打包服务器"
                isChecked = current["mode"] == "custom"
            }
            val group = android.widget.RadioGroup(this).apply { addView(radioOfficial); addView(radioCustom) }
            val content = android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(24, 8, 24, 0)
                addView(group)
                addView(urlInput)
            }
            android.app.AlertDialog.Builder(this)
                .setTitle("云端打包设置")
                .setMessage("打包在服务器上进行。默认使用官方服务器；如需自建，请选择自建并填入地址（带 https://）。")
                .setView(content)
                .setPositiveButton("保存") { _, _ ->
                    val mode = if (radioCustom.isChecked) "custom" else "official"
                    modeRef.set(mode)
                    val url = urlInput.text.toString().trim()
                    holder.set(if (mode == "custom" && url.isNotEmpty()) url else null)
                }
                .setNegativeButton("取消", null)
                .setOnDismissListener { latch.countDown() }
                .show()
        }
        try { latch.await() } catch (_: InterruptedException) { /* ignore */ }
        val mode = modeRef.get() ?: return ""
        val url = holder.get().orEmpty()
        setCloudServerSettings(mode, url)
        return if (mode == "custom" && url.isNotEmpty()) url else cloudServer()
    }

    private fun startRun(projectPath: String, filePath: String?, line: String?): Map<String, Any> {
        if (!renpyRuntime.hasEngine()) {
            return mapOf(
                "success" to false,
                "hasEngine" to false,
                "error" to "设备端未内嵌 Ren'Py 引擎，暂不能在编辑器内热运行。\n" +
                    "方案一：用「云端打包」生成 APK 后安装运行；\n" +
                    "方案二：用 tools/extract-renpy-runtime.sh 提取引擎到 App 后重新打包（届时可内嵌运行）。"
            )
        }
        runOnMain {
            try {
                val intent = Intent(this, PlayerActivity::class.java).apply {
                    putExtra("projectPath", projectPath)
                    putExtra("filePath", filePath ?: "")
                    putExtra("line", line ?: "")
                }
                startActivity(intent)
            } catch (e: Exception) { /* ignored */ }
        }
        return mapOf("success" to true, "hasEngine" to true)
    }

    // ---- 返回键 / 小窗(PiP) / 全屏 ----

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onResume() {
        super.onResume()
        launchingExternal = false
    }

    // 全屏沉浸切换（系统 UI 可见性），供 future 前端触发
    @Suppress("unused")
    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        val flags = if (isFullscreen) {
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        } else {
            View.SYSTEM_UI_FLAG_VISIBLE
        }
        window.decorView.systemUiVisibility = flags
        webView.evaluateJavascript("window.__pupurinFullscreen($isFullscreen)", null)
    }

    override fun onDestroy() {
        installReceiver?.let { runCatching { unregisterReceiver(it) } }
        webView.destroy()
        executor.shutdown()
        super.onDestroy()
    }
}

// ---- JsonArray 便捷访问 ----

private fun JsonArray.strOrThrow(i: Int): String =
    if (i < size() && !get(i).isJsonNull) get(i).asString
    else throw Exception("缺少参数 #$i")

private fun JsonArray.strOrNull(i: Int): String? =
    if (i < size() && !get(i).isJsonNull) get(i).asString else null

private fun JsonArray.objOrNull(i: Int): Map<String, Any>? {
    if (i >= size() || get(i).isJsonNull || !get(i).isJsonObject) return null
    val gson = Gson()
    val t = object : TypeToken<Map<String, Any>>() {}.type
    return gson.fromJson(get(i), t)
}

private fun JsonArray.objOrThrow(i: Int): Map<String, Any> = objOrNull(i) ?: emptyMap()