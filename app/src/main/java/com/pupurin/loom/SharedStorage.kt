package com.pupurin.loom

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 共享目录工具。
 *
 * 铃言织机° 把用户项目等数据放在公共共享目录
 *   /storage/emulated/0/PupurinLoom/
 * 下，而不是埋在应用的私有 data 目录（/Android/data/<包名>/）里，
 * 这样用户能直接在文件管理器 / 连接的电脑上访问、备份、迁移自己的作品。
 *
 * 访问系统：Android 11+（API 30）需要「所有文件访问」（MANAGE_EXTERNAL_STORAGE）；
 * Android 10 及以下需要运行时「存储权限」（WRITE_EXTERNAL_STORAGE）。
 */
object SharedStorage {

    /** 公共共享根目录名（显示在存储根路径下，随包名固定）。 */
    const val ROOT_DIR_NAME = "PupurinLoom"

    fun rootDir(): File = File(Environment.getExternalStorageDirectory(), ROOT_DIR_NAME)

    fun projectsDir(): File = File(rootDir(), "projects").apply { mkdirs() }

    /** 云端打包产物输出目录（共享）：/storage/emulated/0/PupurinLoom/builds/<项目名>/ */
    fun buildsDir(): File = File(rootDir(), "builds").apply { mkdirs() }

    /**
     * 当前是否已具备写入公共共享目录的权限。
     * - API 30+：要求「所有文件访问」（MANAGE_EXTERNAL_STORAGE）
     * - API 16..29：要求运行时可写外部存储（WRITE_EXTERNAL_STORAGE）
     */
    fun isWritable(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            granted == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 拉起系统授权页：API 30+ 打开「所有文件访问」设置，旧版本交给 Activity 申请运行时权限。 */
    fun openSystemPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                )
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                } catch (_: Exception) { /* 无对应设置项，忽略 */ }
            }
        }
        // API 29 及以下：运行时权限由 MainActivity 通过 launcher 请求，这里不处理。
    }
}