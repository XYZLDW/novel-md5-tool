package com.example.novelmd5

import java.util.regex.Pattern

/**
 * 功能2核心：把小说 txt 里所有 <img src="..."> 旧标签按出现顺序替换为
 * <img src="MD5_k" align="center">。
 *
 * 纯逻辑，不碰 IO —— 文本读入、输出写文件都由调用方（MainActivity）负责，
 * 这样同一套逻辑既可用于 SAF 路径也可用于本地 File 路径。
 */
object NovelTask {

    data class Result(
        val totalImgs: Int,
        val filled: Int,
        val unfilled: Int,
        val remainingMd5: Int,
        val text: String
    )

    private val IMG_PATTERN = Pattern.compile(
        """<img\s+[^>]*?src\s*=\s*("[^"]*"|'[^']*'|\S+)(\s+[^>]*)?>""",
        Pattern.CASE_INSENSITIVE
    )

    private const val PH = "\uE000IMG_%05d\uE001"

    /** 纯逻辑：占位 + 按序填 md5，返回替换后的全文。 */
    fun process(novelText: String, md5s: List<String>): Result {
        val positions = mutableListOf<Pair<Int, Int>>()
        val m = IMG_PATTERN.matcher(novelText)
        while (m.find()) positions.add(m.start() to m.end())

        val sb = StringBuilder()
        var lastEnd = 0
        for ((i, p) in positions.withIndex()) {
            val s = p.first; val e = p.second
            sb.append(novelText, lastEnd, s)
            sb.append(String.format(PH, i + 1))
            lastEnd = e
        }
        sb.append(novelText, lastEnd, novelText.length)
        var result = sb.toString()

        var filled = 0
        for ((i, md5) in md5s.withIndex()) {
            if (i >= positions.size) break
            val ph = String.format(PH, i + 1)
            val tag = "<img src=\"$md5\" align=\"center\">"
            val replaced = result.replace(ph, tag)
            if (replaced != result) result = replaced
            filled++
        }

        return Result(
            totalImgs = positions.size,
            filled = filled,
            unfilled = positions.size - filled,
            remainingMd5 = md5s.size - filled,
            text = result
        )
    }

    fun countImgs(text: String): Int {
        val m = IMG_PATTERN.matcher(text)
        var c = 0
        while (m.find()) c++
        return c
    }
}
