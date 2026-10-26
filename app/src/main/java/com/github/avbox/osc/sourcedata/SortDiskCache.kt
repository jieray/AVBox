package com.github.avbox.osc.sourcedata

import com.github.avbox.osc.api.ApiConfig
import com.github.avbox.osc.bean.AbsSortXml
import com.github.avbox.osc.base.App
import com.github.avbox.osc.util.AppLog
import com.google.gson.Gson
import java.io.File
import java.util.concurrent.Executors

/**
 * 首页分类宫格的磁盘快照。
 *
 * 目的：冷启动 / 切订阅源后，内存里的 sortCache 是空的，首页要等爬虫跑完才有东西显示，
 * 这段时间就是用户感知到的"卡"。这里把上一次成功拿到的宫格落到磁盘，
 * 下次先拿快照立刻渲染出首屏，再后台拉真实数据覆盖（见 SortLoader.getSort）。
 *
 * 只是加速首屏，不影响正确性：读写任何一步失败都静默忽略，退回原来的行为。
 */
object SortDiskCache {

    private const val DIR_NAME = "sortcache"
    private const val MAX_BYTES_PER_FILE = 2 * 1024 * 1024
    private const val MAX_FILES = 60

    private val gson = Gson()
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sort-disk-cache").apply { isDaemon = true }
    }

    /**
     * 缓存键带上 api 地址：不同订阅源里同名站点（key 相同但 api 不同）不会互相拿错快照。
     */
    fun keyOf(sourceKey: String?): String {
        if (sourceKey.isNullOrEmpty()) return ""
        val api = try {
            ApiConfig.get().getSource(sourceKey)?.api.orEmpty()
        } catch (e: Throwable) {
            ""
        }
        return sourceKey + "|" + api
    }

    private fun dir(): File? {
        return try {
            val ctx = App.getInstance() ?: return null
            val d = File(ctx.filesDir, DIR_NAME)
            if (!d.exists() && !d.mkdirs()) return null
            d
        } catch (e: Throwable) {
            null
        }
    }

    private fun fileOf(cacheKey: String): File? {
        if (cacheKey.isEmpty()) return null
        val d = dir() ?: return null
        val safe = cacheKey.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
            .take(40)
            .ifEmpty { "k" }
        // 中文/符号被过滤后可能撞名，补一段原始串的哈希区分
        val hash = (cacheKey.hashCode() and 0x7fffffff).toString(16)
        return File(d, "${safe}_$hash.json")
    }

    /**
     * 同步读取快照。只在 Dispatchers.IO 上调用。
     */
    fun load(cacheKey: String): AbsSortXml? {
        if (cacheKey.isEmpty()) return null
        return try {
            val f = fileOf(cacheKey) ?: return null
            if (!f.exists() || !f.isFile) return null
            if (f.length() > MAX_BYTES_PER_FILE) {
                f.delete()
                return null
            }
            val text = f.readText()
            if (text.isBlank()) return null
            val xml = gson.fromJson(text, AbsSortXml::class.java)
            if (xml == null || xml.videoList.isNullOrEmpty()) return null
            xml
        } catch (e: Throwable) {
            AppLog.i("echo--sortDiskCache-load-fail: " + e.javaClass.simpleName)
            null
        }
    }

    /**
     * 异步写快照，不阻塞首页。
     */
    fun save(cacheKey: String, sortXml: AbsSortXml?) {
        if (cacheKey.isEmpty()) return
        val videos = sortXml?.videoList
        if (videos.isNullOrEmpty()) return
        try {
            writer.execute {
                try {
                    val f = fileOf(cacheKey) ?: return@execute
                    val json = gson.toJson(sortXml)
                    if (json.length > MAX_BYTES_PER_FILE) return@execute
                    val tmp = File(f.parentFile, f.name + ".tmp")
                    tmp.writeText(json)
                    if (f.exists()) f.delete()
                    tmp.renameTo(f)
                    trim()
                } catch (e: Throwable) {
                    // 写失败不影响任何功能
                }
            }
        } catch (e: Throwable) {
            // 线程池拒接也一样忽略
        }
    }

    /**
     * 控制目录规模，超出后按最后修改时间删最旧的。
     */
    private fun trim() {
        try {
            val d = dir() ?: return
            val files = d.listFiles()?.toMutableList() ?: return
            if (files.size <= MAX_FILES) return
            files.sortBy { it.lastModified() }
            var excess = files.size - MAX_FILES
            for (f in files) {
                if (excess <= 0) break
                if (f.delete()) excess--
            }
        } catch (e: Throwable) {
            // ignore
        }
    }
}
