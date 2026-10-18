package com.example.novelmd5

import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/** SAF / 文件 共享工具：Activity 与后台 Service 共用。 */
object DocUtils {

    private val IMG_EXTS =
        listOf(".jpg", ".jpeg", ".png", ".webp", ".bmp", ".gif", ".heic", ".tiff", ".ipg")

    fun isImage(name: String): Boolean =
        IMG_EXTS.any { ext -> name.lowercase().endsWith(ext) }

    /**
     * 递归列出某 tree 下所有子文档（含子文件夹里的图片），
     * 每个文档记录它实际所在目录的 tree/docId，并带上 修改时间/大小。
     */
    fun listImageDocs(resolver: ContentResolver, tree: Uri): List<RenameTask.ImgDoc> {
        val results = mutableListOf<RenameTask.ImgDoc>()
        val stack = ArrayDeque<Uri>()
        stack.addLast(tree)
        while (stack.isNotEmpty()) {
            val cur = stack.removeLast()
            val curDocId = DocumentsContract.getTreeDocumentId(cur)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(cur, curDocId)
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                        DocumentsContract.Document.COLUMN_SIZE),
                null, null, null
            )?.use { c ->
                val nameIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val mimeIdx = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val mtimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                while (c.moveToNext()) {
                    val name = c.getString(nameIdx)
                    val childDocId = c.getString(idIdx)
                    val mime = c.getString(mimeIdx)
                    val mtime = if (mtimeIdx >= 0) c.getLong(mtimeIdx) else 0L
                    val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(cur, childDocId)
                    val mimeLower = mime?.lowercase() ?: ""
                    if (mimeLower == DocumentsContract.Document.MIME_TYPE_DIR) {
                        // externalstorage 的 childDocId 是**绝对** docId，直接作为新 tree 根。
                        val subTree = DocumentsContract.buildTreeDocumentUri(cur.authority, childDocId)
                        stack.addLast(subTree)
                    }
                    results.add(RenameTask.ImgDoc(childUri, name, cur, curDocId, mtime, size))
                }
            }
        }
        return results
    }

    /** 统计某 tree 下图片数量（递归）。 */
    fun listImageCount(resolver: ContentResolver, tree: Uri): Int =
        listImageDocs(resolver, tree).count { isImage(it.name) }

    /** 显示名。 */
    fun displayName(resolver: ContentResolver, uri: Uri): String {
        return try {
            resolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.toString()
            } ?: uri.toString()
        } catch (_: Exception) { uri.toString() }
    }

    /**
     * 把 SAF tree URI 映射成真实文件系统路径（供"全部文件访问"授权后用 File API 直写）。
     * externalstorage provider 的 docId 形如 "primary:书籍/阅读导出用/xxx"：
     *  - primary → /storage/emulated/0/xxx
     *  - 其它卷  → /storage/<volumeId>/xxx
     */
    fun treeToRealPath(tree: Uri): File? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(tree)
            val colon = docId.indexOf(':')
            if (colon < 0) return null
            val volume = docId.substring(0, colon)
            val rel = docId.substring(colon + 1)
            val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
            File(root, rel)
        } catch (_: Exception) { null }
    }

    /** 是否已授予「全部文件访问」（MANAGE_EXTERNAL_STORAGE）。API 30+ 才有此开关。 */
    fun isExternalStorageManager(): Boolean {
        return Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager()
    }
}
