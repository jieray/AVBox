package com.github.avbox.osc.util

import android.os.Environment

import java.io.File

/**
 * 本地脚本的 api 换算。
 *
 * 两条通道各走各的，不能混：
 *
 * ① js：JsSpider 走 FileUtils.loadModule，它认 "clan://localhost/<相对外部存储根的路径>"，
 *      会转成 "http://127.0.0.1:port/file/<相对路径>"，服务端 /file/ 端点再按外部存储根目录拼回绝对路径。
 *      所以 js 的 api 必须是【相对路径】——给绝对路径会被拼两次根，取不到文件。
 *
 * ② py：PythonSpider 走 app.py 的 downloadPlugin，它只认 "file://" 前缀当本地文件，
 *      其余一律用 requests 当网络地址下载。所以 py 的 api 必须是【file:// + 绝对路径】。
 *      给 clan:// 会被当远程地址下载，必然失败（表现就是"下载插件失败 / 加载失败"）。
 *
 * 早先为了修 js 把所有本地 api 统一换成 clan://，结果 py 全线挂掉；现在按后缀分流。
 */
object LocalApi {

    private const val CLAN = "clan://localhost/"

    /** 判断后缀是不是 js（带 ?query 也算）。 */
    @JvmStatic
    fun isJs(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        val base = raw.substringBefore("?")
        return base.endsWith(".js", true)
    }

    @JvmStatic
    fun isLocal(url: String?): Boolean {
        val raw = url?.trim().orEmpty()
        return raw.startsWith(CLAN) || raw.startsWith("file://") || raw.startsWith("/")
    }

    /**
     * 换算成各引擎原生认得的本地地址：
     * js -> clan://localhost/<相对路径>；py -> file://<绝对路径>；非本地地址原样返回。
     */
    @JvmStatic
    fun normalize(url: String?): String {
        val raw = url?.trim() ?: return ""
        if (raw.isEmpty()) return raw
        val path = when {
            raw.startsWith("file://") -> android.net.Uri.decode(raw.substring("file://".length))
            raw.startsWith(CLAN) -> return raw
            raw.startsWith("/") -> raw
            else -> return raw
        }
        val root = Environment.getExternalStorageDirectory().absolutePath
        val rel = if (path.startsWith(root)) path.substring(root.length) else path
        val relTrim = rel.trimStart('/')
        if (relTrim.isEmpty()) return raw
        return if (isJs(raw)) {
            CLAN + relTrim
        } else {
            "file://" + (if (path.startsWith("/")) path else root + "/" + relTrim)
        }
    }

    /** 换算成 app.py 的 downloadPlugin 能识别的本地文件地址；非本地地址原样返回。 */
    @JvmStatic
    fun toFileUrl(url: String?): String {
        val raw = url?.trim() ?: return ""
        if (raw.isEmpty()) return raw
        val root = Environment.getExternalStorageDirectory().absolutePath
        return when {
            raw.startsWith("file://") -> raw
            raw.startsWith(CLAN) -> "file://" + root + "/" + raw.substring(CLAN.length)
            raw.startsWith("/") -> "file://" + raw
            else -> raw
        }
    }

    /**
     * 还原成本地真实文件（js 的 clan:// 与 py 的 file:// 都能还原）。
     * 用于 py 加载失败时的兜底：Java 层把文件复制到 App 私有缓存目录再交给 python，
     * 规避外部存储权限 / 路径不可读导致的 open 失败。
     */
    @JvmStatic
    fun sourceFile(url: String?): File? {
        val raw = url?.trim() ?: return null
        if (raw.isEmpty()) return null
        val root = Environment.getExternalStorageDirectory().absolutePath
        val path = when {
            raw.startsWith(CLAN) -> android.net.Uri.decode(root + "/" + raw.substring(CLAN.length))
            raw.startsWith("file://") -> android.net.Uri.decode(raw.substring("file://".length))
            raw.startsWith("/") -> android.net.Uri.decode(raw.substringBefore("?"))
            else -> return null
        }
        val file = File(path)
        return if (file.exists()) file else null
    }
}
