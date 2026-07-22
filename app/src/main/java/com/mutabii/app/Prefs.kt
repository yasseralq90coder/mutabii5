package com.mutabii.app

import android.content.Context
import android.content.SharedPreferences

/** كل إعدادات التنبيهات — مصدر الحقيقة للطبقة الأصلية. */
object Prefs {
    private const val F = "mtb_alarm_prefs"
    fun sp(c: Context): SharedPreferences = c.getSharedPreferences(F, Context.MODE_PRIVATE)

    // مفاتيح
    const val K_ADHAN_ON = "adhan_on"
    const val K_PRE_MIN = "pre_min"
    const val K_IQAMA_ON = "iqama_on"
    const val K_ADHAN_URI = "adhan_uri"
    const val K_ADHAN_VOL = "adhan_vol"
    const val K_ADHAN_STOP = "adhan_stop_sec"

    const val K_WAKE_ON = "wake_on"
    const val K_WAKE_MODE = "wake_mode"        // fajr | time
    const val K_WAKE_OFF = "wake_off"          // دقائق قبل الفجر
    const val K_WAKE_TIME = "wake_time"        // HH:mm
    const val K_WAKE_DAYS = "wake_days"        // "1,2,3,4,5,6,7" (1=أحد..7=سبت)
    const val K_WAKE_URI = "wake_uri"
    const val K_WAKE_VOLSTART = "wake_volstart"
    const val K_WAKE_RAMP = "wake_ramp"
    const val K_WAKE_FORCEMAX = "wake_forcemax"
    const val K_WAKE_VIB = "wake_vib"
    const val K_WAKE_CHALLENGE = "wake_challenge" // none | tap | math
    const val K_WAKE_MATHLVL = "wake_mathlvl"
    const val K_WAKE_TAPS = "wake_taps"
    const val K_WAKE_SNOOZE_MIN = "wake_snooze_min"
    const val K_WAKE_SNOOZE_MAX = "wake_snooze_max"
    const val K_WAKE_AUTOSTOP = "wake_autostop_min"
    const val K_WAKE_SNOOZE_USED = "wake_snooze_used"

    val PRAYERS = arrayOf("fajr", "dhuhr", "asr", "maghrib", "isha")
    val PRAYER_AR = arrayOf("الفجر", "الظهر", "العصر", "المغرب", "العشاء")
    val IQAMA_DEF = intArrayOf(25, 20, 20, 10, 15)

    fun adhanOn(c: Context) = sp(c).getBoolean(K_ADHAN_ON, true)
    fun prayerOn(c: Context, p: String) = sp(c).getBoolean("adhan_$p", true)
    fun setPrayerOn(c: Context, p: String, v: Boolean) = sp(c).edit().putBoolean("adhan_$p", v).apply()
    fun preMin(c: Context) = sp(c).getInt(K_PRE_MIN, 10)
    fun iqamaOn(c: Context) = sp(c).getBoolean(K_IQAMA_ON, false)
    fun iqamaOff(c: Context, i: Int) = sp(c).getInt("iqama_${PRAYERS[i]}", IQAMA_DEF[i])
    fun adhanUri(c: Context): String = sp(c).getString(K_ADHAN_URI, "") ?: ""
    fun adhanVol(c: Context) = sp(c).getInt(K_ADHAN_VOL, 90)
    fun adhanStopSec(c: Context) = sp(c).getInt(K_ADHAN_STOP, 180)

    fun wakeOn(c: Context) = sp(c).getBoolean(K_WAKE_ON, false)
    fun wakeMode(c: Context): String = sp(c).getString(K_WAKE_MODE, "fajr") ?: "fajr"
    fun wakeOff(c: Context) = sp(c).getInt(K_WAKE_OFF, 30)
    fun wakeTime(c: Context): String = sp(c).getString(K_WAKE_TIME, "04:30") ?: "04:30"
    fun wakeDays(c: Context): String = sp(c).getString(K_WAKE_DAYS, "1,2,3,4,5,6,7") ?: "1,2,3,4,5,6,7"
    fun wakeUri(c: Context): String = sp(c).getString(K_WAKE_URI, "") ?: ""
    fun wakeVolStart(c: Context) = sp(c).getInt(K_WAKE_VOLSTART, 30)
    fun wakeRamp(c: Context) = sp(c).getInt(K_WAKE_RAMP, 45)
    fun wakeForceMax(c: Context) = sp(c).getBoolean(K_WAKE_FORCEMAX, true)
    fun wakeVib(c: Context) = sp(c).getBoolean(K_WAKE_VIB, true)
    fun wakeChallenge(c: Context): String = sp(c).getString(K_WAKE_CHALLENGE, "math") ?: "math"
    fun wakeMathLvl(c: Context) = sp(c).getInt(K_WAKE_MATHLVL, 2)
    fun wakeTaps(c: Context) = sp(c).getInt(K_WAKE_TAPS, 20)
    fun wakeSnoozeMin(c: Context) = sp(c).getInt(K_WAKE_SNOOZE_MIN, 5)
    fun wakeSnoozeMax(c: Context) = sp(c).getInt(K_WAKE_SNOOZE_MAX, 3)
    fun wakeAutoStop(c: Context) = sp(c).getInt(K_WAKE_AUTOSTOP, 15)

    fun snoozeUsed(c: Context) = sp(c).getInt(K_WAKE_SNOOZE_USED, 0)
    fun setSnoozeUsed(c: Context, v: Int) = sp(c).edit().putInt(K_WAKE_SNOOZE_USED, v).apply()
}
