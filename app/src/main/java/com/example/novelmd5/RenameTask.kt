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
     * SAF 重命名（create + copy + delete 三段，DocumentsContract 标准做法）：
     *  1. 读 doc.uri 内容 → 算 MD5 → 新名 = MD5.扩展名
     *  2. DocumentsContract.createDocument(resolver, tree, treeDocId, 新名) 创建新文档
     *  3. 把原内容写进新文档
     *  4. deleteDocument 删旧 doc
     *
     * 之所以不直接"改名"：旧版 DocumentsContract 没有公开的 rename API，
     * create+copy+delete 是官方文档推荐的改名实现方式，兼容性最好。
     */
    suspend fun run(
        tree: Uri,
        imageDocs: List<ImgDoc>,
        resolver: ContentResolver,
        onProgress: (Int, Int) -> Unit
    ): Result = withContext(Dispatchers.IO) {
        val treeDocId = DocumentsContract.getTreeDocumentId(tree)
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

                // createDocument(resolver, parentTreeUri, parentDocId, displayName)
        //   parentTreeUri = 选定的 tree（本文件夹）
        //   parentDocId   = tree 的 document id 字符串
        //   displayName   = 要创建的新文件名
        val newUri = DocumentsContract.createDocument(
            resolver, tree, treeDocId, targetName
        ) ?: throw IllegalStateException("createDocument 返回 null")

                // 3. 把原内容写进新文档
                resolver.openInputStream(doc.uri)?.use { input ->
                    resolver.openOutputStream(newUri)?.use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("写入新文档失败")

                // 4. 删除旧文档
                DocumentsContract.deleteDocument(resolver, doc.uri)

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
