package com.github.catvod.crawler.js

import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import android.util.Base64
import android.webkit.WebSettings

import androidx.annotation.Keep

import com.github.catvod.Proxy
import com.github.catvod.crawler.js.rsa.RSAEncrypt
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.AppLog
import com.github.avbox.osc.util.MD5
import com.github.avbox.osc.util.UserAgentPool
import com.whl.quickjs.wrapper.ContextSetter
import com.whl.quickjs.wrapper.Function
import com.whl.quickjs.wrapper.JSArray
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSFunction
import com.whl.quickjs.wrapper.JSObject
import com.whl.quickjs.wrapper.JSUtils
import com.whl.quickjs.wrapper.QuickJSContext

import java.io.IOException
import java.net.URLEncoder
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response

class Global {

    private var runtime: QuickJSContext? = null

    @Volatile
    private var siteKey: String = ""

    @JvmField
    var executor: ExecutorService

    private val timer: Timer

    private val httpTag: String

    private val streams: MutableSet<OpenStream> = ConcurrentHashMap.newKeySet()

    private val proxyStreams: ConcurrentHashMap<Int, StreamEntry> = ConcurrentHashMap()

    private val sessions: MutableSet<Int> = ConcurrentHashMap.newKeySet()

    private val streamTokens: AtomicInteger = AtomicInteger()

    private val httpGate: HttpGate

    constructor(executor: ExecutorService) : this(executor, "default")

    constructor(executor: ExecutorService, key: String) {
        this.executor = executor
        this.httpTag = "js_okhttp_tag_" + key
        this.timer = Timer("js-spider-timer", true)
        this.httpGate = HttpGate(executor)
    }

    fun getHttpTag(): String {
        return httpTag
    }

    @Keep
    @Function
    fun getProxy(local: Boolean): String {
        return Proxy.getUrl(local) + "?do=js"
    }

    @Keep
    @Function
    fun js2Proxy(dynamic: Boolean?, siteType: Int?, siteKey: String?, url: String?, headers: JSObject): String {
        val local = dynamic == null || !dynamic
        return getProxy(local) + "&from=catvod" + "&siteType=" + siteType + "&siteKey=" + siteKey + "&header=" + URLEncoder.encode(headers.stringify()) + "&url=" + URLEncoder.encode(url)
    }

    @Keep
    @Function(name = "_getUserAgent")
    fun getUserAgent(): String {
        return resolveUserAgent()
    }

    @Keep
    @Function(name = "_getCookie")
    fun getCookie(url: String): String {
        return WebViewCookies.getCookie(url)
    }

    @Keep
    @Function(name = "_setCookie")
    fun setCookie(url: String, cookie: String): Boolean {
        return WebViewCookies.setCookie(url, cookie)
    }

    @Keep
    @Function(name = "_session")
    fun createSession(): Int {
        val id = JsSession.create()
        sessions.add(id)
        return id
    }

    @Keep
    @Function(name = "_sessionClose")
    fun closeSession(token: Int): Any? {
        if (!sessions.remove(token)) return null
        JsSession.close(token)
        closeSessionStreams(token)
        return null
    }

    @Keep
    @Function(name = "_sessionGetCookie")
    fun sessionGetCookie(token: Int, url: String): String {
        if (!sessions.contains(token)) return ""
        return JsSession.find(token)?.getCookie(url).orEmpty()
    }

    @Keep
    @Function(name = "_sessionSetCookie")
    fun sessionSetCookie(token: Int, url: String, cookie: String): Boolean {
        if (!sessions.contains(token)) return false
        return JsSession.find(token)?.setCookie(url, cookie) ?: false
    }

    @Keep
    @Function
    fun getProxyUrl(options: JSObject?): String {
        val url = JSUtils.toJsonObject(options).optString("url")
        if (url.isEmpty()) return ""
        val builder = StringBuilder(getProxy(true))
        if (siteKey.isNotEmpty()) builder.append("&siteKey=").append(URLEncoder.encode(siteKey, "UTF-8"))
        builder.append("&url=").append(URLEncoder.encode(url, "UTF-8"))
        return builder.toString()
    }

    fun setSiteKey(value: String?) {
        siteKey = value.orEmpty()
    }

    @Keep
    @Function
    fun joinUrl(parent: String?, child: String): String {
        return HtmlParser.joinUrl(parent, child)
    }

    @Keep
    @Function
    fun pd(html: String, rule: String, add_url: String): String {
        return HtmlParser.parseDomForUrl(html, rule, add_url)
    }

    @Keep
    @Function
    fun pdfh(html: String, rule: String): String {
        return HtmlParser.parseDomForUrl(html, rule, "")
    }

    @Keep
    @Function
    fun pdfa(html: String, rule: String): JSArray {

        return JSUtils<String>().toArray(requireNotNull(runtime) { "js runtime not ready" }, HtmlParser.parseDomForArray(html, rule))
    }

    @Keep
    @Function
    fun pdfla(html: String, p1: String, list_text: String, list_url: String, add_url: String): JSArray {
        return JSUtils<String>().toArray(requireNotNull(runtime) { "js runtime not ready" }, HtmlParser.parseDomForList(html, p1, list_text, list_url, add_url))
    }

    @Keep
    @Function
    fun md5X(text: String?): String {
        return try {
            MD5.encode(text ?: "") ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    @Keep
    @Function
    fun base64Encode(text: String?): String {
        return try {
            Base64.encodeToString((text ?: "").toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }

    @Keep
    @Function
    fun base64Decode(text: String?): String {
        return try {
            String(Base64.decode(text ?: "", Base64.DEFAULT))
        } catch (e: Exception) {
            ""
        }
    }

    @Keep
    @Function
    fun s2t(text: String?): String? {
        try {
            return Trans.s2t(false, text)
        } catch (e: Exception) {
            return ""
        }
    }

    @Keep
    @Function
    fun t2s(text: String?): String? {
        try {
            return Trans.t2s(false, text)
        } catch (e: Exception) {
            return ""
        }
    }

    @Keep
    @Function
    fun aesX(mode: String?, encrypt: Boolean, input: String?, inBase64: Boolean, key: String?, iv: String?, outBase64: Boolean): String {
        val result = Crypto.aes(mode, encrypt, input, inBase64, key, iv, outBase64)
        return result
    }

    @Keep
    @Function
    fun rsaX(mode: String?, pub: Boolean, encrypt: Boolean, input: String?, inBase64: Boolean, key: String?, outBase64: Boolean): String {
        val result = Crypto.rsa(pub, encrypt, input, inBase64, key, outBase64)
        return result
    }

    @Keep
    @Function
    fun rsaEncrypt(data: String?, key: String?): String? {
        return rsaEncrypt(data, key, null)
    }

    @Keep
    @Function
    fun rsaEncrypt(data: String?, key: String?, options: JSObject?): String? {
        var mLong = 1
        var mType = 1
        var mBlock = true
        var mConfig: String? = null
        if (options != null) {
            val op = JSUtils.toJsonObject(options)
            if (op.has("config")) {
                try {
                    mConfig = op.get("config") as String?
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
            if (op.has("type")) {
                try {
                    mType = (op.get("type") as Double).toInt()
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
            if (op.has("long")) {
                try {
                    mLong = (op.get("long") as Double).toInt()
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
            if (op.has("block")) {
                try {
                    mBlock = op.get("block") as Boolean
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
        }
        try {
            return when (mType) {
                1 -> {
                    if (mConfig != null) {
                        RSAEncrypt.encryptByPublicKey(data, key, mConfig, mLong, mBlock)
                    } else {
                        RSAEncrypt.encryptByPublicKey(data, key, mLong, mBlock)
                    }
                }

                2 -> {
                    if (mConfig != null) {
                        RSAEncrypt.encryptByPrivateKey(data, key, mConfig, mLong, mBlock)
                    } else {
                        RSAEncrypt.encryptByPrivateKey(data, key, mLong, mBlock)
                    }
                }

                else -> ""
            }
        } catch (e: Exception) {
            return ""
        }
    }

    @Keep
    @Function
    fun rsaDecrypt(encryptBase64Data: String?, key: String?): String? {
        return rsaDecrypt(encryptBase64Data, key, null)
    }

    @Keep
    @Function
    fun rsaDecrypt(encryptBase64Data: String?, key: String?, options: JSObject?): String? {
        var mLong = 1
        var mType = 1
        var mBlock = true
        var mConfig: String? = null
        if (options != null) {
            val op = JSUtils.toJsonObject(options)
            if (op.has("config")) {
                try {
                    mConfig = op.get("config") as String?
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
            if (op.has("type")) {
                try {
                    mType = (op.get("type") as Double).toInt()
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
            if (op.has("long")) {
                try {
                    mLong = (op.get("long") as Double).toInt()
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
            if (op.has("block")) {
                try {
                    mBlock = op.get("block") as Boolean
                } catch (e: Exception) {
                    AppLog.e("Global", e)
                }
            }
        }
        try {
            return when (mType) {
                1 -> {
                    if (mConfig != null) {
                        RSAEncrypt.decryptByPrivateKey(encryptBase64Data, key, mConfig, mLong, mBlock)
                    } else {
                        RSAEncrypt.decryptByPrivateKey(encryptBase64Data, key, mLong, mBlock)
                    }
                }

                2 -> {
                    if (mConfig != null) {
                        RSAEncrypt.decryptByPublicKey(encryptBase64Data, key, mConfig, mLong, mBlock)
                    } else {
                        RSAEncrypt.decryptByPublicKey(encryptBase64Data, key, mLong, mBlock)
                    }
                }

                else -> ""
            }
        } catch (e: Exception) {
            return ""
        }
    }

    private fun req(url: String, options: JSObject): JSObject {
        val ctx = requireNotNull(runtime) { "js runtime not ready" }
        try {
            val req = Req.objectFrom(JSUtils.toJsonObject(options).toString())
            requireSession(req)
            val res = Connect.to(url, req, httpTag).execute()
            return Connect.success(ctx, req, res)
        } catch (e: Exception) {
            return Connect.error(ctx)
        }
    }

    private fun requireSession(req: Req) {
        val id = req.getSessionId() ?: return
        if (!sessions.contains(id)) throw IllegalStateException("session closed")
    }

    @Keep
    @Function
    fun _http(url: String, options: JSObject): JSObject? {
        val complete = options.getJSFunction("complete")
        if (complete == null) return req(url, options)
        val ctx = runtime
        return try {
            val req = Req.objectFrom(JSUtils.toJsonObject(options).toString())
            requireSession(req)
            httpGate.dispatch(HttpGate.Request(
                start = Runnable {
                    try {
                        Connect.to(url, req, httpTag).enqueue(getCallback(complete, req, ctx))
                    } catch (th: Throwable) {
                        httpGate.finish()
                        deliver(ctx, complete, req, null, th.message.orEmpty())
                    }
                },
                fail = { message -> deliver(ctx, complete, req, null, message) }
            ))
            null
        } catch (th: Throwable) {
            AppLog.e("Global", th)
            deliver(ctx, complete, null, null, th.message.orEmpty())
            null
        }
    }

    @Keep
    @Function(name = "_openStream")
    fun openStream(url: String, options: JSObject): JSObject? {
        val ctx = runtime ?: return null
        var opened: OpenStream? = null
        return try {
            val req = Req.objectFrom(JSUtils.toJsonObject(options).toString())
            requireSession(req)
            val call = Connect.to(url, req, httpTag)
            val response = call.execute()
            val stream = OpenStream(call, response, req.getSessionId())
            opened = stream
            streams.add(stream)
            val obj = ctx.createNewJSObject()
            val headers = ctx.createNewJSObject()
            Connect.setHeader(ctx, response.headers, headers)
            ctx.setProperty(obj, "code", response.code)
            ctx.setProperty(obj, "headers", headers)
            ctx.setProperty(obj, "contentType", response.header("Content-Type") ?: "application/octet-stream")
            ctx.setProperty(obj, "read", object : JSCallFunction {
                override fun call(vararg args: Any?): Any? {
                    val size = (args.firstOrNull() as? Number)?.toInt() ?: DEFAULT_CHUNK
                    if (size !in 1..MAX_CHUNK) return null
                    val bytes = stream.read(size)
                    if (bytes == null) streams.remove(stream)
                    return bytes
                }
            })
            ctx.setProperty(obj, "close", object : JSCallFunction {
                override fun call(vararg args: Any?): Any? {
                    streams.remove(stream)
                    return stream.close()
                }
            })
            obj
        } catch (th: Throwable) {
            AppLog.e("Global", th)
            opened?.let {
                streams.remove(it)
                it.close()
            }
            null
        }
    }

    @Keep
    @Function(name = "_stream")
    fun stream(url: String, options: JSObject, resolve: JSFunction?, reject: JSFunction?): Any? {
        val ctx = runtime
        if (ctx == null || resolve == null || reject == null) return null
        val reply = StreamReply(resolve, reject)
        reply.hold()
        val req = try {
            Req.objectFrom(JSUtils.toJsonObject(options).toString())
        } catch (th: Throwable) {
            settleStream(reply, null, th.message.orEmpty())
            return null
        }
        try {
            requireSession(req)
            val call = Connect.to(url, req, httpTag)
            httpGate.dispatch(HttpGate.Request(
                start = Runnable {
                    try {
                        call.enqueue(streamCallback(ctx, reply, req.getSessionId()))
                    } catch (th: Throwable) {
                        httpGate.finish()
                        settleStream(reply, null, th.message.orEmpty())
                    }
                },
                fail = { message -> settleStream(reply, null, message) }
            ))
        } catch (th: Throwable) {
            AppLog.e("Global", th)
            settleStream(reply, null, th.message.orEmpty())
        }
        return null
    }

    @Keep
    @Function(name = "_streamClose")
    fun streamClose(token: Int): Boolean {
        val entry = proxyStreams.remove(token) ?: return false
        entry.stream.close()
        AppLog.i("echo-js-stream close " + token)
        return true
    }

    fun consumeStream(token: Int): ProxyStream? {
        return proxyStreams.remove(token)?.stream
    }

    private fun settleStream(reply: StreamReply, result: JSObject?, message: String) {
        if (!reply.take()) return
        try {
            executor.submit(Runnable {
                try {
                    if (result != null) reply.ok(result) else reply.fail(message)
                } catch (th: Throwable) {
                    AppLog.e("Global", th)
                } finally {
                    reply.release()
                }
            })
        } catch (th: Throwable) {
            AppLog.d("Global", "executor shutdown, drop stream callback")
            reply.release()
        }
    }

    private fun streamCallback(ctx: QuickJSContext, reply: StreamReply, sessionId: Int?): Callback {
        return object : Callback {
            override fun onResponse(call: Call, response: Response) {
                httpGate.finish()
                if (executor.isShutdown) {
                    response.close()
                    settleStream(reply, null, "executor shutdown")
                    return
                }
                try {
                    executor.submit(Runnable { deliverStream(ctx, response, reply, sessionId) })
                } catch (th: Throwable) {
                    response.close()
                    settleStream(reply, null, th.message.orEmpty())
                }
            }

            override fun onFailure(call: Call, e: IOException) {
                httpGate.finish()
                settleStream(reply, null, e.message.orEmpty())
            }
        }
    }

    private fun deliverStream(ctx: QuickJSContext, response: Response, reply: StreamReply, sessionId: Int?) {
        try {
            sweepProxyStreams()
            val token = streamTokens.incrementAndGet()
            val headers = ctx.createNewJSObject()
            Connect.setHeader(ctx, response.headers, headers)
            val obj = ctx.createNewJSObject()
            ctx.setProperty(obj, "code", response.code)
            ctx.setProperty(obj, "headers", headers)
            ctx.setProperty(obj, "contentType", response.header("Content-Type") ?: "application/octet-stream")
            val handle = ctx.createNewJSObject()
            ctx.setProperty(handle, "__token", token)
            ctx.setProperty(handle, "close", object : JSCallFunction {
                override fun call(vararg args: Any?): Any? = streamClose(token)
            })
            obj.setProperty("stream", handle)
            proxyStreams[token] = StreamEntry(ProxyStream(response), SystemClock.elapsedRealtime(), sessionId)
            AppLog.i("echo-js-stream open " + token + " code=" + response.code)
            settleStream(reply, obj, "")
        } catch (th: Throwable) {
            AppLog.e("Global", th)
            response.close()
            settleStream(reply, null, th.message.orEmpty())
        }
    }

    private fun sweepProxyStreams() {
        val now = SystemClock.elapsedRealtime()
        for ((token, entry) in proxyStreams) {
            if (now - entry.created < STREAM_MAX_IDLE_MS) continue
            if (proxyStreams.remove(token, entry)) entry.stream.close()
        }
    }

    @Keep
    @Function(name = "_htmlQuery")
    fun htmlQuery(html: String, plan: JSObject): JSObject? {
        val ctx = runtime ?: return null
        return try {
            val json = HtmlQuery.query(html, plan.stringify())
            ctx.parse(json) as? JSObject
        } catch (th: Throwable) {
            AppLog.e("Global", th)
            null
        }
    }

    @Keep
    @Function
    fun setTimeout(func: JSFunction, delay: Int?) {
        func.hold()
        timer.schedule(object : TimerTask() {
            override fun run() {
                if (!executor.isShutdown) {
                    try {
                        executor.submit(Runnable {
                            try {
                                func.call()
                            } finally {
                                func.release()
                            }
                        })
                    } catch (e: RejectedExecutionException) {
                        func.release()
                    }
                } else {
                    func.release()
                }
            }
        }, requireNotNull(delay) { "delay is null" }.toLong())
    }

    /**
     * T4 / 猫影视系 dialog API —— 同步阻塞版。
     *
     * 脚本写法：const r = await dialog({title, message, inputs:[...], buttons:[...], onAction})
     * 返回：{ button: '点了哪个按钮', values: { 输入框名: 值 } }
     *
     * 之所以做成同步阻塞：QuickJS 的 Promise 要靠 job 队列驱动，走真正的异步
     * 很容易让 await 永远挂住。这里由 JS 线程阻塞等待，等待期间周期性驱动
     * pending job，onAction 里的 await 能继续往下跑；而 await 一个普通对象会
     * 立刻返回，脚本写法不用改。
     *
     * 窗口在主线程弹出、结果回到 JS 线程，两边靠队列交接，不会互相卡死。
     */
    @Keep
    @Function
    fun dialog(args: JSObject?): Any? {
        val ctx = runtime ?: return null
        // 主线程被占住时 post 出去的弹窗永远没机会执行，直接放弃而不是死锁
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AppLog.i("Global: dialog ignored on main thread")
            return null
        }
        val act = try {
            com.github.avbox.osc.util.ActivityStack.getInstance().currentActivity()
        } catch (th: Throwable) {
            null
        }
        if (act == null || act.isFinishing || act.isDestroyed) return null
        val spec = try {
            if (args == null) org.json.JSONObject() else JSUtils.toJsonObject(args)
        } catch (th: Throwable) {
            org.json.JSONObject()
        }
        val onAction = try {
            args?.getJSFunction("onAction")
        } catch (th: Throwable) {
            null
        }
        val queue = LinkedBlockingQueue<DialogEvent>()
        val main = Handler(Looper.getMainLooper())
        val edits = ArrayList<Pair<String, android.widget.EditText>>()
        val msgView = AtomicReference<android.widget.TextView?>(null)
        val dlgRef = AtomicReference<android.app.AlertDialog?>(null)

        main.post {
            try {
                val pad = (20 * act.resources.displayMetrics.density).toInt()
                val wrap = android.widget.LinearLayout(act)
                wrap.orientation = android.widget.LinearLayout.VERTICAL
                wrap.setPadding(pad, pad / 2, pad, 0)
                val tv = android.widget.TextView(act)
                tv.text = spec.optString("message")
                tv.setTextColor(0xFFCCCCCC.toInt())
                wrap.addView(tv)
                msgView.set(tv)

                val inputs = spec.optJSONArray("inputs")
                val inputCount = inputs?.length() ?: 0
                for (i in 0 until inputCount) {
                    val item = inputs!!.opt(i)
                    val label = dialogLabel(item, i)
                    val et = android.widget.EditText(act)
                    et.hint = label
                    et.setSingleLine(true)
                    et.setTextColor(0xFFFFFFFF.toInt())
                    et.setHintTextColor(0xFF888888.toInt())
                    if (dialogIsPassword(item)) {
                        et.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                            android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                    }
                    wrap.addView(et)
                    edits.add(Pair(label, et))
                }

                val buttons = spec.optJSONArray("buttons")
                val buttonCount = buttons?.length() ?: 0
                val names = ArrayList<String>()
                for (i in 0 until buttonCount) names.add(dialogLabel(buttons!!.opt(i), i))
                if (names.isEmpty()) {
                    names.add("确定")
                    names.add("取消")
                }
                val fire: (String) -> Unit = { which ->
                    val vals = LinkedHashMap<String, String>()
                    for (entry in edits) vals[entry.first] = entry.second.text.toString()
                    queue.offer(DialogEvent(which, vals, false))
                }
                val builder = android.app.AlertDialog.Builder(act)
                    .setTitle(spec.optString("title"))
                    .setView(wrap)
                if (names.size > 0) builder.setPositiveButton(names[0]) { _, _ -> fire(names[0]) }
                if (names.size > 1) builder.setNegativeButton(names[1]) { _, _ -> fire(names[1]) }
                if (names.size > 2) builder.setNeutralButton(names[2]) { _, _ -> fire(names[2]) }
                builder.setOnCancelListener { queue.offer(DialogEvent(null, emptyMap(), true)) }
                dlgRef.set(builder.show())
            } catch (th: Throwable) {
                AppLog.e("Global", th)
                queue.offer(DialogEvent(null, emptyMap(), true))
            }
        }

        val deadline = System.currentTimeMillis() + DIALOG_TIMEOUT_MS
        while (true) {
            drainPendingJobs(ctx)
            val ev = queue.poll(200, TimeUnit.MILLISECONDS)
            if (ev == null) {
                if (System.currentTimeMillis() > deadline) {
                    main.post { dismissQuietly(dlgRef) }
                    return null
                }
                continue
            }
            if (ev.dismissed || ev.button == null) {
                main.post { dismissQuietly(dlgRef) }
                return null
            }
            if (onAction == null) {
                main.post { dismissQuietly(dlgRef) }
                return dialogResult(ctx, ev.button, ev.values)
            }
            val msg = try {
                messageOf(onAction.call(dialogResult(ctx, ev.button, ev.values)))
            } catch (th: Throwable) {
                AppLog.e("Global", th)
                ""
            }
            // 脚本返回 {msg} 表示还要继续交互：更新提示，窗口保持打开
            if (msg.isNotEmpty()) {
                main.post { msgView.get()?.text = msg }
                continue
            }
            main.post { dismissQuietly(dlgRef) }
            return dialogResult(ctx, ev.button, ev.values)
        }
    }

    private fun dismissQuietly(ref: AtomicReference<android.app.AlertDialog?>) {
        try {
            ref.get()?.dismiss()
        } catch (th: Throwable) {
            // 窗口已经没了，忽略
        }
    }

    private fun dialogResult(ctx: QuickJSContext, button: String?, values: Map<String, String>): JSObject {
        val obj = ctx.createNewJSObject()
        val vals = ctx.createNewJSObject()
        for ((key, value) in values) ctx.setProperty(vals, key, value)
        ctx.setProperty(obj, "values", vals)
        if (button != null) ctx.setProperty(obj, "button", button)
        return obj
    }

    /** 输入框名 / 按钮文案：既认字符串，也认对象里的 name/title/label 等字段 */
    private fun dialogLabel(item: Any?, index: Int): String {
        if (item is org.json.JSONObject) {
            for (k in arrayOf("name", "title", "label", "text", "placeholder", "hint", "key")) {
                val v = item.optString(k)
                if (v.isNotEmpty()) return v
            }
        } else if (item != null) {
            val s = item.toString()
            if (s.isNotEmpty()) return s
        }
        return "input$index"
    }

    private fun dialogIsPassword(item: Any?): Boolean {
        if (item !is org.json.JSONObject) return false
        val type = item.optString("type").lowercase()
        return type.contains("password") || type.contains("pwd")
    }

    private fun messageOf(ret: Any?): String {
        val jo = try {
            JSUtils.toJsonObject(ret as? JSObject)
        } catch (th: Throwable) {
            null
        } ?: return ""
        return jo.optString("msg", jo.optString("message", ""))
    }

    /** 驱动 QuickJS 的 Promise 任务队列，让 onAction 里的 await 能继续往下跑 */
    private fun drainPendingJobs(ctx: QuickJSContext) {
        try {
            val method = ctx.javaClass.getMethod("executePendingJob")
            for (i in 0 until 64) {
                val r = method.invoke(ctx)
                val n = when (r) {
                    is Int -> r
                    is Long -> r.toInt()
                    is Boolean -> if (r) 1 else 0
                    else -> 0
                }
                if (n <= 0) break
            }
        } catch (th: Throwable) {
            // 引擎没有这个能力就算了，不影响窗口本身
        }
    }

    private class DialogEvent(
        val button: String?,
        val values: Map<String, String>,
        val dismissed: Boolean,
    )

    private fun closeSessionStreams(token: Int) {
        for (stream in streams) {
            if (stream.sessionId != token) continue
            if (streams.remove(stream)) stream.close()
        }
        for ((key, entry) in proxyStreams) {
            if (entry.sessionId != token) continue
            if (proxyStreams.remove(key, entry)) entry.stream.close()
        }
    }

    fun destroy() {
        for (token in sessions) {
            JsSession.close(token)
            closeSessionStreams(token)
        }
        sessions.clear()
        timer.cancel()
        httpGate.clear()
        for (stream in streams) stream.close()
        streams.clear()
        for (entry in proxyStreams.values) entry.stream.close()
        proxyStreams.clear()
        runtime = null
    }

    fun cancelPending() {
        httpGate.cancelPending()
        for (token in sessions) JsSession.find(token)?.cancelCalls()
    }

    private fun getCallback(complete: JSFunction, req: Req, ctx: QuickJSContext?): Callback {
        return object : Callback {
            override fun onResponse(call: Call, response: Response) {
                var result: Connect.Result? = null
                var message = ""
                try {
                    result = Connect.read(response)
                } catch (th: Throwable) {
                    message = th.message.orEmpty()
                }
                httpGate.finish()
                if (executor.isShutdown) return
                deliver(ctx, complete, req, result, message)
            }

            override fun onFailure(call: Call, e: IOException) {
                httpGate.finish()
                if (executor.isShutdown) return
                deliver(ctx, complete, req, null, e.message.orEmpty())
            }
        }
    }

    private fun deliver(ctx: QuickJSContext?, complete: JSFunction, req: Req?, result: Connect.Result?, message: String) {
        if (ctx == null) return
        try {
            executor.submit(Runnable {
                try {
                    val obj = if (result != null && req != null) Connect.success(ctx, req, result) else Connect.error(ctx, message)
                    complete.call(obj)
                } catch (th: Throwable) {
                    AppLog.e("Global", th)
                }
            })
        } catch (ignored: RejectedExecutionException) {
            AppLog.d("Global", "executor shutdown, drop callback")
        }
    }

    @Keep
    @ContextSetter
    fun setJSContext(runtime: QuickJSContext) {
        this.runtime = runtime
    }

    private class StreamEntry(val stream: ProxyStream, val created: Long, val sessionId: Int?)

    private class StreamReply(private val resolve: JSFunction, private val reject: JSFunction) {

        private val settled = AtomicBoolean(false)

        fun hold() {
            resolve.hold()
            reject.hold()
        }

        fun take(): Boolean = settled.compareAndSet(false, true)

        fun ok(result: JSObject) {
            resolve.call(result)
        }

        fun fail(message: String) {
            reject.call(message)
        }

        fun release() {
            try {
                resolve.release()
            } catch (th: Throwable) {
                AppLog.d("Global", "release stream resolve failed")
            }
            try {
                reject.release()
            } catch (th: Throwable) {
                AppLog.d("Global", "release stream reject failed")
            }
        }
    }

    companion object {

        @Volatile
        private var userAgent: String = ""

        private fun resolveUserAgent(): String {
            val cached = userAgent
            if (cached.isNotEmpty()) return cached
            val value = try {
                WebSettings.getDefaultUserAgent(requireNotNull(AppContextHolder.context()) { "app context not initialized" })
            } catch (th: Throwable) {
                AppLog.e("Global", th)
                UserAgentPool.random()
            }
            userAgent = value
            return value
        }

        private const val DIALOG_TIMEOUT_MS = 300_000L

        private const val DEFAULT_CHUNK = 65536

        private const val MAX_CHUNK = 1048576

        private const val STREAM_MAX_IDLE_MS = 120_000L
    }
}
