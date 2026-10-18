package com.example.novelmd5

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object RenameTask {

    private val IMG_EXTS =
        listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".heic", ".tiff", ".ipg")

    data class Result(
        val success: Int,
        val failed: Int,
        val failures: List<String>
    )

    /**
     * SAF 图片文档：
     *  - uri         = 文档自身 URI（content://.../document/...）
     *  - name        = 原始显示名
     *  - parentTree  = 该文档**实际所在目录**对应的 tree URI（低版本 create+copy+delete 需要）
     *  - parentDocId = 该目录在 provider 里的绝对 documentId
     */
    data class ImgDoc(
        val uri: Uri,
        val name: String,
        val parentTree: Uri,
        val parentDocId: String
    )

    /**
     * SAF 重命名：
     *  - Android 11+（API 30）：原生 DocumentsContract.renameDocument，在原目录内原子改名。
     *  - 更早版本：createDocument(父目录) + 拷贝 + deleteDocument 三段。
     *
     * 关键：新文件必须创建在图片**实际所在目录**（parentTree/parentDocId），
     * 不能假设都在用户选定的顶层 tree 里 —— 图片可能在子文件夹中。
     */
    suspend fun run(
        imageDocs: List<ImgDoc>,
        resolver: ContentResolver,
        onProgress: (Int, Int) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val useRenameApi = Build.VERSION.SDK_INT >= 30
        var ok = 0
        var fail = 0
        val failures = mutableListOf<String>()

        for ((i, doc) in imageDocs.withIndex()) {
            try {
                // 1. 读内容算 MD5
                val md = resolver.openInputStream(doc.uri)?.use { Md5.ofStream(it) }
                    ?: throw IllegalStateException("读不到内容")
                val ext = doc.name.substringAfterLast('.', "bin").uppercase()
                val targetName = "$md.$ext"
                if (targetName.equals(doc.name, ignoreCase = true)) {
                    ok++ // 已经是目标名，跳过
                } else if (useRenameApi) {
                    // 2a. 原生改名（同一目录内）
                    DocumentsContract.renameDocument(resolver, doc.uri, targetName)
                        ?: throw IllegalStateException("renameDocument 返回 null")
                    ok++
                } else {
                    // 2b. 旧版：在图片实际所在目录里 create + copy + delete
                    val newUri = DocumentsContract.createDocument(
                        resolver, doc.parentTree, doc.parentDocId, targetName
                    ) ?: throw IllegalStateException("createDocument 失败")
                    resolver.openInputStream(doc.uri)?.use { input ->
                        resolver.openOutputStream(newUri)?.use { output ->
                            input.copyTo(output)
                        }
                    } ?: throw IllegalStateException("写入新文档失败")
                    DocumentsContract.deleteDocument(resolver, doc.uri)
                    ok++
                }
            } catch (e: Exception) {
                fail++
                failures.add("${doc.name} -> ${e.message}")
            }
            onProgress(i + 1, imageDocs.size)
        }

        Result(ok, fail, failures)
    }

    /** 兼容旧调用（基于真实 File）。 */
    suspend fun runLegacy(dir: File, onProgress: (Int, Int) -> Unit): Result =
        withContext(Dispatchers.IO) {
            val list = dir.listFiles()?.filter {
                it.isFile && IMG_EXTS.any { ext -> it.name.lowercase().endsWith(ext) }
            }?.sortedBy { it.name.lowercase() } ?: emptyList()

            var ok = 0; var fail = 0
            val failures = mutableListOf<String>()

            for ((index, file) in list.withIndex()) {
                val md = Md5.ofFile(file.absolutePath)
                val ext = file.extension.uppercase()
                val target = File(dir, "$md.$ext")
                var renamed = false
                if (!target.exists()) {
                    renamed = file.renameTo(target)
                } else {
                    target.writeBytes(file.readBytes()); file.delete(); renamed = true
                }
                if (renamed) ok++ else { fail++; failures.add("${file.name} -> $target") }
                onProgress(index + 1, list.size)
            }

            Result(ok, fail, failures)
        }
}
