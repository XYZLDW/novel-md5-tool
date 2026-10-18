package com.example.novelmd5

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // 三类目录/文件（SAF DocumentUri，不依赖拼真实路径）
    private var imgTree: Uri? = null       // 功能1：图片文件夹（SAF tree）
    private var novelUri: Uri? = null      // 功能2：小说 txt（SAF document）
    private var md5Tree: Uri? = null       // 功能2：提供 md5 的图片文件夹（SAF tree）
    private var outTree: Uri? = null       // 功能2：输出目录（SAF tree）

    // SAF 选择器
    private var pickTarget = "imgDir"
    private val pickDir = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // 持久化访问权限（SAF 必须，否则下次打开进程读不到）
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            when (pickTarget) {
                "imgDir" -> {
                    imgTree = uri
                    val n = DocUtils.listImageCount(contentResolver, uri)
                    findViewById<TextView>(R.id.tvImgDir).text =
                        "已选：${DocUtils.displayName(contentResolver, uri)}（$n 张图）"
                }
                "md5Dir" -> {
                    md5Tree = uri
                    val n = DocUtils.listImageCount(contentResolver, uri)
                    findViewById<TextView>(R.id.tvMd5Count).text =
                        "已选（$n 张，按所选排序方式提供 MD5）"
                    checkNovelReady()
                }
                "outDir" -> {
                    outTree = uri
                    findViewById<TextView>(R.id.tvOutDir).text = "已选：${DocUtils.displayName(contentResolver, uri)}"
                }
            }
        }
    }

    // 选择 txt 文件
    private val pickText = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            novelUri = uri
            findViewById<TextView>(R.id.tvNovelFile).text = DocUtils.displayName(contentResolver, uri)
            checkNovelReady()
        }
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
        findViewById<Button>(R.id.btnGrantAllFiles).setOnClickListener {
            grantAllFiles()
        }

        checkNovelReady()
        refreshAllFilesStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshAllFilesStatus()
        // 回显任务结果（TaskRunner 是进程级单例，锁屏/切回来结果还在）
        TaskRunner.renameResult?.let {
            findViewById<TextView>(R.id.tvRenameResult).text = it
        }
        TaskRunner.novelResult?.let {
            findViewById<TextView>(R.id.tvNovelResult).text = it
        }
        if (TaskRunner.running) startResultPolling()
    }

    /** 「全部文件访问」授权状态。 */
    private fun refreshAllFilesStatus() {
        val tv = findViewById<TextView>(R.id.tvAllFilesStatus)
        val btn = findViewById<Button>(R.id.btnGrantAllFiles)
        if (DocUtils.isExternalStorageManager()) {
            tv.text = "已授权「全部文件访问」（功能2 结果直接写你选的目录）"
            btn.isEnabled = false
        } else {
            tv.text = "未授权「全部文件访问」（功能2 结果将改写到 下载/ 目录）"
            btn.isEnabled = true
        }
    }

    private fun grantAllFiles() {
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, "Android 11 以下无需此授权", Toast.LENGTH_SHORT).show()
            return
        }
        // 优先跳"本应用"的详情页（iQOO/OriginOS 总列表页常不显示 sideload 应用）
        try {
            val appIntent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            if (appIntent.resolveActivity(packageManager) != null) {
                startActivity(appIntent)
                return
            }
        } catch (_: Exception) { /* 落到总列表 */ }
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        } catch (e: Exception) {
            Toast.makeText(this, "无法打开授权页：${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun checkNovelReady() {
        findViewById<Button>(R.id.btnDoNovel).isEnabled = novelUri != null && md5Tree != null
    }

    // ---------- 功能1（进程级 TaskRunner，锁屏/切后台继续跑） ----------
    private fun startRename() {
        val tree = imgTree ?: run {
            Toast.makeText(this, "请先选择图片文件夹", Toast.LENGTH_SHORT).show(); return
        }
        findViewById<TextView>(R.id.tvRenameResult).text = "已提交后台任务，正在处理…"
        TaskRunner.startRename(contentResolver, tree)
        startResultPolling()
        Toast.makeText(this, "重命名已在后台开始，可锁屏/切走", Toast.LENGTH_SHORT).show()
    }

    // ---------- 功能2（进程级 TaskRunner） ----------
    private fun startNovel() {
        val novel = novelUri ?: run {
            Toast.makeText(this, "请先选择小说 txt", Toast.LENGTH_SHORT).show(); return
        }
        val tree = md5Tree ?: run {
            Toast.makeText(this, "请先选择图片文件夹", Toast.LENGTH_SHORT).show(); return
        }
        val sortIdx = findViewById<android.widget.Spinner>(R.id.spinnerMd5Order).selectedItemPosition
        findViewById<TextView>(R.id.tvNovelResult).text = "已提交后台任务，正在处理…"
        TaskRunner.startNovel(contentResolver, novel, tree, outTree, sortIdx)
        startResultPolling()
        Toast.makeText(this, "替换已在后台开始，可锁屏/切走", Toast.LENGTH_SHORT).show()
    }

    /** 轮询 TaskRunner 结果并回显到界面（任务在进程级跑，界面只是显示器）。 */
    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            val r = TaskRunner.renameResult
            val n = TaskRunner.novelResult
            if (r != null) findViewById<TextView>(R.id.tvRenameResult).text = r
            if (n != null) findViewById<TextView>(R.id.tvNovelResult).text = n
            if (TaskRunner.running) {
                pollHandler.postDelayed(this, 500)
            }
        }
    }

    private fun startResultPolling() {
        TaskRunner.running = true
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
    }
}
