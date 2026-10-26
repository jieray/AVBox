package com.github.avbox.osc.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.github.avbox.osc.ui.activity.DetailActivity
import com.github.avbox.osc.util.AppContextHolder
import com.github.avbox.osc.util.AppManager
import com.github.avbox.osc.util.AppLog

/**
 * 详情页唤起兼容层：对齐影视TV / TVBox 系扩展脚本的调用约定。
 *
 * 第三方 jar、py 脚本里"跳详情页"的写法不统一，常见的有三类，这里全部认下：
 *   ① 五参  start(activity, key, id, name, pic)
 *   ② 三参  start(activity, key, id)
 *   ③ 纯 Intent extra —— 且字段名各家不同，见下面的别名表
 *
 * 本 App 自己是宿主，不需要像寄生方案那样去反射猜方法名；
 * 这里做的是反过来：把宿主入口的"接收口径"放宽，让第三方脚本不改一行就能命中。
 */
object DetailIntent {

    // 各字段在影视TV 系里的常见别名，按顺序取第一个非空的
    // 注意别把 "url" 列进 id 别名：详情页本身也可能带 url 字段，误当成 id 会取错内容
    private val ID_KEYS = arrayOf("id", "vod_id", "vodId", "ids")
    private val SOURCE_KEYS = arrayOf("sourceKey", "key", "siteKey", "site_key", "source", "site")
    private val TITLE_KEYS = arrayOf("title", "name", "vod_name", "vodName")
    private val PIC_KEYS = arrayOf("picture", "pic", "img", "vod_pic", "vodPic", "cover", "thumb")

    fun id(bundle: Bundle?): String = first(bundle, ID_KEYS)
    fun sourceKey(bundle: Bundle?): String = first(bundle, SOURCE_KEYS)
    fun title(bundle: Bundle?): String = first(bundle, TITLE_KEYS)
    fun picture(bundle: Bundle?): String = first(bundle, PIC_KEYS)

    private fun first(bundle: Bundle?, keys: Array<String>): String {
        if (bundle == null) return ""
        for (k in keys) {
            val v = bundle.getString(k)
            if (!v.isNullOrEmpty()) return v
        }
        return ""
    }

    fun build(context: Context, key: String?, id: String?, name: String? = null, pic: String? = null): Intent =
        Intent(context, DetailActivity::class.java)
            .putExtra("id", id.orEmpty())
            .putExtra("sourceKey", key.orEmpty())
            .putExtra("title", name.orEmpty())
            .putExtra("picture", pic.orEmpty())

    // ---- 供 jar / py 脚本直接调用 ----
    // 只留一个 Context 入口：Activity 本身也是 Context，
    // 两个重载在 Chaquopy 侧容易解析歧义，收敛成一个最稳。

    /**
     * 五参：影视TV 系最常见的写法。
     *
     * 之前"要点两次才打开"的原因：脚本拿到的往往是 Application 上下文，
     * 这里就退化成 FLAG_ACTIVITY_NEW_TASK。带着这个标记启动，系统会去
     * 复用/新建另一个 task，本 task 还在前台时这次启动常常被压在后面，
     * 用户看到的就是"点了没反应"，再点一次才浮上来。
     * 现在优先取当前前台 Activity，在原有 task 内正常启动，一次就中。
     */
    @JvmStatic
    fun start(context: Context?, key: String?, id: String?, name: String?, pic: String?) {
        val intent = build(resolveContext(context) ?: return, key, id, name, pic)
        val ctx = resolveContext(context) ?: return
        if (ctx !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        } else {
            // 已在栈顶时走 singleTop 的 onNewIntent，不重建，避免丢状态
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            ctx.startActivity(intent)
        } catch (th: Throwable) {
            LOG.e("DetailIntent", th)
        }
    }

    /** 尽量拿到前台 Activity：脚本给的常常是 Application */
    private fun resolveContext(context: Context?): Context? {
        if (context is Activity) return context
        val current = try {
            AppManager.getInstance().currentActivity()
        } catch (th: Throwable) {
            null
        }
        if (current != null && !current.isFinishing && !current.isDestroyed) return current
        return context ?: AppContextHolder.context()
    }

    /** 三参变体 */
    @JvmStatic
    fun start(context: Context?, key: String?, id: String?) {
        start(context, key, id, null, null)
    }
}
