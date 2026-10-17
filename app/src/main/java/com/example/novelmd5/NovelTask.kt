package com.example.novelmd5

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.regex.Pattern

/**
 * 功能2核心：把小说 txt 里所有 <img src="..."> 旧标签按出现顺序替换为
 * <img src="MD5_k" align="center">。
 */
object NovelTask {

    data class Result(
        val totalImgs: Int,
        val filled: Int,
        val unfilled: Int,
        val remainingMd5: Int,
        val outputFile: File
    )

    private val IMG_PATTERN = Pattern.compile(
        """<img\s+[^>]*?src\s*=\s*("[^"]*"|'[^']*'|\S+)(\s+[^>]*)?>""",
        Pattern.CASE_INSENSITIVE
    )

    private const val PH = "\uE000IMG_%05d\uE001"

    suspend fun process(novel: File, md5s: List<String>, outputBase: File): Result =
        withContext(Dispatchers.IO) {
            val text = novel.readText(Charsets.UTF_8)

            // 扫描所有 img 标签位置
            val positions = mutableListOf<Pair<Int, Int>>()
            val m = IMG_PATTERN.matcher(text)
            while (m.find()) positions.add(m.start() to m.end())

            // 按出现顺序拼接占位符
            val sb = StringBuilder()
            var lastEnd = 0
            for ((i, (s, e)) in positions.withIndex()) {
                sb.append(text, lastEnd, s)
                sb.append(String.format(PH, i + 1))
                lastEnd = e
            }
            sb.append(text, lastEnd, text.length)
            var result = sb.toString()

            // 逐个填入 md5
            var filled = 0
            for ((i, md5) in md5s.withIndex()) {
                if (i >= positions.size) break
                val ph = String.format(PH, i + 1)
                val tag = "<img src=\"$md5\" align=\"center\">"
                val replaced = result.replace(ph, tag)
                if (replaced != result) result = replaced
                filled++
            }

            val outFile = File(
                outputBase.parent,
                outputBase.name.removeSuffix(".txt") + "_filled.txt"
            )
            outFile.parentFile?.mkdirs()
            outFile.writeText(result, Charsets.UTF_8)

            Result(
                totalImgs = positions.size,
                filled = filled,
                unfilled = positions.size - filled,
                remainingMd5 = md5s.size - filled,
                outputFile = outFile
            )
        }

    fun countImgs(text: String): Int {
        val m = IMG_PATTERN.matcher(text)
        var c = 0
        while (m.find()) c++
        return c
    }
}
