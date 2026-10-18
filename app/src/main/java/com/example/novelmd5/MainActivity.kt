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
        val IMG_EXTS = listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".heic", ".tiff")
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
            // 通过 SAF 列举所有图片子文档
            val all = listImageDocs(tree)
            val images = all.filter {
                IMG_EXTS.any { ext -> it.name.lowercase().endsWith(ext) }
            }.sortedBy { it.name.lowercase() }

            if (images.isEmpty()) {
                progress.text = "所选文件夹里没有图片（共 ${all.size} 个文件，均非图片扩展名）"
                return@launch
            }

            val res = RenameTask.run(tree, images, contentResolver) { done, total ->
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

        val all = listImageDocs(tree)
        val images = all.filter {
            IMG_EXTS.any { ext -> it.name.lowercase().endsWith(ext) }
        }.sortedBy { it.name.lowercase() }

        if (images.isEmpty()) {
            findViewById<TextView>(R.id.tvNovelResult).text = "所选文件夹里没有图片"
            return
        }

        findViewById<TextView>(R.id.tvNovelResult).text = "正在读取 ${images.size} 张图的 MD5…"
        scope.launch {
            // 通过 SAF 读图片字节算 MD5（不依赖真实路径）
            val md5s = withContext(io) {
                images.map { doc ->
                    contentResolver.openInputStream(doc.uri)?.use { input ->
                        Md5.ofStream(input)
                    } ?: ""
                }
            }.filter { it.isNotEmpty() }

            // 读小说 txt 内容
            val novelText = withContext(io) {
                contentResolver.openInputStream(novel)?.use { it.readBytes() }?.decodeToString() ?: ""
            }
            if (novelText.isEmpty()) {
                findViewById<TextView>(R.id.tvNovelResult).text = "读不到小说 txt 内容"
                return@launch
            }

            // 纯逻辑替换
            val res = NovelTask.process(novelText, md5s)

            // 输出：写到 outTree（未选则不写出，只返回替换文本）
            var outFile: Uri? = null
            if (outTree != null) {
                val outDir = outTree!!
                val parentDocId = DocumentsContract.getTreeDocumentId(outDir)
                val parentUri = DocumentsContract.buildChildDocumentsUriUsingTree(outDir, parentDocId)
                val outName = displayName(novel).removeSuffix(".txt") + "_filled.txt"
                // createDocument(resolver, parentTreeUri, parentDocId, displayName)
                outFile = DocumentsContract.createDocument(
                    contentResolver, outDir, parentDocId, outName
                )
                if (outFile != null) {
                    contentResolver.openOutputStream(outFile)?.use { out ->
                        out.write(res.text.encodeToByteArray())
                    }
                }
            }

            findViewById<TextView>(R.id.tvNovelResult).text = buildString {
                append("完成。\n")
                append("小说里 img 标签总数：").append(res.totalImgs).append('\n')
                append("已替换：").append(res.filled).append('\n')
                if (res.unfilled > 0) append("未替换（md5 不够）：").append(res.unfilled).append('\n')
                if (res.remainingMd5 > 0) append("未用完 md5：").append(res.remainingMd5).append('\n')
                append("输出文件：").append(
                    if (outFile != null) displayName(outFile!!) else "(未选输出目录，未写出文件)"
                )
            }
            Toast.makeText(this@MainActivity, "替换完成，成功 ${res.filled} 个", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- SAF 工具 ----------

    /** 列出某 tree 下所有子文档（不过滤）。 */
    private fun listImageDocs(tree: Uri): List<RenameTask.ImgDoc> {
        val docId = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
        val docs = mutableListOf<RenameTask.ImgDoc>()
        contentResolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                   DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null
        )?.use { c ->
            val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            while (c.moveToNext()) {
                val name = c.getString(nameIdx)
                val childDocId = c.getString(idIdx)
                val childUri = DocumentsContract.buildDocumentUriUsingTree(tree, childDocId)
                docs.add(RenameTask.ImgDoc(childUri, name))
            }
        }
        return docs
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
