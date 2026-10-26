package com.github.avbox.osc.net

import com.github.avbox.osc.util.StringUtils
import com.github.avbox.osc.util.KV
import com.github.avbox.osc.util.KVKeys

object SearchSettings {

    enum class SearchLayout { Horizontal, Vertical }

    /** 搜索范围：全站 / 站内（当前首页站点）/ 选中（搜索设置里勾选的站点）。 */
    enum class SearchScope { All, Current, Selected }

    private const val KEY_SEARCH_SCOPE = "search_scope"

    private const val VALUE_SCOPE_ALL = "all"

    private const val VALUE_SCOPE_CURRENT = "current"

    private const val VALUE_SCOPE_SELECTED = "selected"

    private const val KEY_EXACT_MATCH = "search_exact_match"

    private const val KEY_RESULT_LAYOUT = "search_result_layout"

    private const val VALUE_LAYOUT_HORIZONTAL = "horizontal"

    private const val VALUE_LAYOUT_VERTICAL = "vertical"

    private const val KEY_EMPTY_SOURCES = "search_sources_empty"

    fun scope(): SearchScope = when (KV.get(KEY_SEARCH_SCOPE, VALUE_SCOPE_ALL)) {
        VALUE_SCOPE_CURRENT -> SearchScope.Current
        VALUE_SCOPE_SELECTED -> SearchScope.Selected
        else -> SearchScope.All
    }

    fun setScope(scope: SearchScope) {
        KV.put(
            KEY_SEARCH_SCOPE,
            when (scope) {
                SearchScope.Current -> VALUE_SCOPE_CURRENT
                SearchScope.Selected -> VALUE_SCOPE_SELECTED
                else -> VALUE_SCOPE_ALL
            },
        )
    }

    fun isExactMatchEnabled(): Boolean = KV.get(KEY_EXACT_MATCH, false)

    fun setExactMatchEnabled(enabled: Boolean) {
        KV.put(KEY_EXACT_MATCH, enabled)
    }

    fun resultLayout(): SearchLayout =
        if (KV.get(KEY_RESULT_LAYOUT, VALUE_LAYOUT_VERTICAL) == VALUE_LAYOUT_HORIZONTAL) {
            SearchLayout.Horizontal
        } else {
            SearchLayout.Vertical
        }

    fun setResultLayout(layout: SearchLayout) {
        KV.put(
            KEY_RESULT_LAYOUT,
            if (layout == SearchLayout.Vertical) VALUE_LAYOUT_VERTICAL else VALUE_LAYOUT_HORIZONTAL,
        )
    }

    fun isSourcesEmpty(): Boolean {
        val api = KV.get(KVKeys.API_URL, "")
        return api.isNotEmpty() && emptyApis().contains(api)
    }

    fun currentSelection(): Set<String>? {
        val api = KV.get(KVKeys.API_URL, "")
        if (api.isEmpty()) return null
        if (isSourcesEmpty()) return emptySet()
        val stored = KV.get<HashMap<String, HashMap<String, String>>>(KVKeys.SOURCES_FOR_SEARCH) ?: return null
        val picked = stored[api] ?: return null
        return if (picked.isEmpty()) null else picked.keys.toSet()
    }

    fun putSourcesForSearch(checked: Set<String>) {
        val api = KV.get(KVKeys.API_URL, "")
        if (api.isEmpty()) return
        val searchable = SearchHelper.getSources().keys
        val picked = checked.filter { it in searchable }
        val all = KV.get<HashMap<String, HashMap<String, String>>>(KVKeys.SOURCES_FOR_SEARCH) ?: HashMap()
        if (picked.isEmpty() || picked.containsAll(searchable)) {
            all.remove(api)
        } else {
            all[api] = HashMap<String, String>().apply { picked.forEach { this[it] = "1" } }
        }
        if (all.isEmpty()) KV.delete(KVKeys.SOURCES_FOR_SEARCH) else KV.put(KVKeys.SOURCES_FOR_SEARCH, all)
        setUpEmpty(api, picked.isEmpty() && searchable.isNotEmpty())
    }

    fun isExactMatch(title: String?, keyword: String?): Boolean {
        val normalized = normalize(title)
        return normalized.isNotEmpty() && normalized == normalize(keyword)
    }

    internal fun normalize(text: String?): String = StringUtils.normalizeTitle(text)
}
