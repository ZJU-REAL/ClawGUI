package com.clawgui.android.core.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 抓 logcat 环形缓冲区里的 ClawGUI 日志,落成 txt,给同事一键分享。
 *
 * 用途是**开发/调试阶段**收集现场 —— 同事复现 bug 后在"设置 → 关于 → 诊断 →
 * 导出诊断日志"里点一下,落到 `workspace/diagnostics/log_<ts>.txt`,走 Share Intent
 * 发出来就行,不需要电脑 + ADB。
 *
 * 上线前整个诊断入口可以从 UI 撤掉(见 `docs/diagnostics.md`),但这个类本身
 * 无副作用可以留着。
 *
 * 实现思路对齐 Python 侧的 "tail -n 5000 log.txt" —— logcat -d 一次性快照拿走,
 * 只要带 `ClawGUI/` 前缀的行,裁剪到最近 ~5000 行,避免大文件难传。
 */
object LogExporter {
    private const val MAX_LINES = 5000
    private const val TAG_SUBSTRING = "ClawGUI/"
    private const val DIR_NAME = "diagnostics"

    /** 把当前 logcat 导到 `<workspaceDir>/diagnostics/log_<ts>.txt`,返回文件。 */
    fun exportToFile(workspaceDir: File): File {
        val dir = File(workspaceDir, DIR_NAME).also { it.mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "log_$ts.txt")
        val lines = readLogcatFiltered()
        file.bufferedWriter().use { w ->
            w.write("# ClawGUI diagnostic log — exported at ${Date()}\n")
            w.write("# device=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT}\n")
            w.write("# filter: logcat -d -v time, tag contains \"$TAG_SUBSTRING\", last $MAX_LINES lines\n")
            w.write("# --------------------------------------------------------------\n")
            lines.forEach {
                w.write(it)
                w.write("\n")
            }
        }
        Log.i("LogExporter", null, "exported ${lines.size} lines to ${file.absolutePath}")
        return file
    }

    /** 清空 `diagnostics/` 目录下的旧 log,返回删掉的文件数。 */
    fun clearOldLogs(workspaceDir: File): Int {
        val dir = File(workspaceDir, DIR_NAME)
        if (!dir.exists()) return 0
        val files = dir.listFiles { f -> f.isFile && f.name.startsWith("log_") && f.name.endsWith(".txt") }
            ?: return 0
        var n = 0
        for (f in files) if (f.delete()) n++
        Log.i("LogExporter", null, "cleared $n old log files")
        return n
    }

    /**
     * 走 Share Intent 把 [file] 扔给系统选择器(微信/邮件/文件管理器都行)。
     * FileProvider authority 固定 "com.clawgui.android.fileprovider",需在 AndroidManifest 注册。
     */
    fun buildShareIntent(context: Context, file: File): Intent {
        val authority = "${context.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(context, authority, file)
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "ClawGUI 诊断日志 ${file.name}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, "分享诊断日志") }
    }

    private fun readLogcatFiltered(): List<String> {
        return try {
            // -d 一次性打印并退出;-v time 拿时间戳 + 等级 + tag
            val proc = ProcessBuilder("logcat", "-d", "-v", "time")
                .redirectErrorStream(true)
                .start()
            val all = proc.inputStream.bufferedReader().useLines { seq ->
                seq.filter { it.contains(TAG_SUBSTRING) }.toList()
            }
            proc.waitFor()
            if (all.size <= MAX_LINES) all else all.subList(all.size - MAX_LINES, all.size)
        } catch (e: Exception) {
            Log.w("LogExporter", null, "logcat capture failed", e)
            listOf("# ERROR: logcat capture failed: ${e.message}")
        }
    }
}
