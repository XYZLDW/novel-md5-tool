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
    private var pollCount = 0
    private val pollRunnable = object : Runnable {
        override fun run() {
            // 进程级静态结果是主源（服务完成/销毁后仍可读到）
            val rRes = TaskService.staticRenameResult()
            val nRes = TaskService.staticNovelResult()
            rRes?.let {
                findViewById<TextView>(R.id.tvRenameResult).text = it
            }
            nRes?.let {
                findViewById<TextView>(R.id.tvNovelResult).text = it
            }
            // 活服务实例（进行中进度）
            val svc = TaskService.instance()
            if (svc?.running == true || TaskService.staticRunning()) {
                pollCount = 0
                pollHandler.postDelayed(this, 400)
                return
            }
            // 降级：应用内 TaskRunner 结果（前台服务被 ROM 拒时的兜底路径）
            TaskRunner.renameResult?.let {
                findViewById<TextView>(R.id.tvRenameResult).text = it
            }
            TaskRunner.novelResult?.let {
                findViewById<TextView>(R.id.tvNovelResult).text = it
            }
            if (TaskRunner.running) {
                pollCount = 0
                pollHandler.postDelayed(this, 400)
                return
            }
            // 任务刚结束：再续订几轮，确保完成瞬间的静态结果被刷上屏
            // （应对"用户留在界面、没切走触发 onResume"的情况）
            if (pollCount < 5) {
                pollCount += 1
                pollHandler.postDelayed(this, 600)
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

    /** Shizuku 授权结果回来（1001）：刷新可用性状态。 */
    override fun onResume() {
        super.onResume()
        // 任务还在跑 → 继续轮询
        if (TaskService.instance()?.running == true || TaskService.staticRunning()) {
            pollHandler.post(pollRunnable)
        }
        // 优先读进程级静态结果（服务完成后仍可读到）
        TaskService.staticRenameResult()?.let {
            findViewById<TextView>(R.id.tvRenameResult).text = it
        }
        TaskService.staticNovelResult()?.let {
            findViewById<TextView>(R.id.tvNovelResult).text = it
        }
        // 再读活的服务（进行中进度）
        TaskService.instance()?.let { svc ->
            svc.renameResult?.let {
                findViewById<TextView>(R.id.tvRenameResult).text = it
            }
            svc.novelResult?.let {
                findViewById<TextView>(R.id.tvNovelResult).text = it
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            100 -> {
                if (grantResults.isNotEmpty() &&
                    grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    Toast.makeText(
                        this, "通知权限已允许，重按一次「开始」即可看到通知栏",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
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
        val pending = { startRenameService(tree) }
        // 通知权限没授予时先请求，授完再启动服务（保证第一遍就弹通知）
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            // 用户在 onActivityResult 回来后手动重触发；这里先记一笔
            Toast.makeText(this, "请允许通知权限后重按一次开始", Toast.LENGTH_LONG).show()
            return
        }
        pending()
    }

    private fun startRenameService(tree: Uri) {
        try {
            val i = Intent(this, TaskService::class.java)
                .setAction(TaskService.ACTION_RENAME)
                .putExtra(TaskService.EXTRA_TREE, tree)
            if (Build.VERSION.SDK_INT >= 29) startForegroundService(i) else startService(i)
            pollHandler.post(pollRunnable)
            Toast.makeText(this, "重命名已在后台开始，可锁屏/切走", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // iQOO 等 ROM 可能拒绝 sideload 应用的前台服务 → 降级为应用内执行，任务不丢
            findViewById<TextView>(R.id.tvRenameResult).text =
                "前台服务启动失败（${e.message}），已改为应用内执行"
            TaskRunner.startRename(contentResolver, tree)
            pollHandler.post(pollRunnable)
            Toast.makeText(this, "已在应用内开始（不能切走）", Toast.LENGTH_SHORT).show()
        }
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
        // 通知权限没授予时先请求，授完再启动服务（保证第一遍就弹通知）
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            Toast.makeText(this, "请允许通知权限后重按一次开始", Toast.LENGTH_LONG).show()
            return
        }
        startNovelService(novel, tree, outTree, sortIdx)
    }

    private fun startNovelService(
        novel: Uri, tree: Uri, out: Uri?, sort: Int
    ) {
        try {
            val i = Intent(this, TaskService::class.java)
                .setAction(TaskService.ACTION_NOVEL)
                .putExtra(TaskService.EXTRA_NOVEL, novel)
                .putExtra(TaskService.EXTRA_MD5TREE, tree)
                .putExtra(TaskService.EXTRA_OUTTREE, out)
                .putExtra(TaskService.EXTRA_SORT, sort)
            if (Build.VERSION.SDK_INT >= 29) startForegroundService(i) else startService(i)
            pollHandler.post(pollRunnable)
            Toast.makeText(this, "替换已在后台开始，可锁屏/切走", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // iQOO 等 ROM 可能拒绝前台服务 → 降级为应用内 TaskRunner 执行，任务不丢
            findViewById<TextView>(R.id.tvNovelResult).text =
                "前台服务启动失败（${e.message}），已改为应用内执行"
            TaskRunner.startNovel(contentResolver, novel, tree, out, sort)
            pollHandler.post(pollRunnable)
            Toast.makeText(this, "已在应用内开始（不能切走）", Toast.LENGTH_SHORT).show()
        }
    }
}
