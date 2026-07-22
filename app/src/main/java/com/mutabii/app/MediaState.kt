package com.mutabii.app

/** آخر حالة معروفة لمشغّل القرآن. */
object MediaState {
    @Volatile var surah: Int = 0
    @Volatile var title: String = ""
    @Volatile var artist: String = ""
    @Volatile var playing: Boolean = false
    @Volatile var pos: Long = 0
    @Volatile var dur: Long = 0
}
