package com.example.novelmd5

import android.content.Context
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 提权封装（可选能力）：
 *  - 未安装 Shizuku / 未授权 → available=false，调用方退回 SAF 多写法路径
 *  - 已授权 → 用 shell 身份（uid 2000）直接写真实文件路径，彻底突破 iQOO
 *    DocumentsProvider 对"新建文件"的限制
 *
 * 两条路径并存，由调用方按 available 选。
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"

    /** Shizuku 是否可用（已装 + 已授权 + 运行时正常）。 */
    fun available(): Boolean = try {
        Shizuku.pingBinder() != null
    } catch (e: Exception) {
        false
    }

    /**
     * 请求 Shizuku 授权（v13+ 自实现权限）。
     * 注意：必须在持有 Activity 的进程里调；Service 里会静默失败。
     * 真正的授权引导由 MainActivity 触发；这里只做 ping + 兜底 request。
     */
    fun requestAuthorization(context: Context) {
        try {
            Shizuku.requestPermission(1001)
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku requestAuthorization failed: ${e.message}")
        }
    }    /** 用 shell 提权把字节写到真实文件路径。成功返回 true。 */
    fun writeViaShell(realPath: String, bytes: ByteArray): Boolean {
        try {
            if (Shizuku.pingBinder() == null) return false
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val safeDir = escapeShellPath(realPath.substringBeforeLast('/', ""))
            val safeFile = escapeShellPath(realPath)
            val cmd = "mkdir -p '$safeDir' && echo '$b64' | base64 -d > '$safeFile' && echo DSH_OK"
            val out = Shizuku.exec(cmd) ?: ""
            return out.contains("DSH_OK")
        } catch (e: Exception) {
            Log.w(TAG, "writeViaShell failed: ${e.message}")
            return false
        }
    }

    /** 把 tree URI 映射成真实路径，用 Shizuku 提权写入。成功返回真实路径。 */
    fun writeToRealPath(tree: android.net.Uri, outName: String, bytes: ByteArray): String? {
        val dir = DocUtils.treeToRealPath(tree) ?: return null
        val f = java.io.File(dir, outName)
        return if (writeViaShell(f.absolutePath, bytes)) f.absolutePath else null
    }

    private fun escapeShellPath(p: String): String = p.replace("'", "'\\''")
}
