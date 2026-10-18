package com.example.novelmd5

import java.io.File
import java.security.MessageDigest

object Md5 {
    fun ofFile(path: String): String {
        val md = MessageDigest.getInstance("MD5")
        File(path).inputStream().use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02X".format(it) }
    }

    fun ofBytes(data: ByteArray): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(data).joinToString("") { "%02X".format(it) }
    }

    /** 从任意输入流计算 MD5（不要求是本地文件，SAF content:// 也能用）。 */
    fun ofStream(inputStream: java.io.InputStream): String {
        val md = MessageDigest.getInstance("MD5")
        val buf = ByteArray(8192)
        while (true) {
            val n = inputStream.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
        }
        return md.digest().joinToString("") { "%02X".format(it) }
    }
}
