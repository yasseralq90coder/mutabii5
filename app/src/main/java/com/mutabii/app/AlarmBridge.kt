package com.mutabii.app

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.JavascriptInterface
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

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

    /**
     * يحفظ ملفاً نصياً (JSON / CSV / ICS) في مجلد التنزيلات العام.
     * WebView لا يُنفّذ تنزيل blob تلقائياً، لذا يمرّ التصدير عبر هذه الدالة.
     */
    @JavascriptInterface
    fun saveText(filename: String, mime: String, content: String): String {
        return try {
            val name = sanitize(filename)
            val bytes = content.toByteArray(Charsets.UTF_8)
            val type = if (mime.isNotEmpty()) mime else "application/octet-stream"
            val where: String
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, type)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return failSave("تعذّر إنشاء الملف")
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: return failSave("تعذّر الكتابة")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                where = "التنزيلات/$name"
            } else {
                val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                val target = try {
                    if (!dir.exists()) dir.mkdirs()
                    val f = File(dir, name)
                    FileOutputStream(f).use { it.write(bytes) }
                    f
                } catch (_: Exception) {
                    // بديل بلا صلاحيات: مجلد التطبيق الخاص
                    val alt = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), name)
                    FileOutputStream(alt).use { it.write(bytes) }
                    alt
                }
                where = target.absolutePath
            }
            toastMain("✅ حُفظ في: $where")
            "ok:$where"
        } catch (e: Exception) {
            toastMain("❌ تعذّر الحفظ: ${e.message}")
            "err:${e.message}"
        }
    }

    private fun failSave(msg: String): String {
        toastMain("❌ تعذّر الحفظ: $msg")
        return "err:$msg"
    }

    private fun sanitize(n: String): String {
        val base = n.ifBlank { "mutabii-backup" }
        return base.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun toastMain(msg: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                try { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
