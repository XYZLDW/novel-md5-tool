package com.example.novelmd5

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

    suspend fun run(dir: File, onProgress: (Int, Int) -> Unit): Result =
        withContext(Dispatchers.IO) {
            val list = dir.listFiles()?.filter {
                it.isFile && IMG_EXTS.any { ext -> it.name.lowercase().endsWith(ext) }
            }?.sortedBy { it.name.lowercase() } ?: emptyList()

            var ok = 0
            var fail = 0
            val failures = mutableListOf<String>()

            for ((index, file) in list.withIndex()) {
                val md = Md5.ofFile(file.absolutePath)
                val ext = file.extension.uppercase()
                val targetName = "$md.$ext"
                val target = File(dir, targetName)

                var renamed = false
                if (!target.exists()) {
                    renamed = file.renameTo(target)
                } else {
                    // 已存在同名（比如内容不同的文件 hash 冲突不可能，但同名旧文件可能）：覆盖
                    val bytes = file.readBytes()
                    target.writeBytes(bytes)
                    file.delete()
                    renamed = true
                }

                if (renamed) {
                    ok++
                } else {
                    fail++
                    failures.add("${file.name} -> $targetName")
                }
                onProgress(index + 1, list.size)
            }

            Result(ok, fail, failures)
        }
}
