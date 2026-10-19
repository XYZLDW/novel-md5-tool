package com.example.novelmd5

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // 三类目录/文件（SAF DocumentUri）
    private var imgTree: Uri? = null
    private var novelUri: Uri? = null
    private var md5Tree: Uri? = null
    private var outTree: Uri? = null

    // SAF 选择器
    private var pickTarget = "imgDir"
    private val pickDir = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
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
                    findViewById<TextView>(R.id.tvOutDir).text =
                        "已选：${DocUtils.displayName(contentResolver, uri)}"
                }
            }
        }
    }

    private val pickText = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            novelUri = uri
            findViewById<TextView>(R.id.tvNovelFile).text =
                DocUtils.displayName(contentResolver, uri)
            checkNovelReady()
        }
    }

    // 轮询前台服务结果回显到界面
    private val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            val svc = TaskService.instance()
            if (svc != null) {
                svc.renameResult?.let {
                    findViewById<TextView>(R.id.tvRenameResult).text = it
                }
                svc.novelResult?.let {
                    findViewById<TextView>(R.id.tvNovelResult).text = it
                }
                if (svc.running) {
                    pollHandler.postDelayed(this, 400)
                    return
                }
            }
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

        checkNovelReady()
    }

    override fun onResume() {
        super.onResume()
        TaskService.instance()?.let { svc ->
            svc.renameResult?.let {
                findViewById<TextView>(R.id.tvRenameResult).text = it
            }
            svc.novelResult?.let {
                findViewById<TextView>(R.id.tvNovelResult).text = it
            }
        }
        if (TaskService.instance()?.running == true) pollHandler.post(pollRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun checkNovelReady() {
        findViewById<Button>(R.id.btnDoNovel).isEnabled =
            novelUri != null && md5Tree != null
    }

    // ---------- 功能1（前台服务，锁屏/切后台/划掉任务都不中断） ----------
    private fun startRename() {
        val tree = imgTree ?: run {
            Toast.makeText(this, "请先选择图片文件夹", Toast.LENGTH_SHORT).show(); return
        }
        findViewById<TextView>(R.id.tvRenameResult).text = "已提交，看通知栏进度…"
        ensurePostNotifications()
        val i = Intent(this, TaskService::class.java)
            .setAction(TaskService.ACTION_RENAME)
            .putExtra(TaskService.EXTRA_TREE, tree)
        if (Build.VERSION.SDK_INT >= 29) startForegroundService(i) else startService(i)
        pollHandler.post(pollRunnable)
        Toast.makeText(this, "重命名已在后台开始，可锁屏/切走", Toast.LENGTH_SHORT).show()
    }

    // ---------- 功能2（前台服务） ----------
    private fun startNovel() {
        val novel = novelUri ?: run {
            Toast.makeText(this, "请先选择小说 txt", Toast.LENGTH_SHORT).show(); return
        }
        val tree = md5Tree ?: run {
            Toast.makeText(this, "请先选择图片文件夹", Toast.LENGTH_SHORT).show(); return
        }
        val sortIdx = findViewById<android.widget.Spinner>(R.id.spinnerMd5Order).selectedItemPosition
        findViewById<TextView>(R.id.tvNovelResult).text = "已提交，看通知栏进度…"
        ensurePostNotifications()
        val i = Intent(this, TaskService::class.java)
            .setAction(TaskService.ACTION_NOVEL)
            .putExtra(TaskService.EXTRA_NOVEL, novel)
            .putExtra(TaskService.EXTRA_MD5TREE, tree)
            .putExtra(TaskService.EXTRA_OUTTREE, outTree)
            .putExtra(TaskService.EXTRA_SORT, sortIdx)
        if (Build.VERSION.SDK_INT >= 29) startForegroundService(i) else startService(i)
        pollHandler.post(pollRunnable)
        Toast.makeText(this, "替换已在后台开始，可锁屏/切走", Toast.LENGTH_SHORT).show()
    }

    /** Android 13+ 需要通知权限，否则前台服务通知不显示（但服务仍会保活进程）。 */
    private fun ensurePostNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100
            )
        }
    }
}
