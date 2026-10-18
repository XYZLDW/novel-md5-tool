package com.example.novelmd5

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 后台任务前台服务：
 *  - 功能1 重命名 / 功能2 替换 都丢给它跑，锁屏/切后台不被系统杀
 *  - 通知栏实时显示进度，完成显示结果
 *  - 输出 txt：优先写用户选的目录（有「全部文件访问」时用 File API 直写），
 *    写不了才退回公共下载目录
 */
class TaskService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var notMgr: NotificationManager? = null

    // 输出 txt 的落盘路径（供界面/结果提示用）
    @Volatile var lastOutputFile: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        notMgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel()
        startFg()

        val action = intent?.action ?: ACTION_STOP
        when (action) {
            ACTION_RENAME -> {
                val tree = intent?.getParcelableExtra<android.net.Uri>(EXTRA_TREE)
                if (tree != null) scope.launch { doRename(tree) }
                else finishTask("重命名：未收到文件夹参数")
            }
            ACTION_NOVEL -> {
                val novel = intent?.getParcelableExtra<android.net.Uri>(EXTRA_NOVEL)
                val md5Tree = intent?.getParcelableExtra<android.net.Uri>(EXTRA_MD5TREE)
                val outTree = intent?.getParcelableExtra<android.net.Uri>(EXTRA_OUTTREE)
                val sort = intent?.getIntExtra(EXTRA_SORT, 0) ?: 0
                if (novel != null && md5Tree != null) {
                    scope.launch { doNovel(novel, md5Tree, outTree, sort) }
                } else finishTask("替换：缺少小说或图片参数")
            }
            else -> finishTask("已完成")
        }
        return START_NOT_STICKY
    }

    // ---------- 功能1 ----------
    private suspend fun doRename(tree: android.net.Uri) {
        updateNote("正在扫描图片…")
        val all = withContext(Dispatchers.IO) { DocUtils.listImageDocs(contentResolver, tree) }
        val images = all.filter { DocUtils.isImage(it.name) }.sortedBy { it.name.lowercase() }
        if (images.isEmpty()) {
            finishTask("重命名：所选文件夹没有图片（共 ${all.size} 个文件）")
            return
        }
        updateNote("重命名中… 0 / ${images.size}")
        val res = RenameTask.run(images, contentResolver) { done, total ->
            updateNote("重命名中… $done / $total")
        }
        val msg = buildString {
            append("重命名完成：成功 ").append(res.success).append(" 张")
            if (res.failed > 0) {
                append("，失败 ").append(res.failed)
                res.failures.take(3).forEach { append("：").append(it) }
            }
        }
        finishTask(msg)
    }

    // ---------- 功能2 ----------
    private suspend fun doNovel(
        novel: android.net.Uri,
        md5Tree: android.net.Uri,
        outTree: android.net.Uri?,
        sortIdx: Int
    ) {
        updateNote("正在扫描图片…")
        val all = withContext(Dispatchers.IO) { DocUtils.listImageDocs(contentResolver, md5Tree) }
        val images = all
            .filter { DocUtils.isImage(it.name) }
            .sortedWith(sortComparator(sortIdx))
        if (images.isEmpty()) {
            finishTask("替换：所选文件夹没有图片")
            return
        }

        updateNote("正在读取 ${images.size} 张图的 MD5…")
        val md5s = withContext(Dispatchers.IO) {
            images.map { doc ->
                contentResolver.openInputStream(doc.uri)?.use { Md5.ofStream(it) } ?: ""
            }.filter { it.isNotEmpty() }
        }

        val novelText = withContext(Dispatchers.IO) {
            contentResolver.openInputStream(novel)?.use { it.readBytes() }?.decodeToString() ?: ""
        }
        if (novelText.isEmpty()) {
            finishTask("替换：读不到小说 txt 内容")
            return
        }

        val res = NovelTask.process(novelText, md5s)

        updateNote("正在写出结果文件…")
        val baseName = DocUtils.displayName(contentResolver, novel)
        val outName = baseName.removeSuffix(".txt") + "_filled.txt"
        val outBytes = res.text.encodeToByteArray()
        val outMsg = writeOutput(outTree, outName, outBytes)
        lastOutputFile = outMsg
        val msg = buildString {
            append("替换完成。img 总数 ").append(res.totalImgs)
                .append("，已替换 ").append(res.filled)
            if (res.unfilled > 0) append("，未替换 ").append(res.unfilled)
            if (res.remainingMd5 > 0) append("，富余 md5 ").append(res.remainingMd5)
            append("\n").append(outMsg)
        }
        finishTask(msg)
    }

    /** 输出落盘：优先用户选的目录（File API，需全部文件访问），失败退回下载目录。 */
    private suspend fun writeOutput(outTree: android.net.Uri?, outName: String, bytes: ByteArray): String {
        return withContext(Dispatchers.IO) {
            // 1) 授权了全部文件访问 且 能映射到真实路径 → 直接写你选的目录
            if (outTree != null && DocUtils.isExternalStorageManager()) {
                val dir = DocUtils.treeToRealPath(outTree)
                if (dir != null && dir.isDirectory) {
                    try {
                        val f = File(dir, outName)
                        f.writeBytes(bytes)
                        return@withContext "已写出到你选的目录：$outName"
                    } catch (_: Exception) { /* 落到下面 */ }
                }
            }
            // 2) SAF 直写（部分 ROM 可用）
            if (outTree != null) {
                try {
                    val treeDocId = DocumentsContract.getTreeDocumentId(outTree)
                    val targetUri = DocumentsContract.buildDocumentUri(
                        outTree.authority, "$treeDocId/$outName"
                    )
                    var ok = false
                    val out = contentResolver.openOutputStream(targetUri, "wt")
                    if (out != null) {
                        out.use { it.write(bytes) }
                        ok = true
                    }
                    if (ok) return@withContext "已写出到你选的目录：$outName"
                } catch (_: Exception) { /* 落到下载目录 */ }
            }
            // 3) 兜底：公共下载目录
            try {
                val dl = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS
                )
                if (!dl.exists()) dl.mkdirs()
                File(dl, outName).writeBytes(bytes)
                return@withContext "输出目录写不了，已改写到 下载/：$outName"
            } catch (e: Exception) {
                return@withContext "输出失败：${e.message}"
            }
        }
    }

    // ---------- 排序比较器（对应 Spinner 6 项） ----------
    private fun sortComparator(idx: Int): Comparator<RenameTask.ImgDoc> = when (idx) {
        0 -> compareBy<RenameTask.ImgDoc> { it.name.lowercase() }
        1 -> compareByDescending<RenameTask.ImgDoc> { it.name.lowercase() }
        2 -> compareBy<RenameTask.ImgDoc> { it.mtime }
        3 -> compareByDescending<RenameTask.ImgDoc> { it.mtime }
        4 -> compareBy<RenameTask.ImgDoc> { it.size }
        5 -> compareByDescending<RenameTask.ImgDoc> { it.size }
        else -> compareBy<RenameTask.ImgDoc> { it.name.lowercase() }
    }

    // ---------- 通知 ----------
    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CH_ID, "后台任务", NotificationManager.IMPORTANCE_LOW)
            notMgr?.createNotificationChannel(ch)
        }
    }

    private fun startFg() {
        val n = buildNote("正在准备…")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOT_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOT_ID, n)
        }
    }

    private fun updateNote(text: String) {
        notMgr?.notify(NOT_ID, buildNote(text, ongoing = true))
    }

    private fun buildNote(text: String, ongoing: Boolean = true): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("小说 MD5 工具")
            .setContentText(text)
            .setOngoing(ongoing)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentIntent(pi)
            .build()
    }

    private fun finishTask(msg: String) {
        notMgr?.notify(NOT_ID, buildNote(msg, ongoing = false))
        stopForeground(true)
        stopSelf()
    }

    companion object {
        const val ACTION_RENAME = "rename"
        const val ACTION_NOVEL = "novel"
        const val ACTION_STOP = "stop"
        const val EXTRA_TREE = "tree"
        const val EXTRA_NOVEL = "novel"
        const val EXTRA_MD5TREE = "md5tree"
        const val EXTRA_OUTTREE = "outtree"
        const val EXTRA_SORT = "sort"
        private const val CH_ID = "task"
        private const val NOT_ID = 7
    }
}
