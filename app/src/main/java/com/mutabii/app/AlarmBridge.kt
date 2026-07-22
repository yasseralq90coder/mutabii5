package com.mutabii.app

import android.content.Context
import android.content.Intent
import android.webkit.JavascriptInterface
import org.json.JSONObject

class AlarmBridge(private val ctx: Context) {

    @JavascriptInterface
    fun syncTimes(json: String) {
        try {
            Store.saveSync(ctx, json)
            AlarmScheduler.rescheduleAll(ctx)
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun openSettings() {
        try {
            ctx.startActivity(
                Intent(ctx, SettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }

    /** تُستدعى من الصفحة عند تغيّر حالة مشغّل القرآن. */
    @JavascriptInterface
    fun mediaUpdate(json: String) {
        try {
            val o = JSONObject(json)
            MediaState.surah = o.optInt("surah", 0)
            MediaState.title = o.optString("title", "")
            MediaState.artist = o.optString("artist", "")
            MediaState.playing = o.optBoolean("playing", false)
            MediaState.pos = o.optLong("pos", 0)
            MediaState.dur = o.optLong("dur", 0)
            WebHolder.audioActive = MediaState.surah > 0
            MediaService.update(ctx)
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun mediaStop() {
        try {
            MediaState.surah = 0
            MediaState.playing = false
            WebHolder.audioActive = false
            MediaService.stop(ctx)
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun ping(): String = "ok"
}
