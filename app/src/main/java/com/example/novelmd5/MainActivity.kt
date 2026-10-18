package com.example.novelmd5

import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val scope = lifecycleScope
    private val io = Dispatchers.IO

    // 三类目录/文件（SAF DocumentUri，不依赖拼真实路径）
    private var imgTree: Uri? = null       // 功能1：图片文件夹（SAF tree）
    private var novelUri: Uri? = null      // 功能2：小说 txt（SAF document）
    private var md5Tree: Uri? = null       // 功能2：提供 md5 的图片文件夹（SAF tree）
    private var outTree: Uri? = null       // 功能2：输出目录（SAF tree，默认=小说所在）

    // SAF 选择器
    private var pickTarget = "imgDir"
    private val pickDir = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 持久化访问权限（SAF 必须，否则下次打开进程读不到）
            contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            when (pickTarget) {
                "imgDir" -> {
                    imgTree = uri
                    val n = listImageCount(uri)
                    findViewById<TextView>(R.id.tvImgDir).text =
                        "已选：${displayName(uri)}（$n 张图）"
                }
                "md5Dir" -> {
                    md5Tree = uri
                    val n = listImageCount(uri)
                    findViewById<TextView>(R.id.tvMd5Count).text =
                        "已选（$n 张，按文件名排序提供 MD5）"
                    checkNovelReady()
                }
                "outDir" -> {
                    outTree = uri
                    findViewById<TextView>(R.id.tvOutDir).text = "已选：${displayName(uri)}"
                }
            }
        }
    }

    // 选择 txt 文件
    private val pickText = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            novelUri = uri
            findViewById<TextView>(R.id.tvNovelFile).text = displayName(uri)
            checkNovelReady()
        }
    }

    companion object {
        val IMG_EXTS =
            listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".heic", ".tiff", ".ipg")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        findViewById<Button>(R.id.btnPickImgDir).setOnClickListener {
            pickTarget = "imgDir"; pickDir.launch(null)
        }
        findViewById<Button>(R.id.btnPickNovel).setOnClickListener {
            pickText.launch(arrayOf("text/*"))
        }
        findViewById<Button>(R.id.btnPickMd5Dir).setOnClickListener {
            pickTarget = "md5Dir"; pickDir.launch(null)
        }
        findViewById<Button>(R.id.btnPickOutDir).setOnClickListener {
            pickTarget = "outDir"; pickDir.launch(null)
        }
        findViewById<Button>(R.id.btnDoRename).setOnClickListener { startRename() }
        findViewById<Button>(R.id.btnDoNovel).setOnClickListener { startNovel() }

        checkNovelReady()
    }

    private fun checkNovelReady() {
        findViewById<Button>(R.id.btnDoNovel).isEnabled = novelUri != null && md5Tree != null
    }

    // ---------- 功能1 ----------
    private fun startRename() {
        val tree = imgTree ?: run {
            Toast.makeText(this, "请先选择图片文件夹", Toast.LENGTH_SHORT).show(); return
        }
        val progress = findViewById<TextView>(R.id.tvRenameResult)
        progress.text = "正在计算 MD5 并改名…"

        scope.launch {
            // 通过 SAF 列举所有图片子文档（递归，含子文件夹）
            val all = listImageDocs(tree)
            val images = all.filter {
                IMG_EXTS.any { ext -> it.name.lowercase().endsWith(ext) }
            }.sortedBy { it.name.lowercase() }

            if (images.isEmpty()) {
                progress.text = "所选文件夹里没有图片（共 ${all.size} 个文件，均非图片扩展名）"
                return@launch
            }

            val res = RenameTask.run(images, contentResolver) { done, total ->
                progress.text = "进度 $done / $total"
            }
            progress.text = buildString {
                append("完成：成功 ").append(res.success).append(" 张")
                if (res.failed > 0) {
                    append("，失败 ").append(res.failed).append(" 张")
                    res.failures.take(5).forEach { append("\n  - ").append(it) }
                }
                append("\n输出目录：").append(displayName(tree))
            }
            Toast.makeText(this@MainActivity, "重命名完成：成功 ${res.success} 张", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 功能2 ----------
    private fun startNovel() {
        val novel = novelUri ?: run {
            Toast.makeText(this, "请先选择小说 txt", Toast.LENGTH_SHORT).show(); return
        }
        val tree = md5Tree ?: run {
            Toast.makeText(this, "请先选择图片文件夹", Toast.LENGTH_SHORT).show(); return
        }

        val resultView = findViewById<TextView>(R.id.tvNovelResult)
        resultView.text = "正在扫描图片…"
        scope.launch {
            try {
                // 1. 递归列图（IO，避免主线程卡死）
                val all = withContext(io) { listImageDocs(tree) }
                val images = all.filter {
                    IMG_EXTS.any { ext -> it.name.lowercase().endsWith(ext) }
                }.sortedBy { it.name.lowercase() }

                if (images.isEmpty()) {
                    resultView.text = "所选文件夹里没有图片"
                    return@launch
                }

                resultView.text = "正在读取 ${images.size} 张图的 MD5…"
                // 2. 读图片算 MD5（IO）
                val md5s = withContext(io) {
                    images.map { doc ->
                        contentResolver.openInputStream(doc.uri)?.use { Md5.ofStream(it) } ?: ""
                    }.filter { it.isNotEmpty() }
                }

                // 3. 读小说（IO）
                val novelText = withContext(io) {
                    contentResolver.openInputStream(novel)?.use { it.readBytes() }?.decodeToString() ?: ""
                }
                if (novelText.isEmpty()) {
                    resultView.text = "读不到小说 txt 内容"
                    return@launch
                }

                // 4. 纯逻辑替换
                val res = NovelTask.process(novelText, md5s)

                // 5. 输出（IO + 捕获异常，失败不崩，只在界面提示）
                // 优先写你选的输出目录；写不了（iQOO 等 ROM 拒绝 SAF 建文件/写流）就落到
                // /sdcard/Download/（公共下载目录，任何 ROM 都允许，不依赖 SAF 写授权）。
                var outMsg = "（未写出文件）"
                val baseName = try { displayName(novel) } catch (_: Exception) { "novel" }
                val outName = baseName.removeSuffix(".txt") + "_filled.txt"
                val outBytes = res.text.encodeToByteArray()

                // 5a. 先试写你选的输出目录
                var wrote = false
                if (outTree != null) {
                    wrote = withContext(io) {
                        try {
                            val outDir = outTree!!
                            val treeDocId = DocumentsContract.getTreeDocumentId(outDir)
                            val targetUri = DocumentsContract.buildDocumentUri(outDir.authority, "$treeDocId/$outName")
                            contentResolver.openOutputStream(targetUri, "wt")?.use { out ->
                                out.write(outBytes)
                            } != null
                        } catch (e: Exception) {
                            false
                        }
                    }
                }
                if (wrote) {
                    outMsg = "已写出到 你选的输出目录：$outName"
                } else {
                    // 5b. 兜底：写公共下载目录
                    outMsg = withContext(io) {
                        try {
                            val dlDir = android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS
                            )
                            val f = java.io.File(dlDir, outName)
                            if (!dlDir.exists()) dlDir.mkdirs()
                            f.writeBytes(outBytes)
                            "输出目录写不了，已改写到 下载/：$outName"
                        } catch (e: Exception) {
                            "输出失败：${e.message}"
                        }
                    }
                }

                // 6. 显示结果（主线程）
                resultView.text = buildString {
                    append("完成。\n")
                    append("小说里 img 标签总数：").append(res.totalImgs).append('\n')
                    append("已替换：").append(res.filled).append('\n')
                    if (res.unfilled > 0) append("未替换（md5 不够）：").append(res.unfilled).append('\n')
                    if (res.remainingMd5 > 0) append("未用完 md5：").append(res.remainingMd5).append('\n')
                    append("输出：").append(outMsg)
                }
                Toast.makeText(this@MainActivity, "替换完成，成功 ${res.filled} 个", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                resultView.text = "出错：${e.message}"
            }
        }
    }

    // ---------- SAF 工具 ----------

    /** 递归列出某 tree 下所有子文档（含子文件夹里的图片），每个文档记录它所在的目录。 */
    private fun listImageDocs(tree: Uri): List<RenameTask.ImgDoc> {
        val results = mutableListOf<RenameTask.ImgDoc>()
        // 用栈做 DFS，避免深层嵌套递归爆栈。
        // 栈里存的是"以某文档为根的 tree"，用于继续递归它的子目录。
        val stack = ArrayDeque<Uri>()
        stack.addLast(tree)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val curDocId = DocumentsContract.getTreeDocumentId(cur)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(cur, curDocId)
            contentResolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                       DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                       DocumentsContract.Document.COLUMN_MIME_TYPE),
                null, null, null
            )?.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx)
                    val childDocId = c.getString(idIdx)
                    val mime = c.getString(mimeIdx)
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(cur, childDocId)
                    val mimeLower = mime?.lowercase() ?: ""
                    // 文件夹 → 构造"以该子文件夹为根"的 tree，入栈继续递归。
                    // 注意：externalstorage provider 返回的 childDocId 是**绝对** docId
                    // （形如 "primary:书籍/阅读导出用/子目录"），直接作为新 tree 的 documentId，
                    // 不要再去拼 "$curDocId/$childDocId"（拼了会报 Invalid URI）。
                    if (mimeLower == DocumentsContract.Document.MIME_TYPE_DIR) {
                        val subTree = DocumentsContract.buildTreeDocumentUri(cur.authority, childDocId)
                        stack.addLast(subTree)
                    }
                    // 文件 → 记录；parentTree=cur（它实际所在目录的 tree），parentDocId=curDocId
                    results.add(RenameTask.ImgDoc(childUri, name, cur, curDocId))
                }
            }
        }
        return results
    }

    /** 统计某 tree 下图片数量（用于选完立即显示）。 */
    private fun listImageCount(tree: Uri): Int =
        listImageDocs(tree).count {
            IMG_EXTS.any { ext -> it.name.lowercase().endsWith(ext) }
        }

    /** 取某个 document 所在目录（parentDir）。SAF 下通过 buildChildDocumentsUri 拿不到 parent，
     *  这里用 novel 的 docId 推断：取它所在 tree。
     *  简化：用 DocumentsContract 的 parentDocumentUri。 */
    private fun parentDirOf(novel: Uri): Uri? {
        return try {
            // novel 是 content://document/...，它的 parent 可用 getTreeDocumentId 推断
            // 但 document（非 tree）没有直接 API。退化为 null（写到 outTree 或提示用户选）。
            null
        } catch (_: Exception) { null }
    }

    private fun novelName(novel: Uri): String {
        return displayName(novel)
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.toString()
            } ?: uri.toString()
        } catch (_: Exception) { uri.toString() }
    }
}
