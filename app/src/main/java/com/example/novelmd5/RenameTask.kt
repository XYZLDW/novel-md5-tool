package com.example.novelmd5

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object RenameTask {

    private val IMG_EXTS =
        listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".heic", ".tiff")

    data class Result(
        val success: Int,
        val failed: Int,
        val failures: List<String>
    )

    /** SAF 图片文档：uri + 原始文件名。 */
    data class ImgDoc(val uri: Uri, val name: String)

    /**
     * SAF 版重命名：
     *  1. 读 doc.uri 内容 → 算 MD5 → 新文件名 = MD5.扩展名
     *  2. 在 tree 下 createDocument(新文件名) → 写入原内容 → delete 旧 doc
     *
     * 之所以不直接"改名"，是因为 DocumentsContract 不提供 rename API，
     * 标准做法就是 create+copy+delete 三段。
     */
    suspend fun run(
        resolver: ContentResolver,
        tree: Uri,
        imageDocs: List<ImgDoc>,
        onProgress: (Int, Int) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val parentUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree, DocumentsContract.getTreeDocumentId(tree)
        )

        var ok = 0
        var fail = 0
        val failures = mutableListOf<String>()

        for ((i, doc) in imageDocs.withIndex()) {
            try {
                val md = resolver.openInputStream(doc.uri)?.use { Md5.ofStream(it) }
                    ?: throw IllegalStateException("读不到内容")
                val ext = doc.name.substringAfterLast('.', missingIfAbsent = "bin").uppercase()
                val targetName = "$md.$ext"

                val newUri = DocumentsContract.createDocument(resolver, parentUri, targetName)
                    ?: throw IllegalStateException("createDocument 失败")
                resolver.openInputStream(doc.uri)?.use { input ->
                    resolver.openOutputStream(newUri)?.use { output -> input.copyTo(output) }
                }
                resolver.delete(doc.uri, null, null)
                ok++
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
