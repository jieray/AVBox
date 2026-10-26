package com.github.avbox.osc.util

import com.github.avbox.osc.util.AppLog as LOG

import com.github.avbox.osc.bean.SourceBean

import java.util.HashMap

/**
 * 站点级 ext 覆盖。
 *
 * 站点配置里的 "ext" 由订阅源提供，用户一般改不到；首页顶栏的 ext 图标
 * 允许对当前站点单独改写，改写结果按站点 key 存在本地，重新加载配置后
 * 仍然生效（在 ConfigParser 解析站点时回填）。
 *
 * 存空串等于删除覆盖，回到订阅源给的值。
 */
object SiteExt {

    private const val TAG = "SiteExt"

    private fun overrides(): MutableMap<String, String> {
        return try {
            KV.get(KVKeys.SITE_EXT_OVERRIDE, HashMap<String, String>())
        } catch (th: Throwable) {
            LOG.e(TAG, th)
            HashMap()
        }
    }

    /** 本地保存的 ext；null 表示没有覆盖 */
    fun stored(key: String?): String? {
        if (key.isNullOrEmpty()) return null
        return overrides()[key]
    }

    fun set(key: String?, value: String) {
        if (key.isNullOrEmpty()) return
        val next = HashMap<String, String>(overrides())
        if (value.isEmpty()) next.remove(key) else next[key] = value
        KV.put(KVKeys.SITE_EXT_OVERRIDE, next)
        LOG.i("$TAG: set $key -> $value")
    }

    /** 解析站点后回填本地覆盖 */
    fun apply(bean: SourceBean?) {
        val key = bean?.key ?: return
        if (key.isEmpty()) return
        val value = overrides()[key] ?: return
        bean.ext = value
    }

    /** 当前生效的 ext（含本地覆盖） */
    fun current(bean: SourceBean?): String {
        val key = bean?.key
        if (!key.isNullOrEmpty()) {
            val value = overrides()[key]
            if (value != null) return value
        }
        return bean?.ext ?: ""
    }
}
