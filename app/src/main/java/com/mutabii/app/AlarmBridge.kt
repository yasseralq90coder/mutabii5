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

    /* ═══ إعدادات التنبيهات — مصدر الحقيقة هو Prefs الأصلية ═══
       الصفحة تعرض وتكتب هنا مباشرة، فما تراه في الشاشة هو ما يرنّ فعلاً. */

    @JavascriptInterface
    fun getAlarms(): String {
        return try {
            val o = JSONObject()
            o.put("adhanOn", Prefs.adhanOn(ctx))
            val pr = JSONObject()
            Prefs.PRAYERS.forEach { pr.put(it, Prefs.prayerOn(ctx, it)) }
            o.put("prayers", pr)
            o.put("preMin", Prefs.preMin(ctx))
            o.put("iqamaOn", Prefs.iqamaOn(ctx))
            val iq = JSONObject()
            Prefs.PRAYERS.forEachIndexed { i, p -> iq.put(p, Prefs.iqamaOff(ctx, i)) }
            o.put("iqama", iq)
            o.put("adhanUri", Prefs.adhanUri(ctx))
            o.put("adhanVol", Prefs.adhanVol(ctx))
            o.put("adhanStopSec", Prefs.adhanStopSec(ctx))

            o.put("wakeOn", Prefs.wakeOn(ctx))
            o.put("wakeMode", Prefs.wakeMode(ctx))
            o.put("wakeOff", Prefs.wakeOff(ctx))
            o.put("wakeTime", Prefs.wakeTime(ctx))
            o.put("wakeDays", Prefs.wakeDays(ctx))
            o.put("wakeUri", Prefs.wakeUri(ctx))
            o.put("wakeVolStart", Prefs.wakeVolStart(ctx))
            o.put("wakeRamp", Prefs.wakeRamp(ctx))
            o.put("wakeForceMax", Prefs.wakeForceMax(ctx))
            o.put("wakeVib", Prefs.wakeVib(ctx))
            o.put("wakeChallenge", Prefs.wakeChallenge(ctx))
            o.put("wakeMathLvl", Prefs.wakeMathLvl(ctx))
            o.put("wakeTaps", Prefs.wakeTaps(ctx))
            o.put("wakeSnoozeMin", Prefs.wakeSnoozeMin(ctx))
            o.put("wakeSnoozeMax", Prefs.wakeSnoozeMax(ctx))
            o.put("wakeAutoStop", Prefs.wakeAutoStop(ctx))
            o.put("wakeRetries", Prefs.wakeRetries(ctx))
            o.put("wakeRetryMin", Prefs.wakeRetryMin(ctx))
            o.put("exactOk", exactAlarmsAllowed())
            o.put("battOk", batteryUnrestricted())
            o.toString()
        } catch (e: Exception) { "{}" }
    }

    /** يكتب مفتاحاً واحداً ثم يعيد جدولة المنبّهات فوراً — فيسري الأثر بلا حفظ منفصل. */
    @JavascriptInterface
    fun setAlarm(key: String, value: String) {
        try {
            val e = Prefs.sp(ctx).edit()
            when (key) {
                "adhanOn" -> e.putBoolean(Prefs.K_ADHAN_ON, value == "1")
                "preMin" -> e.putInt(Prefs.K_PRE_MIN, value.toIntOrNull() ?: 10)
                "iqamaOn" -> e.putBoolean(Prefs.K_IQAMA_ON, value == "1")
                "adhanVol" -> e.putInt(Prefs.K_ADHAN_VOL, value.toIntOrNull() ?: 90)
                "adhanStopSec" -> e.putInt(Prefs.K_ADHAN_STOP, value.toIntOrNull() ?: 180)
                "wakeOn" -> e.putBoolean(Prefs.K_WAKE_ON, value == "1")
                "wakeMode" -> e.putString(Prefs.K_WAKE_MODE, value)
                "wakeOff" -> e.putInt(Prefs.K_WAKE_OFF, value.toIntOrNull() ?: 30)
                "wakeTime" -> e.putString(Prefs.K_WAKE_TIME, value)
                "wakeDays" -> e.putString(Prefs.K_WAKE_DAYS, value)
                "wakeVolStart" -> e.putInt(Prefs.K_WAKE_VOLSTART, value.toIntOrNull() ?: 30)
                "wakeRamp" -> e.putInt(Prefs.K_WAKE_RAMP, value.toIntOrNull() ?: 45)
                "wakeForceMax" -> e.putBoolean(Prefs.K_WAKE_FORCEMAX, value == "1")
                "wakeVib" -> e.putBoolean(Prefs.K_WAKE_VIB, value == "1")
                "wakeChallenge" -> e.putString(Prefs.K_WAKE_CHALLENGE, value)
                "wakeMathLvl" -> e.putInt(Prefs.K_WAKE_MATHLVL, value.toIntOrNull() ?: 2)
                "wakeTaps" -> e.putInt(Prefs.K_WAKE_TAPS, value.toIntOrNull() ?: 20)
                "wakeSnoozeMin" -> e.putInt(Prefs.K_WAKE_SNOOZE_MIN, value.toIntOrNull() ?: 5)
                "wakeSnoozeMax" -> e.putInt(Prefs.K_WAKE_SNOOZE_MAX, value.toIntOrNull() ?: 3)
                "wakeAutoStop" -> e.putInt(Prefs.K_WAKE_AUTOSTOP, value.toIntOrNull() ?: 15)
                "wakeRetries" -> e.putInt(Prefs.K_WAKE_RETRIES, value.toIntOrNull() ?: 3)
                "wakeRetryMin" -> e.putInt(Prefs.K_WAKE_RETRY_MIN, value.toIntOrNull() ?: 3)
                "adhanUri" -> e.putString(Prefs.K_ADHAN_URI, value)
                "wakeUri" -> e.putString(Prefs.K_WAKE_URI, value)
                else -> {
                    // adhan_<صلاة> و iqama_<صلاة>
                    if (key.startsWith("adhan_")) e.putBoolean(key, value == "1")
                    else if (key.startsWith("iqama_")) e.putInt(key, value.toIntOrNull() ?: 20)
                    else return
                }
            }
            e.apply()
            AlarmScheduler.rescheduleAll(ctx)
        } catch (_: Exception) {}
    }

    /** تجربة الصوت الحقيقي بالإعدادات الحالية — لا محاكاة. */
    @JavascriptInterface
    fun testAlarm(mode: String) {
        try {
            val wake = mode == "wake"
            val i = Intent(ctx, AlarmService::class.java).apply {
                putExtra("mode", if (wake) "wake" else "adhan")
                putExtra("title", if (wake) "⏰ تجربة الإيقاظ" else "🕌 تجربة الأذان")
                putExtra("text", "هذه تجربة — أوقفها متى شئت")
                putExtra("uri", if (wake) Prefs.wakeUri(ctx) else Prefs.adhanUri(ctx))
                putExtra("vol", if (wake) 100 else Prefs.adhanVol(ctx))
                putExtra("loop", false)
                putExtra("autoStopMs", 30000L)
                putExtra("forceMax", false)   // التجربة لا تفرض أقصى صوت
                putExtra("vibrate", wake && Prefs.wakeVib(ctx))
                putExtra("volStart", if (wake) Prefs.wakeVolStart(ctx) else Prefs.adhanVol(ctx))
                putExtra("rampSec", if (wake) Prefs.wakeRamp(ctx) else 0)
            }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun stopTest() {
        try { ctx.startService(Intent(ctx, AlarmService::class.java).apply { action = "STOP" }) }
        catch (_: Exception) {}
    }

    /** يفتح منتقي ملف صوت ويحفظه في مفتاح الأذان أو الإيقاظ. */
    @JavascriptInterface
    fun pickSound(target: String) {
        try { MainActivity.pickSound(target) } catch (_: Exception) {}
    }

    @JavascriptInterface
    fun openExactSettings() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } else openSettings()
        } catch (_: Exception) { openSettings() }
    }

    @JavascriptInterface
    @android.annotation.SuppressLint("BatteryLife")
    fun openBatterySettings() {
        try {
            ctx.startActivity(
                Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(android.net.Uri.parse("package:" + ctx.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) { openSettings() }
    }

    private fun exactAlarmsAllowed(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 31) {
            (ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
                .canScheduleExactAlarms()
        } else true
    } catch (_: Exception) { true }

    private fun batteryUnrestricted(): Boolean = try {
        (ctx.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
            .isIgnoringBatteryOptimizations(ctx.packageName)
    } catch (_: Exception) { true }

    /**
     * تسجيل الأذكار: هل صلاحية الميكروفون ممنوحة؟
     * إن لم تكن، يُطلب من المستخدم منحها ثم تُعاد المحاولة من الصفحة.
     */
    @JavascriptInterface
    fun ensureMic(): Boolean {
        return try {
            val granted = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (!granted) MainActivity.askMic()
            granted
        } catch (_: Exception) { false }
    }

    /** ملء الشاشة: يخفي/يُظهر أشرطة النظام (WebView لا يستطيع ذلك بنفسه). */
    @JavascriptInterface
    fun setFullscreen(full: Boolean) {
        try { MainActivity.setFullscreenMode(full) } catch (_: Exception) {}
    }

    /** مشاركة نصية عبر قائمة مشاركة أندرويد الحقيقية (navigator.share لا يعمل في WebView). */
    @JavascriptInterface
    fun shareText(text: String) {
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
            }
            ctx.startActivity(
                Intent.createChooser(send, "مشاركة").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (_: Exception) {}
    }

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
