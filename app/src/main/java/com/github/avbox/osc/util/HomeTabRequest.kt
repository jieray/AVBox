package com.github.avbox.osc.util

import androidx.compose.runtime.mutableStateOf

/**
 * 从其它页面（如配置管理）请求主界面切回首页宫格页。
 */
object HomeTabRequest {
    private val tokenState = mutableStateOf(0)

    val token: Int
        get() = tokenState.value

    fun request() {
        tokenState.value += 1
    }
}
