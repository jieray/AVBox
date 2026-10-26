package com.github.avbox.osc.util

object MusicSettings {
    private const val PLAY_MODE = "music_play_mode"

    private const val AUTO_OPEN_PAGE = "music_auto_open_page"

    fun autoOpenPage(): Boolean = KV.get(AUTO_OPEN_PAGE, false)

    fun setAutoOpenPage(enabled: Boolean) {
        KV.put(AUTO_OPEN_PAGE, enabled)
    }

    var playMode: String
        get() = KV.get(PLAY_MODE, "")
        set(value) {
            KV.put(PLAY_MODE, value)
        }
}
