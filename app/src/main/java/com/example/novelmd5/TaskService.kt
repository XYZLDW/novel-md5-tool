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
    /** 通知权限是否已授予（Android 13+）；未授予时不强行发通知，避免反复失败。 */
    @Volatile var canNotify: Boolean = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        ensureChannel()
        TaskServiceHolder.set(this)
        // 把服务上的结果同步进静态 holder（进程级，服务销毁后仍保留）
        syncStaticResults()
    }

    override fun onDestroy() {
        super.onDestroy()
        // 保留静态 holder 里的结果（供 Activity 轮询读），只清单例引用
        syncStaticResults()
        TaskServiceHolder.set(null)
        scope.cancel()
    }

    /** 把服务实例上的结果字段同步到进程级静态 holder。 */
    private fun syncStaticResults() {
        TaskServiceHolder.staticRenameResult = renameResult
        TaskServiceHolder.staticNovelResult = novelResult
        TaskServiceHolder.staticRunning = running
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 先判断能否发通知（Android 13+ 需 POST_NOTIFICATIONS 已授予），避免第一遍未授权时反复失败
        canNotify = hasNotificationPermission()
        // 同步 startForeground（占位），5 秒窗口内完成；3 参（带 type）被 ROM 拒时回落 2 参
        try {
            startForeground(
                NOT_ID,
                buildNote(if (canNotify) "准备中…" else "任务已启动（通知权限未授予）", ongoing = true),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            fgOk = true
        } catch (e: Exception) {
            try {
                startForeground(NOT_ID, buildNote("任务已启动", ongoing = true))
                fgOk = true
            } catch (_: Exception) {
                // 彻底不行：任务仍跑（后台），仅靠界面回显
            }
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
            // 结果先落静态 holder（服务销毁后仍可被界面读到）
            TaskServiceHolder.staticRenameResult = renameResult
            TaskServiceHolder.staticRunning = false
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
                append("\n诊断：前台服务=")
                append(if (fgOk) "已启用（可后台）" else "被ROM拒绝（仅应用内）")
                append("，通知权限=").append(if (canNotify) "已授权（已弹通知栏）" else "未授权（无通知栏）")
                if (!canNotify) append(" → 请在系统设置里给本应用开通知权限，下次即可弹通知")
            }
        } catch (e: Exception) {
            novelResult = "替换出错：${e.message}"
        } finally {
            running = false
            updateNote(novelResult?.lines()?.firstOrNull() ?: "完成", ongoing = false)
            // 结果先落静态 holder（服务销毁后仍可被界面读到）
            TaskServiceHolder.staticNovelResult = novelResult
            TaskServiceHolder.staticRunning = false
            stopSelf()
        }
    }

    /**
     * 输出落盘（按"最贴合用户指定目录"优先）：
     *  0) Shizuku 提权可用 + 选了输出目录 → 直写真实路径（彻底突破 iQOO provider 限制）
     *  1) 授权「全部文件访问」且选了输出目录 → 按真实路径直写
     *  2) 在【你选的输出目录】里用 DocumentsContract API 落文件（功能1改名已证明该通道可用）
     *  3) 在【图片文件夹】里同样方式落文件
     *  4) 兜底：公共 下载/（MediaStore，任何 ROM 都允许）
     */
    private suspend fun writeOutput(
        md5Tree: Uri,
        outTree: Uri?,
        outName: String,
        bytes: ByteArray
    ): String = withContext(Dispatchers.IO) {
        // 0) （v19 起 Shizuku 路径移除；如日后需要可在此插入 Shizuku 提权直写真实路径）
        // 1) 全部文件访问 + 真实路径（仅部分 ROM 可授予）
        if (DocUtils.isExternalStorageManager() && outTree != null) {
            try {
                val realDir = DocUtils.treeToRealPath(outTree)
                if (realDir != null) {
                    val f = File(realDir, outName)
                    if (!f.parentFile!!.exists()) f.parentFile.mkdirs()
                    f.writeBytes(bytes)
                    return@withContext "已按「全部文件访问」写出到真实路径：${f.absolutePath}"
                }
            } catch (_: Exception) { /* 落到 SAF 多写法 */ }
        }
        // 2) 你选的输出目录（SAF 通道，功能1改名证明可用）
        if (outTree != null && outTree.toString() != md5Tree.toString()) {
            val r = writeIntoTree(outTree, outName, bytes)
            if (r != null) return@withContext "已写出到你选的输出目录：$outName（$r）"
        }
        // 3) 图片文件夹
        val r3 = writeIntoTree(md5Tree, outName, bytes)
        if (r3 != null) return@withContext "输出目录写不了，已改写到 图片文件夹：$outName（$r3）"
        // 4) 兜底：公共下载目录
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

    /**
     * 在某 tree 内把结果 txt 写进去。
     * 先处理"已存在同名文件"（覆盖，避免重复生成）；再依次试 4 种新建方式：
     *  a) createDocument 建空文档再写内容
     *  b) openOutputStream(newDocUri,"wt") 直接新建
     *  c) createDocument 临时名 → renameDocument 成目标名 → 写内容
     *  d) createDocument 临时名 → 写内容 → renameDocument 成目标名
     *  成功返回该方式名；全失败返回 null（由调用方继续兜底）。
     */
    private fun writeIntoTree(tree: Uri, outName: String, bytes: ByteArray): String? {
        val treeDocId = android.provider.DocumentsContract.getTreeDocumentId(tree)
        // 0) 清理上一轮遗留的临时文件（_dsh_tmp_*.tmp），避免"目录里多出一堆"
        cleanupTempDocs(tree, treeDocId)
        // 0.5) 若已存在同名文件 → 直接覆盖，不再新建（解决"点两遍生成两个文件"）
        val existingUri = findDocByName(tree, treeDocId, outName)
        if (existingUri != null) {
            try {
                contentResolver.openOutputStream(existingUri, "w")?.use { it.write(bytes) }
                    ?: throw java.io.FileNotFoundException("覆盖既有文件失败")
                return "overwrite-existing"
            } catch (_: Exception) { /* 覆盖失败就继续走新建路径 */ }
        }
        // a) 最直接的：createDocument + 写流
        try {
            val newUri = android.provider.DocumentsContract.createDocument(
                contentResolver, tree, "text/plain", outName)
            if (newUri != null) {
                contentResolver.openOutputStream(newUri)?.use { it.write(bytes) }
                    ?: throw java.io.FileNotFoundException("createDocument 流为 null")
                return "createDocument"
            }
        } catch (_: Exception) { /* 试 b */ }
        // b) 新 doc URI + "wt"
        try {
            val targetUri = android.provider.DocumentsContract.buildDocumentUri(
                tree.authority, "$treeDocId/$outName"
            )
            val out = contentResolver.openOutputStream(targetUri, "wt")
            if (out != null) {
                out.use { it.write(bytes) }
                return "openOutputStream-wt"
            }
        } catch (_: Exception) { /* 试 c */ }
        // c) 先建临时文档改名（利用 renameDocument 已验证可用）
        try {
            val tmpName = "_dsh_tmp_${System.currentTimeMillis()}.tmp"
            val tmpUri = android.provider.DocumentsContract.createDocument(
                contentResolver, tree, "application/octet-stream", tmpName)
            if (tmpUri != null) {
                val finalUri = android.provider.DocumentsContract.renameDocument(
                    contentResolver, tmpUri, outName
                ) ?: throw java.io.FileNotFoundException("renameDocument 返回 null")
                contentResolver.openOutputStream(finalUri)?.use { it.write(bytes) }
                    ?: throw java.io.FileNotFoundException("改名后流为 null")
                return "create+rename"
            }
        } catch (_: Exception) { /* 试 d */ }
        // d) 建临时→写内容→改名
        try {
            val tmpName = "_dsh_tmp_${System.currentTimeMillis()}.tmp"
            val tmpUri = android.provider.DocumentsContract.createDocument(
                contentResolver, tree, "application/octet-stream", tmpName)
            if (tmpUri != null) {
                contentResolver.openOutputStream(tmpUri)?.use { it.write(bytes) }
                    ?: throw java.io.FileNotFoundException("写临时文件失败")
                android.provider.DocumentsContract.renameDocument(contentResolver, tmpUri, outName)
                    ?: throw java.io.FileNotFoundException("renameDocument 返回 null")
                return "write+rename"
            }
        } catch (_: Exception) { /* 全失败 */ }
        // 收尾：把本轮失败可能留下的临时文件清掉
        cleanupTempDocs(tree, treeDocId)
        return null
    }

    /** 找 tree 直接子文档里 DISPLAY_NAME == name 的文档，返回其 docId 对应 Uri；没有返回 null。 */
    private fun findDocByName(tree: Uri, treeDocId: String, name: String): Uri? {
        try {
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, treeDocId)
            val proj = arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            contentResolver.query(children, proj, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndexOrThrow(
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndexOrThrow(
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    if (c.getString(nameIdx) == name) {
                        val id = c.getString(idIdx)
                        val mime = c.getString(mimeIdx)
                        // 只要不是目录就命中（txt 是 text/plain 或 application/octet-stream）
                        if (mime != android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                            return android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, id)
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    /** 删掉 tree 里所有 _dsh_tmp_*.tmp 临时文档。 */
    private fun cleanupTempDocs(tree: Uri, treeDocId: String) {
        try {
            val children = android.provider.DocumentsContract.buildChildDocumentsUriUsingTree(
                tree, treeDocId)
            val proj = arrayOf(
                android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE
            )
            val tmpUris = mutableListOf<Uri>()
            contentResolver.query(children, proj, null, null, null)?.use { c ->
                val idIdx = c.getColumnIndexOrThrow(
                    android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = c.getColumnIndexOrThrow(
                    android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = c.getColumnIndexOrThrow(
                    android.provider.DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val dname = c.getString(nameIdx) ?: ""
                    if (dname.startsWith("_dsh_tmp_") &&
                        c.getString(mimeIdx) != android.provider.DocumentsContract.Document.MIME_TYPE_DIR) {
                        tmpUris.add(android.provider.DocumentsContract.buildDocumentUriUsingTree(
                            tree, c.getString(idIdx)))
                    }
                }
            }
            tmpUris.forEach { u ->
                try { android.provider.DocumentsContract.deleteDocument(contentResolver, u) }
                catch (_: Exception) { }
            }
        } catch (_: Exception) { }
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
        // 无通知权限时不反复 notify（会静默失败），避免浪费
        if (!canNotify) return
        try { nm.notify(NOT_ID, buildNote(text, ongoing)) } catch (_: Exception) { }
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
        fun instance(): TaskService? = TaskServiceHolder.get()

        /** 读进程级静态结果（服务已 stopSelf 后仍可拿到）。 */
        fun staticRenameResult(): String? = TaskServiceHolder.staticRenameResult
        fun staticNovelResult(): String? = TaskServiceHolder.staticNovelResult
        fun staticRunning(): Boolean = TaskServiceHolder.staticRunning
    }
}

/** 进程内持有当前 TaskService 实例（供 Activity 读结果）；结果字段为进程级静态，服务销毁后仍保留。 */
internal object TaskServiceHolder {
    @Volatile private var ref: TaskService? = null
    @Volatile var staticRenameResult: String? = null
    @Volatile var staticNovelResult: String? = null
    @Volatile var staticRunning: Boolean = false
    fun get(): TaskService? = ref
    fun set(s: TaskService?) { ref = s }
}
