package com.example.novelmd5

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 前台服务（通知栏保护）：任务丢给它跑，锁屏/切后台/划掉任务都不被系统杀。
 *
 * 崩溃根因回顾（v8 崩）：onStartCommand 里只 startForeground 了"占位通知"，
 * 但真正的任务在 Activity 的 lifecycleScope 里跑，Activity 销毁 scope 被 cancel。
 * 本次修复：任务本身跑在**服务的进程级 scope**（SupervisorJob），
 * 服务不结束、scope 不被 cancel；startForeground 在 onStartCommand 同步完成。
 */
class TaskService : Service() {

    // 进程级 scope：服务活着就一直跑，Activity 销毁不影响
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var nm: NotificationManager

    // 结果回传给 Activity（进程内共享）
    @Volatile var renameResult: String? = null
    @Volatile var novelResult: String? = null
    @Volatile var running: Boolean = false
    /** 前台服务是否成功 startForeground（诊断用）。 */
    @Volatile var fgOk: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel()
        TaskServiceHolder.set(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        TaskServiceHolder.set(null)
        scope.cancel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先同步 startForeground（占位），保证 5 秒窗口内完成，不会被系统当"后台启动"杀。
        // iQOO 等 ROM 可能拒绝 sideload 应用的前台服务 → 包 try/catch，失败就降级为普通 startService 行为。
        try {
            startForeground(
                NOT_ID,
                buildNote("准备中…", ongoing = true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            fgOk = true
        } catch (e: Exception) {
            // 前台服务被 ROM 拒绝：仍尝试继续跑任务（普通后台），但不再保活。
            // 记到结果里让界面能看到。
            try {
                startForeground(NOT_ID, buildNote("前台被拒，后台执行中…", ongoing = true))
            } catch (_: Exception) { /* 彻底不行就算了，任务继续跑 */ }
        }
        when (intent?.action) {
            ACTION_RENAME -> {
                val tree = intent.getParcelableExtra<Uri>(EXTRA_TREE)
                if (tree != null) scope.launch { doRename(tree) } else stopWithMsg("缺少文件夹参数")
            }
            ACTION_NOVEL -> {
                val novel = intent.getParcelableExtra<Uri>(EXTRA_NOVEL)
                val md5Tree = intent.getParcelableExtra<Uri>(EXTRA_MD5TREE)
                val outTree = intent.getParcelableExtra<Uri>(EXTRA_OUTTREE)
                val sort = intent.getIntExtra(EXTRA_SORT, 0)
                if (novel != null && md5Tree != null) scope.launch { doNovel(novel, md5Tree, outTree, sort) }
                else stopWithMsg("缺少小说或图片参数")
            }
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    // ---------- 功能1 ----------
    private suspend fun doRename(tree: Uri) {
        running = true
        renameResult = "正在扫描图片…"
        try {
            val all = withContext(Dispatchers.IO) { DocUtils.listImageDocs(contentResolver, tree) }
            val images = all.filter { DocUtils.isImage(it.name) }.sortedBy { it.name.lowercase() }
            if (images.isEmpty()) {
                renameResult = "所选文件夹没有图片（共 ${all.size} 个文件）"
                return
            }
            val res = RenameTask.run(images, contentResolver) { done, total ->
                renameResult = "重命名中… $done / $total"
                updateNote("重命名中… $done / $total")
            }
            renameResult = buildString {
                append("重命名完成：成功 ").append(res.success).append(" 张")
                if (res.failed > 0) {
                    append("，失败 ").append(res.failed)
                    res.failures.take(3).forEach { append("：").append(it) }
                }
            }
        } catch (e: Exception) {
            renameResult = "重命名出错：${e.message}"
        } finally {
            running = false
            updateNote(renameResult ?: "完成", ongoing = false)
            stopSelf()
        }
    }

    // ---------- 功能2 ----------
    private suspend fun doNovel(
        novel: Uri,
        md5Tree: Uri,
        outTree: Uri?,
        sortIdx: Int
    ) {
        running = true
        novelResult = "正在扫描图片…"
        try {
            val all = withContext(Dispatchers.IO) { DocUtils.listImageDocs(contentResolver, md5Tree) }
            val images = all.filter { DocUtils.isImage(it.name) }
                .sortedWith(sortComparator(sortIdx))
            if (images.isEmpty()) {
                novelResult = "所选文件夹没有图片"
                return
            }
            novelResult = "正在读取 ${images.size} 张图的 MD5…"
            updateNote("读取 ${images.size} 张图的 MD5…")
            val md5s = withContext(Dispatchers.IO) {
                images.map { doc ->
                    contentResolver.openInputStream(doc.uri)?.use { Md5.ofStream(it) } ?: ""
                }.filter { it.isNotEmpty() }
            }
            val novelText = withContext(Dispatchers.IO) {
                contentResolver.openInputStream(novel)?.use { it.readBytes() }?.decodeToString() ?: ""
            }
            if (novelText.isEmpty()) {
                novelResult = "读不到小说 txt 内容"
                return
            }
            val res = NovelTask.process(novelText, md5s)

            novelResult = "正在写出结果文件…"
            updateNote("写出结果文件…")
            val baseName = DocUtils.displayName(contentResolver, novel)
            val outName = baseName.removeSuffix(".txt") + "_filled.txt"
            val outBytes = res.text.encodeToByteArray()
            val outMsg = writeOutput(md5Tree, outTree, outName, outBytes)

            novelResult = buildString {
                append("替换完成。img 总数 ").append(res.totalImgs)
                    .append("，已替换 ").append(res.filled)
                if (res.unfilled > 0) append("，未替换 ").append(res.unfilled)
                if (res.remainingMd5 > 0) append("，富余 md5 ").append(res.remainingMd5)
                append("\n").append(outMsg)
                append("\n诊断：前台服务=").append(if (fgOk) "已启用（可后台）" else "被ROM拒绝（仅应用内）")
                append("，通知=").append(if (hasNotificationPermission()) "已授权" else "未授权")
            }
        } catch (e: Exception) {
            novelResult = "替换出错：${e.message}"
        } finally {
            running = false
            updateNote(novelResult?.lines()?.firstOrNull() ?: "完成", ongoing = false)
            stopSelf()
        }
    }

    /**
     * 输出落盘：
     *  1) 授权「全部文件访问」时，按你选的【输出目录】真实路径直接写（不受 DocumentsProvider 限制）
     *  2) 没授权或输出目录没选 → 首选【图片文件夹本身】（SAF 可写）
     *  3) 都写不了 → 公共 下载/（MediaStore，任何 ROM 都允许）
     */
    private suspend fun writeOutput(
        md5Tree: Uri,
        outTree: Uri?,
        outName: String,
        bytes: ByteArray
    ): String = withContext(Dispatchers.IO) {
        // 1) 授权了「全部文件访问」且选了输出目录 → 按真实路径写（最贴合"落在我设定的地方"）
        if (DocUtils.isExternalStorageManager() && outTree != null) {
            try {
                val realDir = DocUtils.treeToRealPath(outTree)
                if (realDir != null) {
                    val f = File(realDir, outName)
                    if (!f.parentFile!!.exists()) f.parentFile.mkdirs()
                    f.writeBytes(bytes)
                    return@withContext "已按「全部文件访问」写出到真实路径：${f.absolutePath}"
                }
            } catch (_: Exception) { /* 落到 SAF 兜底 */ }
        }
        // 2) SAF：首选图片文件夹（可写性最高），其次你选的输出目录
        val candidates = buildList {
            add(md5Tree to "图片文件夹")
            if (outTree != null && outTree.toString() != md5Tree.toString()) {
                add(outTree to "你选的输出目录")
            }
        }
        for ((tree, label) in candidates) {
            try {
                val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(tree)
                val targetUri = android.provider.DocumentsContract.buildDocumentUri(
                    tree.authority, "$treeDocId/$outName"
                )
                val out = contentResolver.openOutputStream(targetUri, "wt")
                if (out != null) {
                    out.use { it.write(bytes) }
                    return@withContext "已写出到 $label：$outName"
                }
            } catch (_: Exception) { /* 试下一个 */ }
        }
        // 3) 兜底：公共下载目录（API 29+ 走 MediaStore，旧版走 File）
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, outName)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download")
                }
                val newUri = contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: throw java.io.FileNotFoundException("MediaStore insert 返回 null")
                contentResolver.openOutputStream(newUri)?.use { it.write(bytes) }
                    ?: throw java.io.FileNotFoundException("打不开 MediaStore 流")
                return@withContext "都写不了，已改写到 下载/：$outName"
            } else {
                val dl = android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS)
                if (!dl.exists()) dl.mkdirs()
                File(dl, outName).writeBytes(bytes)
                return@withContext "都写不了，已改写到 下载/：$outName"
            }
        } catch (e: Exception) {
            return@withContext "输出失败：${e.message}"
        }
    }

    private fun hasNotificationPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

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
            nm.createNotificationChannel(
                NotificationChannel(CH_ID, "后台任务", NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun updateNote(text: String, ongoing: Boolean = true) {
        nm.notify(NOT_ID, buildNote(text, ongoing))
    }

    private fun buildNote(text: String, ongoing: Boolean): Notification {
        val open = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            this, 0, open,
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

    private fun stopWithMsg(msg: String) {
        nm.notify(NOT_ID, buildNote(msg, ongoing = false))
        stopSelf()
    }

    companion object {
        const val ACTION_RENAME = "rename"
        const val ACTION_NOVEL = "novel"
        const val EXTRA_TREE = "tree"
        const val EXTRA_NOVEL = "novel"
        const val EXTRA_MD5TREE = "md5tree"
        const val EXTRA_OUTTREE = "outtree"
        const val EXTRA_SORT = "sort"
        private const val CH_ID = "task"
        private const val NOT_ID = 7

        /** Activity 用来读服务的结果（进程内单例）。 */
        fun instance(): TaskService? = (TaskServiceHolder.get())
    }
}

/** 进程内持有当前 TaskService 实例（供 Activity 读结果）。 */
internal object TaskServiceHolder {
    @Volatile private var ref: TaskService? = null
    fun get(): TaskService? = ref
    fun set(s: TaskService?) { ref = s }
}
