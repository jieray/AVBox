package com.github.catvod.crawler

import android.os.Handler
import android.os.Looper
import com.github.tvbox.osc.ui.activity.HtmlActivity
import com.github.tvbox.osc.util.AppContextHolder
import com.github.tvbox.osc.util.LOG
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * HTML 脚本爬虫（站点 type 6）。
 *
 * 与 js / py 走【同一套 Spider 接口】：分类与宫格数据由脚本自己返回，
 * 不是 WebView 里渲染出来的宫格 —— WebView 只负责跑脚本，不负责出 UI。
 *
 * 流程：每次被调用就拉起一次全屏 HtmlActivity 跑 html，脚本算完调
 * window.fm.done(result) 回传，Activity 随即关闭。所以：
 *   · 进站点（homeContent）自动弹一次，拿分类与宫格；
 *   · 点宫格（detailContent）再弹一次。
 *
 * Spider 接口是同步的，而 Activity + WebView 是异步的，这里用
 * CountDownLatch 做同步等待并带超时 —— 脚本不回调也不会把调用方卡死。
 */
class HtmlSpider(
    key: String?,
    private val api: String?,
    private val extend: String?,
) : Spider() {

    init {
        this.siteKey = key
    }

    override fun init(context: android.content.Context?) {
        // 无状态：每次调用现起 Activity，不需要预加载
    }

    /**
     * 宫格里始终保留一张“启动”卡：html 靠 WebView 跑，页面被误关后首页就空了，
     * 这张卡是这个站点的常驻入口，点它只重新拉起 html，不做详情页跳转。
     */
    override fun homeContent(filter: Boolean): String? {
        val raw = call("homeContent", JSONObject().put("filter", filter))
        return withLaunchCard(raw)
    }

    override fun homeVideoContent(): String? =
        call("homeVideoContent", JSONObject())

    override fun categoryContent(
        tid: String?,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, String>?,
    ): String? {
        val map: Map<String, String> = extend ?: emptyMap()
        return call(
            "categoryContent",
            JSONObject()
                .put("tid", tid ?: "")
                .put("pg", pg)
                .put("filter", filter)
                .put("extend", JSONObject(map).toString()),
        )
    }

    override fun detailContent(ids: List<String>?): String? {
        val list: List<String> = ids ?: emptyList()
        // 点“启动”卡 = 二次拉起 html，不进详情页
        if (list.isNotEmpty() && list[0] == LAUNCH_ID) {
            call("homeContent", JSONObject().put("filter", true))
            return JSONObject().put("list", org.json.JSONArray()).toString()
        }
        return call("detailContent", JSONObject().put("ids", org.json.JSONArray(list)))
    }

    /** 把启动卡挂到宫格末尾：真实内容在前，入口永远在 */
    private fun withLaunchCard(raw: String?): String {
        val base = if (raw.isNullOrBlank()) JSONObject() else try {
            JSONObject(raw)
        } catch (th: Throwable) {
            JSONObject()
        }
        if (!base.has("list")) base.put("list", org.json.JSONArray())
        val arr = base.optJSONArray("list") ?: org.json.JSONArray()
        arr.put(
            JSONObject()
                .put("vod_id", LAUNCH_ID)
                .put("vod_name", LAUNCH_NAME)
                .put("vod_pic", "")
                .put("vod_remarks", "HTML"),
        )
        base.put("list", arr)
        return base.toString()
    }

    override fun searchContent(key: String?, quick: Boolean): String? =
        call("searchContent", JSONObject().put("key", key ?: "").put("quick", quick))

    override fun searchContent(key: String?, quick: Boolean, pg: String?): String? =
        call(
            "searchContent",
            JSONObject().put("key", key ?: "").put("quick", quick).put("pg", pg ?: ""),
        )

    override fun playerContent(flag: String?, id: String, vipFlags: List<String>?): String? {
        val list: List<String> = vipFlags ?: emptyList()
        return call(
            "playerContent",
            JSONObject()
                .put("flag", flag ?: "")
                .put("id", id)
                .put("vipFlags", org.json.JSONArray(list)),
        )
    }

    override fun proxyLocal(params: Map<String, String>?): Array<Any?>? {
        val map: Map<String, String> = params ?: emptyMap()
        val raw = call("localProxy", JSONObject(map)) ?: return null
        return try {
            val o = JSONObject(raw)
            val code = o.optInt("code", 200)
            val type = o.optString("type", "application/octet-stream")
            val body = o.optString("body", "")
            arrayOf<Any?>(code, type, body.toByteArray())
        } catch (th: Throwable) {
            LOG.e(TAG, th)
            null
        }
    }

    override fun destroy() {
        HtmlActivity.finishIfRunning(siteKey)
    }

    private fun call(method: String, args: JSONObject): String? {
        val ctx = AppContextHolder.context() ?: return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<String?>()
        val request = HtmlActivity.Request(
            siteKey = siteKey ?: "",
            api = api ?: "",
            ext = extend ?: "",
            method = method,
            args = args.toString(),
        ) { value ->
            result.set(value)
            latch.countDown()
        }
        Handler(Looper.getMainLooper()).post {
            try {
                // 同一站点复用已存活的页面，不再每次调用都新建 Activity，
                // 否则首页加载（homeContent/homeVideoContent/categoryContent）会反复前后台切换
                if (HtmlActivity.dispatchToLive(request)) return@post
                HtmlActivity.finishIfRunning(null)
                HtmlActivity.pending = request
                ctx.startActivity(
                    android.content.Intent(ctx, HtmlActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
            } catch (th: Throwable) {
                LOG.e(TAG, th)
                latch.countDown()
            }
        }
        val ok = try {
            latch.await(TIMEOUT_SEC, TimeUnit.SECONDS)
        } catch (th: Throwable) {
            false
        }
        if (!ok) LOG.i("$TAG: $method timeout after ${TIMEOUT_SEC}s")
        return result.get()
    }

    private companion object {
        const val TAG = "HtmlSpider"
        const val TIMEOUT_SEC = 30L
        const val LAUNCH_NAME = "启动脚本"
        const val LAUNCH_ID = "__html_launch__"
    }
}
