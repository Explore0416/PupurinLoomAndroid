// Pupurin° Loom — Android JS Bridge
// 注入于 document-start（先于渲染层执行），暴露与桌面版 preload 完全一致的 window.pupurin。
// 所有请求通过 window.__PupurinBridge（原生 addJavascriptInterface）转发，
// 原生侧完成后回调 window.__pupurinResolve(id, err, result)。
;(function () {
  if (window.pupurin) return

  var callbacks = {}
  var cid = 1

  // 原生“返回手势”接管钩子：渲染层若注册了该函数，则原生返回时优先调用它。
  // 约定返回 true=已处理返回（原生不再退出）；返回 false=请原生退出。
  window.__loomOnNativeBack = null

  function call(method, args) {
    return new Promise(function (resolve, reject) {
      var id = String(cid++)
      callbacks[id] = { resolve: resolve, reject: reject }
      try {
        window.__PupurinBridge.handle(JSON.stringify({ id: id, method: method, args: args || [] }))
      } catch (e) {
        delete callbacks[id]
        reject(e)
      }
    })
  }

  window.__pupurinResolve = function (id, err, result) {
    var cb = callbacks[id]
    if (!cb) return
    delete callbacks[id]
    if (err) cb.reject(new Error(err))
    else cb.resolve(result)
  }

  // 全屏状态事件回调（原生侧可能触发）
  var fullscreenCbs = []
  window.__pupurinFullscreen = function (isFs) {
    var cbs = fullscreenCbs.slice()
    for (var i = 0; i < cbs.length; i++) { try { cbs[i](isFs) } catch (e) {} }
  }

  function noopCleanup() { return function () {} }

  // 云端打包服务器：返回已配置地址；为空则触发原生输入框填写并保存
  var __cloudServerPromise = null
  function ensureCloudServer() {
    if (__cloudServerPromise) return __cloudServerPromise
    __cloudServerPromise = call('getCloudServer', []).then(function (info) {
      if (info && info.url) return info.url
      // 未配置 → 走原生输入框（prompt 对应 bridge）填写
      return call('promptCloudServer', [info && info.official ? info.official : ''])
    }).then(function (url) {
      __cloudServerPromise = null
      return (url || '').trim()
    })
    return __cloudServerPromise
  }

  var api = {
    getBackendPort: function () { return call('getBackendPort', []) },
    getBackendStatus: function () { return call('getBackendStatus', []) },

    // 项目管理
    listProjects: function () { return call('listProjects', []) },
    createProject: function (name, path, opts) { return call('createProject', [name, path, opts]) },
    openProject: function (id) { return call('openProject', [id]) },
    deleteProject: function (id) { return call('deleteProject', [id]) },
    getDefaultDir: function () { return call('getDefaultDir', []) },
    getVisibleDir: function () { return call('getVisibleDir', []) },
    importProject: function (sourcePath) { return call('importProject', [sourcePath]) },
    showProjectInFinder: function (projectPath) { return call('showProjectInFinder', [projectPath]) },
    runGame: function (projectPath) { return call('runGame', [projectPath]) },
    runGameFromLine: function (projectPath, filePath, line) { return call('runGameFromLine', [projectPath, filePath, line]) },
    // 云端打包：先确保已配置服务器地址（未配置则触发原生输入框→保存→返回地址）
    packageGame: function (projectPath, platform) {
      return ensureCloudServer().then(function (url) {
        if (!url) return { logs: ['已取消打包：未配置云端打包服务器。'] }
        return call('packageGame', [projectPath, platform || 'pc'])
      })
    },
    packageWeb: function (projectPath, opts) {
      return ensureCloudServer().then(function (url) {
        if (!url) return { logs: ['已取消打包：未配置云端打包服务器。'] }
        return call('packageWeb', [projectPath, opts || {}])
      })
    },
    packageMobile: function (projectPath, opts) {
      return ensureCloudServer().then(function (url) {
        if (!url) return { logs: ['已取消打包：未配置云端打包服务器。'] }
        return call('packageMobile', [projectPath, opts || {}])
      })
    },

    // 返回已配置的服务器地址；为空则在原生层弹输入框让用户填写并保存
    ensureCloudServer: function () { return ensureCloudServer() },

    // 云端打包设置：查询当前渠道(official|custom) 与自建地址
    getCloudServerSettings: function () { return call('getCloudServerSettings', []) },
    setCloudServerSettings: function (mode, customUrl) {
      return call('setCloudServerSettings', [mode, customUrl])
    },
    // 打开原生「云端打包设置」对话框（官方打包 / 自建服务器 + 地址），返回当前生效地址
    openCloudServerSettings: function () { return call('openCloudServerSettings', []) },

    // 运行已导出的游戏（APK）与内嵌引擎探测
    hasRenpyEngine: function () { return call('hasRenpyEngine', []) },
    engineInfo: function () { return call('engineInfo', []) },
    listRenpyLikeGames: function () { return call('listRenpyLikeGames', []) },
    launchPackage: function (pkg) { return call('launchPackage', [pkg]) },
    installExportedGame: function () { return call('installExportedGame', []) },

    // Ren'Py SDK 引导
    sdkStatus: function () { return call('sdkStatus', []) },
    openPrivacySettings: function () { return call('openPrivacySettings', []) },
    openSdkDownload: function () { return call('openSdkDownload', []) },
    sdkOpenLauncher: function () { return call('sdkOpenLauncher', []) },
    pickImageFile: function () { return call('pickImageFile', []) },
    revealPath: function (p) { return call('revealPath', [p]) },
    openExternal: function (url) { return call('openExternal', [url]) },

    // 应用设置 + 更新检查
    getSettings: function () { return call('getSettings', []) },
    setSetting: function (key, value) { return call('setSetting', [key, value]) },
    checkUpdate: function () { return call('checkUpdate', []) },
    getCloudServer: function () { return call('getCloudServer', []) },
    setCloudServer: function (url) { return call('setCloudServer', [url]) },
    testCloudServer: function () { return call('testCloudServer', []) },
    pickDirectory: function () { return call('pickDirectory', []) },
    probeFs: function (dir) { return call('probeFs', [dir]) },

    // 窗口事件
    onFullscreenChange: function (cb) {
      fullscreenCbs.push(cb)
      return function () {
        var i = fullscreenCbs.indexOf(cb)
        if (i >= 0) fullscreenCbs.splice(i, 1)
      }
    },
    getIsFullscreen: function () { return call('getIsFullscreen', []) },
    onMenuAction: function (cb) { return noopCleanup() },
    onBeforeClose: function (cb) { return noopCleanup() },
    confirmClose: function () { return call('confirmClose', []) },
    cancelClose: function () { return call('cancelClose', []) },
    setMenuView: function (view) { /* Android 无菜单栏，no-op */ },

    // 插件系统
    listPlugins: function () { return call('listPlugins', []) },
    loadPluginMain: function (id) { return call('loadPluginMain', [id]) },
    setPluginEnabled: function (id, enabled) { return call('setPluginEnabled', [id, enabled]) },
    setPluginTrusted: function (id, trusted) { return call('setPluginTrusted', [id, trusted]) },
    openPluginsDir: function () { return call('openPluginsDir', []) },
    openPluginMain: function (id) { return call('openPluginMain', [id]) },
    getPluginData: function (id) { return call('getPluginData', [id]) },
    setPluginData: function (id, data) { return call('setPluginData', [id, data]) },
    pluginFsRead: function (projectPath, subPath) { return call('pluginFsRead', [projectPath, subPath]) },
    pluginFsWrite: function (projectPath, subPath, content) { return call('pluginFsWrite', [projectPath, subPath, content]) },
    pluginFsList: function (projectPath, subDir) { return call('pluginFsList', [projectPath, subDir]) },
    pluginFsUploadImage: function (projectPath) { return call('pluginFsUploadImage', [projectPath]) },
    pluginHttp: function (method, url, body, headers) { return call('pluginHttp', [method, url, body, headers]) },
    pluginExec: function (command) { return call('pluginExec', [command]) },
    storeFetchIndex: function (indexUrl) { return call('storeFetchIndex', [indexUrl]) },
    storeInstall: function (entry) { return call('storeInstall', [entry]) },
    createPlugin: function (input) { return call('createPlugin', [input]) },

    // 角色管理
    loadCharacters: function (projectRoot) { return call('loadCharacters', [projectRoot]) },
    saveCharacters: function (projectRoot, characters) { return call('saveCharacters', [projectRoot, characters]) },
    newCharacter: function (name) { return call('newCharacter', [name]) },
    newSprite: function (name) { return call('newSprite', [name]) },
    parseCharactersFromScript: function (projectRoot) { return call('parseCharactersFromScript', [projectRoot]) },

    // 变量管理
    loadVariables: function (projectRoot) { return call('loadVariables', [projectRoot]) },
    saveVariables: function (projectRoot, variables) { return call('saveVariables', [projectRoot, variables]) },
    newVariable: function (name) { return call('newVariable', [name]) },
    parseVariablesFromScript: function (projectRoot) { return call('parseVariablesFromScript', [projectRoot]) },

    // 文件保存
    saveScript: function (projectPath, content) { return call('saveScript', [projectPath, content]) },

    // 资源管理
    listFiles: function (projectPath, subDir) { return call('listFiles', [projectPath, subDir]) },
    createDir: function (projectPath, subDir) { return call('createDir', [projectPath, subDir]) },
    createFile: function (projectPath, subPath, content) { return call('createFile', [projectPath, subPath, content]) },
    renameFile: function (projectPath, oldPath, newName) { return call('renameFile', [projectPath, oldPath, newName]) },
    deleteFile: function (projectPath, subPath) { return call('deleteFile', [projectPath, subPath]) },
    moveFile: function (projectPath, srcPath, destDir) { return call('moveFile', [projectPath, srcPath, destDir]) },
    setStoryMark: function (projectPath, filePath, mark) { return call('setStoryMark', [projectPath, filePath, mark]) },
    readFile: function (projectPath, subPath) { return call('readFile', [projectPath, subPath]) },
    importFile: function (projectPath, destSubDir, srcFilePath) { return call('importFile', [projectPath, destSubDir, srcFilePath]) },
    importImages: function (projectPath) { return call('importImages', [projectPath]) },
    pickFiles: function () { return call('pickFiles', []) },
    pickAudioFiles: function () { return call('pickAudioFiles', []) },
    readImageBase64: function (projectPath, subPath) { return call('readImageBase64', [projectPath, subPath]) },
    writeImageBase64: function (projectPath, subPath, dataUrl) { return call('writeImageBase64', [projectPath, subPath, dataUrl]) },
    readAudioBase64: function (projectPath, subPath) { return call('readAudioBase64', [projectPath, subPath]) },
    scanNonAsciiFiles: function (projectPath) { return call('scanNonAsciiFiles', [projectPath]) },
    applyNonAsciiRename: function (projectPath, items) { return call('applyNonAsciiRename', [projectPath, items]) },
    listRpyFiles: function (projectPath) { return call('listRpyFiles', [projectPath]) },
    saveRpyFile: function (projectPath, subPath, content) { return call('saveRpyFile', [projectPath, subPath, content]) },
    // 注册原生返回手势处理：fn() 返回 true=已处理返回，false=请原生退出应用
    setNativeBackHandler: function (fn) { window.__loomOnNativeBack = typeof fn === 'function' ? fn : null; return true }
  }

  window.pupurin = api
})()