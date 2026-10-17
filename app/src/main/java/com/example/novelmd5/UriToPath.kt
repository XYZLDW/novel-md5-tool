package com.example.novelmd5

import android.content.Context
import android.net.Uri
import java.io.File

/**
 * 把 SAF 的 content:// URI 解析成真实文件系统路径。
 * 对 /storage/emulated/0 下的目录有效；其它情况返回 null。
 */
object UriToPath {
    fun toRealPath(context: Context, uri: Uri): String? {
        val s = uri.toString()
        // content://com.android.external.documents:primary/DCIM/xxx
        val m = Regex("com\\.android\\.external\\.documents:(.+)").find(s)
        if (m != null) {
            val raw = m.groupValues[1].replace("+", " ")
            return "/storage/emulated/0" + (if (raw.startsWith("/")) "" else "/") + raw
        }
        if (s.startsWith("/storage/")) return s
        if (s.startsWith("/data/")) return s
        return null
    }
}
