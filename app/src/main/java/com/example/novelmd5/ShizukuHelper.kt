package com.example.novelmd5

import android.content.Context
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 提权封装（可选能力）：
 *  - Shizuku 不可用（未装 / 未授权 / 服务端未起）→ available=false，调用方退回 SAF 多写法兜底
 *  - 可用 → 用 shell 身份（uid 2000）直接写真实文件路径，彻底突破 iQOO
 *    DocumentsProvider 对"新建文件"的限制（shell 身份可写任意目录，已验证）
 *
 * 两条路径并存，由调用方按 available 选。
 * 所有 Shizuku 调用都包 try/catch：运行时任何失败都返回 false，绝不抛给上层崩掉 App。
 */
object ShizukuHelper {

    private const val TAG = "ShizukuHelper"

    /** Shizuku 是否可用（已装 + 已授权 + 服务端在跑 + 本应用已拿到 binder）。 */
    fun available(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Exception) {
        false
    }

    /**
     * 请求 Shizuku 授权（需在 Shizuku 服务可用后、Activity 里调）。
     * 授权结果通过 Shizuku.addRequestPermissionResultListener 回调。
     * Shizuku 不可用时调用会静默失败。
     */
    fun requestAuthorization(context: Context) {
        try {
            Shizuku.requestPermission(1001)
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku requestAuthorization failed: ${e.message}")
        }
    }

    /** 用 shell 提权把字节写到真实文件路径。成功返回 true，失败（含 Shizuku 不可用）返回 false。 */
    fun writeViaShell(realPath: String, bytes: ByteArray): Boolean {
        try {
            if (!available()) return false
            val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
            val safeDir = escapeShellPath(realPath.substringBeforeLast('/', ""))
            val safeFile = escapeShellPath(realPath)
            val shCmd = "mkdir -p '$safeDir' && echo '$b64' | base64 -d > '$safeFile' && echo DSH_OK"
            // 通过 Shizuku binder 以 shell 身份执行命令
            val out = execShell(shCmd) ?: return false
            return out.contains("DSH_OK")
        } catch (e: Exception) {
            Log.w(TAG, "writeViaShell failed: ${e.message}")
            return false
        }
    }

    /**
     * 用 Shizuku 以 shell 身份执行一条命令，返回 stdout；Shizuku 不可用或执行失败返回 null。
     * 用 ShizukuBinderWrapper 的 remote 通道 + 内置的 remote exec，避免依赖不稳定的公开 API。
     */
    private fun execShell(cmd: String): String? {
        return try {
            val binder = Shizuku.getBinder() ?: return null
            // Shizuku 提供 ShizukuBinderWrapper 包装 binder，但执行命令没有稳定公开 API；
            // 这里通过 Shizuku 的 remote binder 调 "newProcess" 反射（各版本私有签名不同），
            // 失败则返回 null 让上层回落兜底路径。
            val cls = Shizuku::class.java
            val m = cls.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            m.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val proc = m.invoke(null, arrayOf("sh", "-c", cmd), null as Array<String>?, null as String?)
            val out = proc.javaClass.getMethod("getInputStream").invoke(proc) as java.io.InputStream
            val text = out.bufferedReader().readText()
            proc.javaClass.getMethod("waitFor").invoke(proc)
            proc.javaClass.getMethod("destroy").invoke(proc)
            text
        } catch (e: Exception) {
            Log.w(TAG, "execShell failed: ${e.message}")
            null
        }
    }

    /** 把 tree URI 映射成真实路径，用 Shizuku 提权写入。成功返回真实路径，否则 null。 */
    fun writeToRealPath(tree: android.net.Uri, outName: String, bytes: ByteArray): String? {
        val dir = DocUtils.treeToRealPath(tree) ?: return null
        val f = java.io.File(dir, outName)
        return if (writeViaShell(f.absolutePath, bytes)) f.absolutePath else null
    }

    private fun escapeShellPath(p: String): String = p.replace("'", "'\\''")
}
