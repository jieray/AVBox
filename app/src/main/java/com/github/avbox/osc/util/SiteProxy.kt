package com.github.avbox.osc.util

import com.github.avbox.osc.bean.ProxyRule
import com.github.avbox.osc.bean.SourceBean
import com.github.avbox.osc.net.OkGoHelper
import com.google.gson.JsonParser

import java.util.HashMap

/**
 * 站点级代理。
 *
 * 规则（与站点配置 "proxy" 字段一致）：
 *   - 字段缺省或为空字符串 -> 该站不走代理；
 *   - 字段填了任意非空值 -> 该站走代理；值是 http://、https://、socks 开头的合法地址时用该地址，
 *     否则（例如 "proxy" 这类占位标记）使用设置里填写的代理地址。
 *
 * 生效方式：
 *   - js / 原生爬虫：请求走 OkHttp，OkHttp 已挂 OkProxySelector，直接把通配规则塞进去即可；
 *   - py 爬虫：脚本用 requests 自己发请求，不经过 OkHttp，所以额外把地址同步给 Python 侧。
 *
 * 临时切换（首页代理图标）只覆盖当前判定的结果，不改站点配置本身。
 */
object SiteProxy {

    private const val TAG = "SiteProxy"

    const val DEFAULT_URL = "http://127.0.0.1:2080"

    private fun overrides(): MutableMap<String, Boolean> {
        return try {
            KV.get(KVKeys.SITE_PROXY_OVERRIDE, HashMap<String, Boolean>())
        } catch (th: Throwable) {
            HashMap()
        }
    }

    /** 覆盖项登记时的站点 api；旧数据没有记录，按“不限制”处理以保持兼容 */
    private fun overrideApis(): MutableMap<String, String> {
        return try {
            KV.get(KVKeys.SITE_PROXY_OVERRIDE_API, HashMap<String, String>())
        } catch (th: Throwable) {
            HashMap()
        }
    }

    /**
     * 覆盖项是否属于这个站点。
     * 站点 key 只在单个订阅源内唯一，不同订阅源里同名站点 key 会重复 ——
     * 只看 key 会让 A 源开的代理错误地作用到 B 源的同名站点上。
     */
    private fun overrideMatches(bean: SourceBean): Boolean {
        val api = overrideApis()[bean.key] ?: return true
        return api.isEmpty() || api == (bean.api ?: "")
    }

    /** 设置里填写的代理地址 */
    fun configuredUrl(): String {
        val url = KV.get(KVKeys.PROXY_URL, DEFAULT_URL)
        val trim = url?.trim() ?: ""
        return if (trim.isEmpty()) DEFAULT_URL else trim
    }

    fun setConfiguredUrl(url: String) {
        KV.put(KVKeys.PROXY_URL, url.trim())
    }

    /**
     * 站点配置解析出的实际代理地址；空串表示配置上不走代理。
     *
     * 【关键字开关】与 appmenu 的 MenuProxy.KEY 同款：
     * proxy 字段为空时，若站点名 / api 路径（含文件夹名或文件名）里出现
     * 关键字「代理」，等同于填了 "proxy" 占位标记 —— 本地脚本目录导入时
     * 不必逐个改 JSON，把目录或文件改名即可整批开关。
     */
    fun resolve(bean: SourceBean?): String {
        if (bean == null) return ""
        val raw = bean.proxy?.trim() ?: ""
        if (raw.isNotEmpty()) {
            if (raw.startsWith("http://") || raw.startsWith("https://") || raw.startsWith("socks")) return raw
            return configuredUrl()
        }
        if (matchKeyword(bean)) return configuredUrl()
        return ""
    }

    /** 关键字「代理」：命中站点名或 api 路径（文件夹名 / 文件名任一含即可） */
    private const val KEY_WORD = "代理"

    @JvmStatic
    fun matchKeyword(bean: SourceBean?): Boolean {
        if (bean == null) return false
        val name = bean.name ?: ""
        if (name.contains(KEY_WORD)) return true
        val api = bean.api ?: ""
        if (api.contains(KEY_WORD)) return true
        return false
    }

    /** 只看站点配置的结论（忽略临时切换） */
    fun configuredEnabled(bean: SourceBean?): Boolean = resolve(bean).isNotEmpty()

    /** 当前是否使用代理（含临时切换） */
    fun isEnabled(bean: SourceBean?): Boolean {
        val key = bean?.key ?: return false
        if (key.isEmpty()) return false
        val ov = overrides()[key]
        if (ov != null && overrideMatches(bean)) return ov
        return configuredEnabled(bean)
    }

    /** 是否处于临时切换状态 */
    fun isOverridden(bean: SourceBean?): Boolean {
        val key = bean?.key ?: return false
        return key.isNotEmpty() && overrides().containsKey(key) && overrideMatches(bean)
    }

    fun setOverride(key: String, enabled: Boolean?, api: String? = null) {
        if (key.isEmpty()) return
        val next = HashMap<String, Boolean>(overrides())
        val nextApi = HashMap<String, String>(overrideApis())
        if (enabled == null) {
            next.remove(key)
            nextApi.remove(key)
        } else {
            next[key] = enabled
            nextApi[key] = api ?: ""
        }
        KV.put(KVKeys.SITE_PROXY_OVERRIDE, next)
        KV.put(KVKeys.SITE_PROXY_OVERRIDE_API, nextApi)
    }

    /** 首页代理图标：临时切换并立即生效 */
    fun toggle(bean: SourceBean?): Boolean {
        val key = bean?.key ?: return false
        if (key.isEmpty()) return false
        val next = !isEnabled(bean)
        setOverride(key, next, bean.api ?: "")
        apply(bean)
        return next
    }

    /** 按当前站点应用代理；未启用则关闭全局代理 */
    fun apply(bean: SourceBean?) {
        val url = if (isEnabled(bean)) resolve(bean).ifEmpty { configuredUrl() } else ""
        applyGlobal(url)
    }

    fun clear() {
        resumeInfra()
        applyGlobal("")
    }

    /**
     * 拉取在线订阅 / 更新这类基础设施请求期间，临时不套【站点代理】。
     * 站点代理是给某个站点的爬虫临时开的，换个场景还在用就是越界。
     */
    fun suspendInfra() {
        try {
            OkGoHelper.proxySelector().suspendSiteProxy = true
        } catch (th: Throwable) {
            LOG.d(TAG, "suspend infra skipped | " + th.message)
        }
    }

    fun resumeInfra() {
        try {
            OkGoHelper.proxySelector().suspendSiteProxy = false
        } catch (th: Throwable) {
            LOG.d(TAG, "resume infra skipped | " + th.message)
        }
    }

    private fun applyGlobal(url: String) {
        resumeInfra()
        if (url.isEmpty()) {
            try {
                OkGoHelper.setProxyList(null)
            } catch (th: Throwable) {
                LOG.e(TAG, th)
            }
            syncPython("")
            LOG.i("$TAG: proxy off")
            return
        }
        try {
            // 注意：hosts 不能用 "*"。OkProxySelector.containOrMatch 走的是
            // text.contains(rule) || text.matches(Regex(rule))，而 Regex("*") 是非法正则、
            // 会抛异常被 catch 吞掉，contains("*") 又恒为 false —— 结果任何域名都匹配不上，
            // 代理等于没开。hosts 用空串：contains("") 恒真，且不依赖正则。
            val json = "[{\"name\":\"site_proxy\",\"hosts\":[\"\"],\"urls\":[\"" + url + "\"]}]"
            val array = JsonParser.parseString(json).asJsonArray
            OkGoHelper.setProxyList(ProxyRule.arrayFrom(array))
        } catch (th: Throwable) {
            LOG.e(TAG, th)
        }
        syncPython(url)
        LOG.i("$TAG: proxy on -> $url")
    }

    /** py 脚本用 requests 自己发请求，不走 OkHttp，单独同步地址 */
    private fun syncPython(url: String) {
        try {
            val loader = com.undcover.freedom.pyramid.PythonLoader.getInstance()
            val app = loader?.pyApp ?: return
            app.callAttr("setSiteProxy", url)
        } catch (th: Throwable) {
            LOG.d(TAG, "python proxy sync skipped | " + th.message)
        }
    }
}
