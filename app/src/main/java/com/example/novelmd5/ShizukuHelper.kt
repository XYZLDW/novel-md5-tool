package com.example.novelmd5

import android.content.Context
import android.os.IBinder
import android.util.Log
import rikka.shizuku.Shizuku

/**
 * Shizuku 提权封装（可选能力）：
 *  - Shizuku 不可用（未装 / 未授权 / 服务端未起）→ available=false，调用方退回 SAF 多写法兜底
 *  - 可用 → 用 shell 身份（uid 2000）直接写真实文件路径，彻底突破 iQOO
 *    DocumentsProvider 对"新建文件"的限制
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
     * 请求 Shizuku 授权（需在 Activity 里调；Shizuku 服务端可用时才有意义）。
     * 授权结果通过 Shizuku.addRequestPermissionResultListener 回调，不走 Activity 的
     * onRequestPermissionsResult，故这里只负责发起。
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

            // 执行 shell 命令：优先用公开 API（若存在），否则反射调 Shizuku.newProcess
            // （不同 Shizuku 版本该方法可能为 private/包级，反射保证编译期安全）。
            val proc = execCommand(arrayOf("sh", "-c", shCmd)) ?: return false

            // 读 stdout 判断成功标记（ShizukuRemoteProcess.getInputStream → java.io.InputStream）
            val stdout = readProcessOutput(proc)
            proc.javaClass.getMethod("destroy").invoke(proc)
            return stdout.contains("DSH_OK")
        } catch (e: Exception) {
            Log.w(TAG, "writeViaShell failed: ${e.message}")
            return false
        }
    }

    /**
     * 执行命令，返回一个带 getInputStream/destroy 的对象（ShizukuRemoteProcess 或反射句柄）。
     * 实现：用反射调 Shizuku.newProcess(String[], String[], String)；失败返回 null。
     */
    private fun execCommand(cmd: Array<String>): Any? {
        try {
            val cls = Shizuku::class.java
            // 尝试公开重载
            val m = cls.getMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
            return m.invoke(null, cmd, null as Array<String>?, null as String?)
        } catch (e: Exception) {
            // 公开方法不存在 → 试私有方法反射
            try {
                val m = cls.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
                m.isAccessible = true
                return m.invoke(null, cmd, null as Array<String>?, null as String?)
            } catch (e2: Exception) {
                Log.w(TAG, "no newProcess available: ${e2.message}")
                return null
            }
        }
    }

    /** 反射读进程的 stdout（ShizukuRemoteProcess.getInputStream）。 */
    private fun readProcessOutput(proc: Any): String {
        return try {
            val in = proc.javaClass.getMethod("getInputStream").invoke(proc) as java.io.InputStream
            val s = in.bufferedReader().readText()
            // 等进程结束再取退出码更稳
            try {
                proc.javaClass.getMethod("waitFor").invoke(proc)
            } catch (_: Exception) { }
            s
        } catch (e: Exception) {
            Log.w(TAG, "readProcessOutput failed: ${e.message}")
            ""
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
