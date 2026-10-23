package com.example.novelmd5

import android.content.ContentResolver
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 进程级任务运行器（object 单例）：
 *  - 任务跑在进程级 scope（SupervisorJob），Activity 销毁 / 锁屏 / 切后台都不中断
 *  - 不依赖前台服务、通知权限（避开 iQOO ROM 对 FGS 的各种限制）
 *  - 结果存到单例字段，Activity 返回后读取刷新界面
 */
object TaskRunner {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile var renameResult: String? = null
    @Volatile var novelResult: String? = null
    @Volatile var running: Boolean = false

    // ---------- 功能1 ----------
    fun startRename(resolver: ContentResolver, tree: android.net.Uri) {
        renameResult = "已提交后台任务，正在处理…"
        running = true
        scope.launch {
            try {
                val all = withContext(Dispatchers.IO) { DocUtils.listImageDocs(resolver, tree) }
                val images = all.filter { DocUtils.isImage(it.name) }
                    .sortedBy { it.name.lowercase() }
                if (images.isEmpty()) {
                    renameResult = "所选文件夹没有图片（共 ${all.size} 个文件）"
                    return@launch
                }
                renameResult = "重命名中… 0 / ${images.size}"
                val res = RenameTask.run(images, resolver) { done, total ->
                    renameResult = "重命名中… $done / $total"
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
            }
        }
    }

    // ---------- 功能2 ----------
    fun startNovel(
        resolver: ContentResolver,
        novel: android.net.Uri,
        md5Tree: android.net.Uri,
        outTree: android.net.Uri?,
        sortIdx: Int
    ) {
        novelResult = "已提交后台任务，正在处理…"
        running = true
        scope.launch {
            try {
                val all = withContext(Dispatchers.IO) { DocUtils.listImageDocs(resolver, md5Tree) }
                val images = all.filter { DocUtils.isImage(it.name) }
                    .sortedWith(sortComparator(sortIdx))
                if (images.isEmpty()) {
                    novelResult = "所选文件夹没有图片"
                    return@launch
                }
                novelResult = "正在读取 ${images.size} 张图的 MD5…"
                val md5s = withContext(Dispatchers.IO) {
                    images.map { doc ->
                        resolver.openInputStream(doc.uri)?.use { Md5.ofStream(it) } ?: ""
                    }.filter { it.isNotEmpty() }
                }
                val novelText = withContext(Dispatchers.IO) {
                    resolver.openInputStream(novel)?.use { it.readBytes() }?.decodeToString() ?: ""
                }
                if (novelText.isEmpty()) {
                    novelResult = "读不到小说 txt 内容"
                    return@launch
                }
                val res = NovelTask.process(novelText, md5s)

                novelResult = "正在写出结果文件…"
                val baseName = DocUtils.displayName(resolver, novel)
                val outName = baseName.removeSuffix(".txt") + "_filled.txt"
                val outBytes = res.text.encodeToByteArray()
                val outMsg = writeOutput(resolver, md5Tree, outTree, outName, outBytes)

                novelResult = buildString {
                    append("替换完成。img 总数 ").append(res.totalImgs)
                        .append("，已替换 ").append(res.filled)
                    if (res.unfilled > 0) append("，未替换 ").append(res.unfilled)
                    if (res.remainingMd5 > 0) append("，富余 md5 ").append(res.remainingMd5)
                    append('\n').append(outMsg)
                }
            } catch (e: Exception) {
                novelResult = "替换出错：${e.message}"
            } finally {
                running = false
            }
        }
    }

    /**
     * 输出落盘，三级策略：
     *  1) 已授「全部文件访问」→ File API 直接写你选的目录
     *  2) SAF openOutputStream 直写你选的目录（部分 ROM 可用）
     *  3) 兜底：公共 下载/（API 29+ 走 MediaStore，旧版走 File）
     */
    private suspend fun writeOutput(
        resolver: ContentResolver,
        md5Tree: android.net.Uri,
        outTree: android.net.Uri?,
        outName: String,
        bytes: ByteArray
    ): String = withContext(Dispatchers.IO) {
        // 1) 全部文件访问 + 能映射真实路径 → 直接写你选的目录
        if (outTree != null && DocUtils.isExternalStorageManager()) {
            val dir = DocUtils.treeToRealPath(outTree)
            if (dir != null) {
                try {
                    val f = File(dir, outName)
                    if (!f.parentFile!!.exists()) f.parentFile.mkdirs()
                    f.writeBytes(bytes)
                    return@withContext "已按「全部文件访问」写出到真实路径：${f.absolutePath}"
                } catch (_: Exception) { /* 落到下面 */ }
            }
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
                val treeDocId = DocumentsContract.getTreeDocumentId(tree)
                val targetUri = DocumentsContract.buildDocumentUri(
                    tree.authority, "$treeDocId/$outName"
                )
                val out = resolver.openOutputStream(targetUri, "wt")
                if (out != null) {
                    out.use { it.write(bytes) }
                    return@withContext "已写出到 $label：$outName"
                }
            } catch (_: Exception) { /* 试下一个 */ }
        }
        // 3) 兜底：公共下载目录
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                // API 29+：MediaStore Downloads（RELATIVE_PATH=Download，任何 ROM 都允许）
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, outName)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, "Download")
                }
                val newUri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw java.io.FileNotFoundException("MediaStore insert 返回 null")
                resolver.openOutputStream(newUri)?.use { it.write(bytes) }
                    ?: throw java.io.FileNotFoundException("打不开 MediaStore 流")
                return@withContext "输出目录写不了，已改写到 下载/：$outName"
            } else {
                val dl = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!dl.exists()) dl.mkdirs()
                File(dl, outName).writeBytes(bytes)
                return@withContext "输出目录写不了，已改写到 下载/：$outName"
            }
        } catch (e: Exception) {
            return@withContext "输出失败：${e.message}"
        }
    }

    /** 对应界面 Spinner 的 6 个排序项。 */
    private fun sortComparator(idx: Int): Comparator<RenameTask.ImgDoc> = when (idx) {
        0 -> compareBy<RenameTask.ImgDoc> { it.mtime }           // 修改时间 旧→新（默认）
        1 -> compareByDescending<RenameTask.ImgDoc> { it.mtime } // 修改时间 新→旧
        2 -> compareBy<RenameTask.ImgDoc> { it.name.lowercase() }
        3 -> compareByDescending<RenameTask.ImgDoc> { it.name.lowercase() }
        4 -> compareBy<RenameTask.ImgDoc> { it.size }
        5 -> compareByDescending<RenameTask.ImgDoc> { it.size }
        else -> compareBy<RenameTask.ImgDoc> { it.mtime }
    }
}
