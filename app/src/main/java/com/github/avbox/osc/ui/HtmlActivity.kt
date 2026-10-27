package com.github.avbox.osc.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.KV
import org.json.JSONObject

/**
 * HTML 站点（type 6）的全屏网页容器。
 *
 * 【范式】对应 appmenu 的 WvWv：页面只是【被打开的应用】，不负责回传数据。
 * 本页职责就一个 —— 把 url 打开并让它正常跑起来。分类宫格由 HtmlSpider 在
 * Java 侧造好，所以首页永远有数据；点宫格只是再打开一次本页。
 *
 * 两个关键点，缺一个就是"只出框架没数据"：
 *   1) 本地 html 走 http://127.0.0.1:9978/file/...（svc 已在 HtmlSpider 侧换算），
 *      不再用 loadDataWithBaseURL 把正文塞进 data 主文档 —— 那样页面 origin
 *      失真，页内 fetch/XHR 全被 CORS 打死。
 *   2) 注入 fetch 补丁，把页内 fetch 接到 FmBridge.req（走 App 网络栈 + 站点代理）。
 *      WebView 自己的网络栈不认 OkHttp 的 proxySelector，不补这层代理形同虚设。
 */
class HtmlActivity : Activity() {

    data class Request(
        val siteKey: String,
        val url: String,
        val proxy: String = "",
        val patchFetch: Boolean = true,
        val interceptAll: Boolean = false,
        val ext: String = "",
    )

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var currentReq: Request? = null
    private var curSiteKey: String? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = pending
        if (req == null) {
            finish()
            return
        }
        pending = null
        currentReq = req
        curSiteKey = req.siteKey
        live = this

        val wv = WebView(this)
        webView = wv
        setContentView(
            wv,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.loadWithOverviewMode = true
        wv.settings.useWideViewPort = true
        // 远程页面常是 https 壳里嵌 http 子资源；不允许混合内容会直接白屏
        @Suppress("DEPRECATION")
        wv.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        wv.settings.javaScriptCanOpenWindowsAutomatically = true
        // 兜底：svc 换算失败退回 file:// 时，页内跨域请求还能过
        @Suppress("DEPRECATION")
        wv.settings.allowUniversalAccessFromFileURLs = true
        wv.addJavascriptInterface(Bridge(req), "FmBridge")

        wv.webViewClient = object : WebViewClient() {

            override fun onPageStarted(
                view: WebView?,
                url: String?,
                favicon: android.graphics.Bitmap?,
            ) {
                // 提前把 fm 挂上：有些页面在顶层脚本里就要用 fm.req，
                // 等 onPageFinished 才注入的话那段代码已经因 fm undefined 崩掉了
                injectApiOnly()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                if (req.patchFetch) injectFetchPatch()
            }

            /**
             * 子资源接到 App 网络栈，让站点代理对 WebView 同样生效。
             * 本地文件服务（127.0.0.1）必须放过 —— 否则页面正文/资源会被自己拦死。
             * 取不到就返回 null，交回 WebView 自己加载，不硬拦。
             */
            override fun shouldInterceptRequest(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
            ): android.webkit.WebResourceResponse? {
                val r = request ?: return null
                if (r.method != "GET") return null
                if (r.isForMainFrame) return null
                val u = r.url?.toString() ?: return null
                if (!u.startsWith("http://") && !u.startsWith("https://")) return null
                if (isLocalService(u)) return null
                val target = if (req.interceptAll) u else u
                return try {
                    val resp = com.github.catvod.net.OkHttp.client()
                        .newCall(okhttp3.Request.Builder().url(target).build())
                        .execute()
                    val type = resp.header("content-type") ?: "application/octet-stream"
                    val mime = type.split(";")[0].trim()
                    android.webkit.WebResourceResponse(
                        mime,
                        charsetOf(type),
                        resp.code,
                        if (resp.message.isNullOrEmpty()) "OK" else resp.message,
                        emptyMap<String, String>(),
                        resp.body?.byteStream(),
                    )
                } catch (th: Throwable) {
                    null
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: android.webkit.WebResourceRequest?,
                error: android.webkit.WebResourceError?,
            ) {
                if (request?.isForMainFrame != false) {
                    AppLog.i("$TAG: load failed: " + req.url + " err=" + (error?.description ?: ""))
                }
            }
        }

        wv.loadUrl(req.url)
    }

    override fun onDestroy() {
        if (live === this) live = null
        currentReq = null
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    /** 只把 fm-api.js 挂进页面，不做其它动作（供 onPageStarted 用） */
    private fun injectApiOnly() {
        val api = readAsset("fm-api.js") ?: return
        val view = webView ?: return
        mainHandler.post {
            try {
                @Suppress("DEPRECATION")
                view.evaluateJavascript(api, null)
            } catch (th: Throwable) {
                AppLog.e(TAG, th)
            }
        }
    }

    /**
     * fetch 补丁：把页内 fetch 接到 fm.req（= FmBridge.req，走 App 网络栈 + 站点代理）。
     *
     * 不做这层的话，页内 fetch 由 WebView 内核直发，拦不到也管不着代理 ——
     * 需要代理的页面就永远取不到数据。原生 fetch 只用于本地/非 http 的兜底。
     */
    private fun injectFetchPatch() {
        val view = webView ?: return
        mainHandler.post {
            try {
                @Suppress("DEPRECATION")
                view.evaluateJavascript(FETCH_PATCH, null)
            } catch (th: Throwable) {
                AppLog.e(TAG, th)
            }
        }
    }

    private fun readAsset(name: String): String? =
        try {
            assets.open(name).bufferedReader().use { it.readText() }
        } catch (th: Throwable) {
            AppLog.e(TAG, th)
            null
        }

    private fun charsetOf(contentType: String?): String {
        if (contentType.isNullOrEmpty()) return "UTF-8"
        val i = contentType.indexOf("charset=")
        if (i < 0) return "UTF-8"
        return contentType.substring(i + "charset=".length)
            .split(";", ",")[0].trim().trim('"').ifEmpty { "UTF-8" }
    }

    private inner class Bridge(private val req: Request) {

        @JavascriptInterface
        fun siteKey(): String = req.siteKey

        @JavascriptInterface
        fun ext(): String = req.ext

        @JavascriptInterface
        fun log(msg: String?) {
            AppLog.i("$TAG[${req.siteKey}]: ${msg ?: ""}")
        }

        /** 同步请求：走 App 网络栈，站点代理开关对它同样生效 */
        @JavascriptInterface
        fun req(url: String?, headers: String?, method: String?, body: String?): String {
            if (url.isNullOrEmpty()) return ""
            return try {
                val client = com.github.catvod.net.OkHttp.client()
                val builder = okhttp3.Request.Builder().url(url)
                if (!headers.isNullOrEmpty()) {
                    val h = JSONObject(headers)
                    val it = h.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        builder.header(k, h.optString(k))
                    }
                }
                val m = (method ?: "GET").uppercase()
                val payload = if (body.isNullOrEmpty()) null
                else okhttp3.RequestBody.create(null, body)
                builder.method(m, payload)
                val resp = client.newCall(builder.build()).execute()
                val text = resp.body?.string() ?: ""
                resp.close()
                text
            } catch (th: Throwable) {
                AppLog.e(TAG, th)
                ""
            }
        }

        @JavascriptInterface
        fun getVar(key: String?): String =
            try {
                KV.get(varKey(req.siteKey, key), "") ?: ""
            } catch (th: Throwable) {
                ""
            }

        @JavascriptInterface
        fun setVar(key: String?, value: String?) {
            try {
                KV.put(varKey(req.siteKey, key), value ?: "")
            } catch (th: Throwable) {
                AppLog.e(TAG, th)
            }
        }

        /** 页面自己要求关闭（fm.close()） */
        @JavascriptInterface
        fun done(result: String?) {
            mainHandler.post { finishNow() }
        }
    }

    private fun finishNow() {
        try {
            finish()
        } catch (ignored: Throwable) {
        }
    }

    companion object {
        private const val TAG = "HtmlActivity"

        @Volatile
        var pending: Request? = null

        @Volatile
        var live: HtmlActivity? = null

        /**
         * 同一站点的页面还活着 → 不重复拉起（首页多次调用不会反复弹页面）。
         * 页面被关闭后 live 为空，再点宫格会正常新建 —— 这就是"误关后能重新打开"。
         */
        @JvmStatic
        fun dispatchToLive(req: Request): Boolean {
            val act = live ?: return false
            if (act.isFinishing || act.isDestroyed) {
                live = null
                return false
            }
            if (act.curSiteKey != req.siteKey) return false
            return true
        }

        /** 立刻收掉页面（切站点 / spider 销毁时） */
        @JvmStatic
        fun finishIfRunning(siteKey: String?) {
            pending = null
            val act = live ?: return
            live = null
            act.mainHandler.post { act.finishNow() }
        }

        private fun isLocalService(u: String): Boolean =
            u.contains("127.0.0.1") || u.contains("localhost") || u.contains("[::1]")

        private fun varKey(siteKey: String, key: String?): String =
            "html_var_" + siteKey + "_" + (key ?: "")

        /**
         * fetch 补丁（对应 appmenu WvWv 的 FetchPatch）。
         * 页内 fetch → fm.req（Java OkHttp，走站点代理）；失败退回原生 fetch。
         */
        private const val FETCH_PATCH = """
(function(){
  if (window.__fmFetchPatched) return;
  var NF = window.fetch;
  if (!NF) return;
  window.__fmFetchPatched = true;
  var NFB = NF.bind(window);
  function toUrl(i){ return (typeof i === 'string') ? i : ((i && i.url) || ''); }
  function ok(u){
    if (!u) return false;
    if (!/^https?:\/\//i.test(u)) return false;
    if (/127\.0\.0\.1|localhost|\[::1\]/i.test(u)) return false;
    return true;
  }
  function hdrs(h){
    var o = {};
    try {
      if (!h) return o;
      if (typeof Headers !== 'undefined' && h instanceof Headers) {
        h.forEach(function(v,k){ o[k] = v; });
        return o;
      }
      if (Object.prototype.toString.call(h) === '[object Array]') {
        for (var i=0;i<h.length;i++) o[h[i][0]] = h[i][1];
        return o;
      }
      for (var k in h) { if (Object.prototype.hasOwnProperty.call(h,k)) o[k] = h[k]; }
    } catch(e) {}
    return o;
  }
  function mk(txt, u){
    try {
      return new Response(txt, {status:200, statusText:'OK', headers:{'content-type':'text/plain;charset=utf-8'}});
    } catch(e) {
      return { ok:true, status:200, url:u, text:function(){ return Promise.resolve(txt); },
               json:function(){ try { return Promise.resolve(JSON.parse(txt)); } catch(e2){ return Promise.reject(e2); } },
               headers:{ get:function(){ return null; } } };
    }
  }
  window.fetch = function(input, init){
    var u = toUrl(input);
    if (!ok(u)) return NFB(input, init);
    var src = init || ((typeof input === 'object' && input) ? input : null);
    var m = 'GET', h = null, b = null;
    if (src) { m = (src.method || 'GET').toUpperCase(); h = src.headers; b = src.body; }
    /* 非字符串 body（FormData/Blob/Stream）不接管，避免转码失真 */
    if (b != null && typeof b !== 'string') return NFB(input, init);
    try {
      var txt = window.fm.req(u, { method:m, headers:hdrs(h), body:(b == null ? '' : b) });
      return Promise.resolve(mk(txt, u));
    } catch(e) {
      return NFB(input, init);
    }
  };
})();
"""
    }
}
