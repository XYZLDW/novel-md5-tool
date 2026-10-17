package com.example.novelmd5

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private val scope = lifecycleScope
    private val io = Dispatchers.IO

    // 三类目录/文件
    private var imgDir: File? = null       // 功能1：图片文件夹
    private var novelFile: File? = null    // 功能2：小说 txt
    private var md5Dir: File? = null       // 功能2：提供 md5 的图片文件夹
    private var outDir: File? = null       // 功能2：输出目录（默认=小说所在目录）

    // SAF 选择器（按 target 分发）
    private var pickTarget = "imgDir"
    private val pickDir = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            when (pickTarget) {
                "imgDir" -> {
                    val name = displayName(uri)
                    imgDir = File(UriToPath.toRealPath(this, uri) ?: "")
                    findViewById<TextView>(R.id.tvImgDir).text = name
                }
                "md5Dir" -> {
                    val name = displayName(uri)
                    md5Dir = File(UriToPath.toRealPath(this, uri) ?: "")
                    val n = md5Dir?.let { d ->
                        d.listFiles { f ->
                            f.isFile && f.name.lowercase().endsWith(IMG_EXTS)
                        }?.size
                    } ?: 0
                    findViewById<TextView>(R.id.tvMd5Count).text = "已选文件夹（$n 张图，按文件名排序提供 MD5）"
                    checkNovelReady()
                }
                "outDir" -> {
                    val name = displayName(uri)
                    outDir = File(UriToPath.toRealPath(this, uri) ?: "")
                    findViewById<TextView>(R.id.tvOutDir).text = name
                }
            }
        }
    }

    // 选择 txt 文件（内容读取，用 SAF 的 document）
    private val pickText = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val name = displayName(uri)
            val tmp = File(filesDir, name)
            contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            }
            novelFile = tmp
            findViewById<TextView>(R.id.tvNovelFile).text = name
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
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "text/*"
            }
            pickText.launch(intent)
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
        findViewById<Button>(R.id.btnDoNovel).isEnabled = novelFile != null && md5Dir != null
    }

    // ---------- 功能1 ----------
    private fun startRename() {
        val dir = imgDir ?: run {
            Toast.makeText(this, "请先选择图片文件夹", Toast.LENGTH_SHORT).show(); return
        }
        val progress = findViewById<TextView>(R.id.tvRenameResult)
        progress.text = "正在计算 MD5 并改名…"
        scope.launch {
            val res = RenameTask.run(dir) { done, total ->
                progress.text = "进度 $done / $total"
            }
            progress.text = buildString {
                append("完成：成功 ").append(res.success).append(" 张")
                if (res.failed > 0) {
                    append("，失败 ").append(res.failed).append(" 张")
                    res.failures.take(5).forEach { append("\n  - ").append(it) }
                }
                append("\n输出目录：").append(dir.absolutePath)
            }
            Toast.makeText(this@MainActivity, "重命名完成：成功 ${res.success} 张", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 功能2 ----------
    private fun startNovel() {
        val novel = novelFile ?: run {
            Toast.makeText(this, "请先选择小说 txt", Toast.LENGTH_SHORT).show(); return
        }
        val md5Dir = md5Dir ?: run {
            Toast.makeText(this, "请先选择图片文件夹", Toast.LENGTH_SHORT).show(); return
        }
        val out = outDir ?: File(novel.parentFile, "")
        out.mkdirs()

        val images = md5Dir.listFiles { f ->
            f.isFile && f.name.lowercase().endsWith(IMG_EXTS)
        }?.sortedBy { it.name.lowercase() } ?: emptyList()

        if (images.isEmpty()) {
            findViewById<TextView>(R.id.tvNovelResult).text = "所选文件夹里没有图片"
            return
        }

        findViewById<TextView>(R.id.tvNovelResult).text = "正在读取 ${images.size} 张图的 MD5…"
        scope.launch {
            val md5s = withContext(io) { images.map { Md5.ofFile(it.absolutePath) } }
            val res = NovelTask.process(novel, md5s, File(out, novel.name))
            val outFile = File(out, novel.name.removeSuffix(".txt") + "_filled.txt")
            findViewById<TextView>(R.id.tvNovelResult).text = buildString {
                append("完成。\n")
                append("小说里 img 标签总数：").append(res.totalImgs).append('\n')
                append("已替换：").append(res.filled).append('\n')
                if (res.unfilled > 0) append("未替换（md5 不够）：").append(res.unfilled).append('\n')
                if (res.remainingMd5 > 0) append("未用完 md5：").append(res.remainingMd5).append('\n')
                append("输出文件：").append(outFile.absolutePath)
            }
            Toast.makeText(this@MainActivity, "替换完成，成功 ${res.filled} 个", Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.toString()
            } ?: uri.toString()
        } catch (_: Exception) { uri.toString() }
    }
}
