package com.github.catvod.crawler

import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

/**
 * HTML 站点的配置与宫格构造（对应 appmenu 的 WvWv / Config）。
 *
 * 【范式说明 —— 与旧实现的关键差别】
 * 旧实现把 html 当"爬虫"：起 WebView 跑脚本，等它调 fm.done() 回传分类宫格。
 * 市面上几乎没有 html 这么写，所以稳定"只出框架、没数据"。
 *
 * appmenu 的范式反过来：html 只是【被打开的应用】，数据由 Java 自己造 ——
 *   · homeContent  读 ext 的 items / dir / root 造宫格返回（不起 WebView）
 *   · detailContent 才起 WebView 打开页面，然后返回空列表（因此不进详情页）
 *   · playerContent 恒返回 {"parse":0,"url":""}
 * 本文件实现前一半：配置解析 + 宫格构造。
 */
class HtmlConfig private constructor() {

    data class Entry(
        val id: String,
        val name: String,
        val url: String,
        val pic: String,
        val cate: String,
    )

    val entries = ArrayList<Entry>()
    var direct = false
    var directUrl = ""
    var directProxyOn = false
    var proxy = ""
    var proxySwitch = ""
    var defaultPic = ""
    var detailMode = ""
    var detailDelayMs = 0
    var patchFetch = true
    var interceptAll = false
    var folderMode = false

    private var seq = 0
    private var t0 = 0L
    private var visit = 0

    /** 分类清单（去重、保序），不含"全部" */
    fun categories(): List<String> {
        val out = ArrayList<String>()
        for (e in entries) {
            val c = e.cate
            if (c.isNotEmpty() && !out.contains(c)) out.add(c)
        }
        return out
    }

    fun byId(id: String): Entry? {
        if (id.isEmpty()) return null
        if (id.startsWith("f:")) {
            val p = id.substring(2)
            return entries.firstOrNull { it.url == p }
                ?: Entry(id, File(p).name, p, defaultPic, "")
        }
        return entries.firstOrNull { it.id == id }
    }

    /** 分类名 → type_id；"全部" 固定为 "0" */
    fun catId(cate: String): String {
        val i = categories().indexOf(cate)
        return if (i < 0) "0" else (i + 1).toString()
    }

    /**
     * 该条目是否走代理。两条判定（与站点管理里的关键字开关一致）：
     *   1) 站点 ext 显式给了 proxy 且开关打开（proxySwitch 非空即视为开）
     *   2) 文件名或父目录名含「代理」；远程 url 含「#代理」
     */
    fun proxyFor(e: Entry): String {
        if (proxy.isEmpty()) return ""
        val on = proxySwitch.isNotEmpty() || hasProxyKeyword(e.url)
        return if (on) proxy else ""
    }

    fun grid(cateId: String): String {
        val cls = JSONArray()
        cls.put(JSONObject().put("type_id", "0").put("type_name", ALL_CATE))
        for (c in categories()) {
            cls.put(JSONObject().put("type_id", catId(c)).put("type_name", c))
        }
        val list = JSONArray()
        for (e in entries) {
            val cid = if (e.cate.isEmpty()) "0" else catId(e.cate)
            if (cateId != "0" && cateId != cid) continue
            list.put(
                JSONObject()
                    .put("vod_id", e.id)
                    .put("vod_name", e.name)
                    .put("vod_pic", if (e.pic.isEmpty()) defaultPic else e.pic)
                    .put("vod_remarks", "HTML")
            )
        }
        return JSONObject()
            .put("class", cls)
            .put("list", list)
            .put("page", 1)
            .put("pagecount", 1)
            .put("limit", list.length())
            .put("total", list.length())
            .toString()
    }

    /** 详情（detailMode=full 时才用）：把条目包装成一条详情数据 */
    fun fullDetail(e: Entry): String {
        val vod = JSONObject()
            .put("vod_id", e.id)
            .put("vod_name", e.name)
            .put("vod_pic", if (e.pic.isEmpty()) defaultPic else e.pic)
            .put("type_name", if (e.cate.isEmpty()) "HTML" else e.cate)
            .put("vod_remarks", "HTML")
            .put("vod_play_from", "网页")
            .put("vod_play_url", "打开\$" + e.id)
        return JSONObject().put("list", JSONArray().put(vod)).toString()
    }

    // ---------- 解析 ----------

    private fun add(name: String, url: String, pic: String, cate: String) {
        if (url.isEmpty()) return
        entries.add(Entry("e${seq++}", name, url, pic, cate))
    }

    private fun addFolder(dir: File, cate: String) {
        add(dir.name, dir.absolutePath, "", cate)
    }

    private fun addItems(arr: JSONArray) {
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val name = pick(o, "name", "title", "vod_name")
            val url = pick(o, "url", "file", "src", "path")
            val pic = pick(o, "pic", "vod_pic", "thumb", "cover")
            val cate = pick(o, "type_name", "group", "cate", "category", "type")
            add(
                if (name.isEmpty()) guessName(url) else name,
                url, pic, cate,
            )
        }
    }

    private fun scanDir(dir: String, cate: String) {
        val root = File(dir)
        if (!root.exists()) return
        t0 = System.currentTimeMillis()
        visit = 0
        scanInto(root, cate, 0)
    }

    private fun scanInto(dir: File, cate: String, depth: Int) {
        if (depth > MAX_DEPTH) return
        if (visit > MAX_VISIT) return
        if (System.currentTimeMillis() - t0 > MAX_SEARCH_MS) return
        val kids = dir.listFiles() ?: return
        kids.sortWith(compareBy { it.name })
        for (f in kids) {
            if (visit++ > MAX_VISIT) return
            if (System.currentTimeMillis() - t0 > MAX_SEARCH_MS) return
            if (entries.size >= MAX_MATCH) return
            if (f.isDirectory) {
                if (depth > 0 && SKIP_DIRS.contains(f.name.lowercase())) continue
                if (folderMode) {
                    if (countHtml(f) > 0) addFolder(f, cate)
                    else scanInto(f, cate, depth + 1)
                } else {
                    scanInto(f, cate, depth + 1)
                }
            } else if (isHtml(f.name)) {
                add(f.name.substringBeforeLast('.'), f.absolutePath, "", cate)
            }
        }
    }

    private fun countHtml(dir: File): Int {
        var n = 0
        val kids = dir.listFiles() ?: return 0
        for (f in kids) {
            if (f.isFile && isHtml(f.name)) n++
            if (n > 0) break
        }
        return n
    }

    private fun parseJson(o: JSONObject) {
        val items = o.optJSONArray("items") ?: o.optJSONArray("list") ?: o.optJSONArray("sites")
        if (items != null) addItems(items)

        val dir = pick(o, "dir", "dirPath", "dir_path", "root", "rootDir", "root_dir", "base", "baseDir", "basePath", "base_path")
        if (dir.isNotEmpty()) scanDir(dir, pick(o, "type_name", "cate", "group"))

        proxy = pick(o, "proxy", "proxyUrl", "proxy_url")
        proxySwitch = pick(o, "proxySwitch", "proxy_switch")
        defaultPic = pick(o, "defaultPic", "default_pic")
        detailMode = pick(o, "detailMode", "detail_mode")
        detailDelayMs = o.optInt("detailDelayMs", o.optInt("detail_delay", 0))
        patchFetch = o.optBoolean("patchFetch", o.optBoolean("patch_fetch", true))
        interceptAll = o.optBoolean("interceptAll", o.optBoolean("intercept_all", false))
        folderMode = o.optBoolean("folder", o.optBoolean("folderMode", o.optBoolean("folder_mode", false)))
        val du = pick(o, "directUrl", "direct_url", "url", "direct")
        if (o.optBoolean("direct", false) || du.isNotEmpty()) {
            direct = true
            directUrl = if (du.isNotEmpty() && du != "true") du else ""
            directProxyOn = o.optBoolean("directProxyOn", o.optBoolean("direct_proxy", false))
        }
    }

    companion object {
        const val ALL_CATE = "全部"
        private const val MAX_DEPTH = 5
        private const val MAX_MATCH = 8
        private const val MAX_VISIT = 40000
        private const val MAX_SEARCH_MS = 2500L
        private val SKIP_DIRS = setOf(
            "android", "dcim", "pictures", "movies", "music", "download", "documents",
            "alarms", "ringtones", "notifications", "podcasts", "audiobooks",
            "system", "data", "cache", "obb", "media", "tencent", "qqbrowser",
            "screenshots", "recordings",
        )
        private val HTML_EXT = listOf(".html", ".htm", ".xhtml", ".shtml")

        fun isHtml(name: String): Boolean {
            val n = name.lowercase()
            return HTML_EXT.any { n.endsWith(it) }
        }

        fun hasProxyKeyword(url: String): Boolean {
            if (url.isEmpty()) return false
            if (url.contains("#代理")) return true
            if (url.startsWith("http://") || url.startsWith("https://")) return false
            val f = File(url)
            if (f.name.contains("代理")) return true
            return f.parentFile?.name?.contains("代理") == true
        }

        private fun pick(o: JSONObject, vararg keys: String): String {
            for (k in keys) {
                val v = o.optString(k, "")
                if (v.isNotEmpty() && v != "null") return v
            }
            return ""
        }

        private fun guessName(url: String): String {
            if (url.isEmpty()) return "网页"
            val raw = url.substringBefore('#').substringBefore('?')
            val last = raw.substringAfterLast('/', "")
            if (last.isEmpty()) return raw.substringAfter("//").substringBefore('/').ifEmpty { "网页" }
            return last.substringBeforeLast('.').ifEmpty { last }
        }

        /**
         * 本地绝对路径 → 本地文件服务的 http 地址（对应 appmenu 的 svc()）。
         *
         * 为什么要转：直接 file:// 加载时，页面的 origin 是 file://，页内
         * fetch/XHR 会被 CORS 打死，页面只能出框架取不到数据。换成
         * http://127.0.0.1:<port>/file/... 后页面跑在标准 http 源上，fetch 正常。
         * 本 App 的 RemoteServer 已有 /file/ 端点（root 为外部存储根目录）。
         *
         * 端口不能写死：9978 被占用时 ControlManager 会递增，所以运行时取实际端口
         * （对应 appmenu 的 detectSvcPort）；拿不到就退回 file:// 而不是拼错地址。
         */
        fun svc(url: String): String {
            if (url.isBlank()) return ""
            val t = url.trim()
            if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("file://")) return t
            if (!t.startsWith("/")) return t
            val rel = stripRoot(t) ?: return "file://$t"
            val port = servicePort()
            if (port <= 0) return "file://$t"
            val sb = StringBuilder("http://127.0.0.1:$port/file/")
            val segs = rel.split("/")
            var first = true
            for (s in segs) {
                if (!first) sb.append('/')
                first = false
                sb.append(
                    try {
                        URLEncoder.encode(s, "UTF-8").replace("+", "%20")
                    } catch (th: Throwable) {
                        s
                    }
                )
            }
            return sb.toString()
        }

        private fun stripRoot(p: String): String? {
            val roots = listOf(
                Environment.getExternalStorageDirectory().absolutePath + "/",
                "/storage/emulated/0/",
                "/mnt/sdcard/",
                "/sdcard/",
            )
            for (r in roots) {
                if (p.startsWith(r)) return p.substring(r.length)
            }
            return null
        }

        /** 本地文件服务的实际端口（9978 被占用时 ControlManager 会递增） */
        private fun servicePort(): Int = try {
            val cm = com.github.avbox.osc.server.ControlManager.get()
            var p = cm.startedPort()
            if (p <= 0) {
                cm.startServer()
                p = cm.startedPort()
            }
            p
        } catch (th: Throwable) {
            0
        }

        fun parse(ext: String?, api: String?, siteName: String?): HtmlConfig {
            val c = HtmlConfig()
            var text = ext?.trim() ?: ""
            // ext 可能是 clan://localhost/x 或 file://x 这类本地地址写法
            if (text.startsWith("clan://localhost/")) {
                text = Environment.getExternalStorageDirectory().absolutePath + "/" +
                    text.substring("clan://localhost/".length)
            } else if (text.startsWith("file://")) {
                text = text.substring("file://".length)
            }

            if (text.startsWith("{")) {
                try {
                    c.parseJson(JSONObject(text))
                } catch (th: Throwable) {
                    c.add(guessName(text), text, "", "")
                }
            } else if (text.isNotEmpty()) {
                // 纯文本：目录 → 扫描；否则当一个页面地址
                val f = File(text)
                if (f.isDirectory) c.scanDir(text, "")
                else if (isHtml(text) || text.startsWith("http")) c.add(guessName(text), text, "", "")
            }

            // 站点自己的 api（本地路径或远程 url）永远作为兜底条目：
            // 单个 html 文件站点也要在首页有一个宫格，点它才能打开页面
            if (c.entries.isEmpty() && !c.direct) {
                val a = (api ?: "").trim()
                if (a.isNotEmpty()) {
                    val name = siteName?.takeIf { it.isNotBlank() } ?: guessName(a)
                    c.add(name, a, "", "")
                }
            }
            if (c.direct && c.directUrl.isEmpty()) c.directUrl = api ?: ""
            return c
        }
    }
}
