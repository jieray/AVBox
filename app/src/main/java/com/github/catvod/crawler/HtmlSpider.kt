package com.github.catvod.crawler

import android.os.Handler
import android.os.Looper
import com.github.avbox.osc.ui.HtmlActivity
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.AppLog as LOG

/**
 * HTML 脚本爬虫（站点 type 6）。
 *
 * 【范式：与 appmenu 的 WvWv 一致，不再让 html 当爬虫】
 * html 页面只是【被打开的应用】，分类与宫格由 Java 侧自己造：
 *
 *   · homeContent    按 ext（items / dir / root）或站点 api 造分类 + 宫格，
 *                    【不起 WebView】—— 所以首页一定能出数据，不会"加载失败"
 *   · detailContent  才起全屏 WebView 打开页面，然后返回空列表 → 不进详情页，
 *                    点宫格的效果就是"打开这个网页"
 *   · playerContent  恒 {"parse":0,"url":""}
 *
 * 这么改的原因：旧实现起 WebView 跑脚本、等它调 fm.done() 回传数据，而市面上的
 * html 几乎没有这么写的，结果就是页面出得来、数据永远为空（"只出框架没数据"）。
 *
 * 页面被误关后，首页宫格还在，再点一次即可重新打开 —— 宫格就是这个站点的常驻入口。
 */
class HtmlSpider(
    key: String?,
    private val api: String?,
    private val extend: String?,
) : Spider() {

    private var cfg: HtmlConfig? = null

    init {
        this.siteKey = key
    }

    override fun init(context: android.content.Context?) {
        cfg = HtmlConfig.parse(extend, api, siteKey)
    }

    private fun config(): HtmlConfig {
        if (cfg == null) cfg = HtmlConfig.parse(extend, api, siteKey)
        return cfg!!
    }

    override fun homeContent(filter: Boolean): String {
        val c = config()
        // direct：进站点即直接打开页面，不造宫格（ext 里显式指定时才走这条）
        if (c.direct) {
            val u = c.directUrl.ifEmpty { api ?: "" }
            open(u, if (c.directProxyOn) c.proxy else "")
            return EMPTY_CLASS_LIST
        }
        val cates = c.categories()
        val first = if (cates.isEmpty()) "0" else c.catId(cates[0])
        return c.grid(first)
    }

    override fun homeVideoContent(): String = EMPTY_LIST

    override fun categoryContent(
        tid: String?,
        pg: String,
        filter: Boolean,
        extend: HashMap<String, String>?,
    ): String = config().grid(tid ?: "0")

    override fun detailContent(ids: List<String>?): String {
        val c = config()
        val id = ids?.firstOrNull() ?: return EMPTY_LIST
        val e = c.byId(id) ?: return EMPTY_LIST
        if (e.url.isEmpty()) return EMPTY_LIST
        // 打开页面后【返回空列表】：宿主因此不会进详情页，点宫格 = 打开网页
        open(e.url, c.proxyFor(e))
        if (c.detailMode == "full") return c.fullDetail(e)
        return EMPTY_LIST
    }

    override fun searchContent(key: String?, quick: Boolean): String = ""

    override fun searchContent(key: String?, quick: Boolean, pg: String?): String = ""

    override fun playerContent(flag: String?, id: String, vipFlags: List<String>?): String =
        EMPTY_PLAYER

    override fun proxyLocal(params: Map<String, String>?): Array<Any?>? = null

    override fun destroy() {
        HtmlActivity.finishIfRunning(siteKey)
    }

    /**
     * 起全屏 WebView 打开页面。
     *
     * 与旧实现不同：这里【不等待】页面回传结果（页面也不需要回传），
     * 所以不存在"脚本不回调就超时返回空"的问题 —— 打开就算完成。
     */
    private fun open(rawUrl: String, proxy: String) {
        val url = HtmlConfig.svc(rawUrl)
        if (url.isEmpty()) return
        val c = config()
        val req = HtmlActivity.Request(
            siteKey = siteKey ?: "",
            url = url,
            proxy = proxy,
            patchFetch = c.patchFetch,
            interceptAll = c.interceptAll,
            ext = extend ?: "",
        )
        Handler(Looper.getMainLooper()).post {
            try {
                // 同一页面正在显示就不重复拉起（防止首页多次调用反复弹）
                if (HtmlActivity.dispatchToLive(req)) return@post
                HtmlActivity.finishIfRunning(null)
                HtmlActivity.pending = req
                val starter = resolveStarter() ?: return@post
                val launch = android.content.Intent(starter, HtmlActivity::class.java)
                if (starter !is android.app.Activity) {
                    launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                starter.startActivity(launch)
            } catch (th: Throwable) {
                LOG.e(TAG, th)
            }
        }
    }

    /**
     * 取栈顶 Activity 作为启动上下文。拿不到才退回 Application。
     * 用 Application + NEW_TASK 会让系统去复用/另起 task，本 task 还在前台时
     * 这次启动常被压在后面 —— 表现就是 html 页面被首页遮住。
     */
    private fun resolveStarter(): android.content.Context? {
        val stack = try {
            com.github.avbox.osc.util.ActivityStack.getInstance()
        } catch (th: Throwable) {
            null
        }
        if (stack != null) {
            try {
                val top = stack.currentActivity()
                if (!top.isFinishing && !top.isDestroyed) return top
            } catch (th: Throwable) {
                // 栈为空等，走下面的兜底
            }
        }
        return AppContextHolder.context()
    }

    private companion object {
        const val TAG = "HtmlSpider"
        const val EMPTY_LIST = "{\"list\":[]}"
        const val EMPTY_CLASS_LIST = "{\"class\":[],\"list\":[]}"
        const val EMPTY_PLAYER = "{\"parse\":0,\"url\":\"\"}"
    }
}
