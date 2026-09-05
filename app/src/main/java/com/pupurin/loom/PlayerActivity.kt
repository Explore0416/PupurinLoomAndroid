package com.pupurin.loom

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * 内嵌 Ren'Py 引擎的运行宿主（骨架）。
 *
 * 目标行为：当「运行」按钮触发、且 App 已内嵌引擎（RenpyRuntime.hasEngine() == true）时，
 * 此 Activity 会接收项目路径并承载真正的游戏运行。
 *
 * 当前状态：骨架占位。真正运行需要：
 *   1) 用 tools/extract-renpy-runtime.sh 从官方游戏 APK 提取 librenpy.so 等原生库到
 *      jniLibs/，Python 运行时到 assets/renpy/；
 *   2) 把一个 Ren'Py 官方模板的 Java 引导代码（SDLActivity 初始化 + Python 引导）移植进来，
 *      在本类 onCreate 里按实际情况初始化引擎，并把 projectPath 作为 project directory 传入。
 *
 * TODO(下一版): 接入 SDL/Ren'Py bootstrap，把 projectPath / filePath / line 传给引擎。
 */
class PlayerActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val projectPath = intent.getStringExtra("projectPath") ?: ""
        val filePath = intent.getStringExtra("filePath") ?: ""
        val line = intent.getStringExtra("line") ?: ""

        // 骨架占位界面：告知这是运行宿主的框架。
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#1F1D1A"))
        }
        val tv = TextView(this).apply {
            text = buildString {
                appendLine("内嵌引擎运行宿主（骨架）")
                appendLine()
                appendLine("项目：$projectPath")
                if (filePath.isNotBlank()) appendLine("起始文件：$filePath" + if (line.isNotBlank()) ":$line" else "")
                appendLine()
                appendLine("尚未接入 SDL/Ren'Py 引导，请在下一版本完成引擎初始化后即可在此真正运行。")
            }
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(48, 48, 48, 48)
        }
        root.addView(
            tv,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
        setContentView(root)
    }
}