package com.mutabii.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object AlarmScheduler {

    const val ACTION_FIRE = "com.mutabii.app.ALARM_FIRE"
    private const val HORIZON_MS = 4L * 24 * 60 * 60 * 1000  // 4 أيام
    private const val CODE_BASE = 100000
    private const val K_CODES = "sched_codes"

    data class Ev(val t: Long, val type: String, val prayer: Int, val title: String, val text: String)

    fun rescheduleAll(c: Context) {
        try {
            cancelAll(c)
            val now = System.currentTimeMillis()
            val events = buildEvents(c, now, now + HORIZON_MS)
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val codes = StringBuilder()
            var idx = 0
            for (e in events) {
                val code = CODE_BASE + idx
                val fire = Intent(c, AlarmReceiver::class.java).apply {
                    action = ACTION_FIRE
                    putExtra("type", e.type)
                    putExtra("prayer", e.prayer)
                    putExtra("title", e.title)
                    putExtra("text", e.text)
                    putExtra("t", e.t)
                }
                val pi = PendingIntent.getBroadcast(
                    c, code, fire,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                scheduleExact(am, c, e.t, pi)
                if (codes.isNotEmpty()) codes.append(",")
                codes.append(code)
                idx++
                if (idx > 200) break
            }
            // منبّه صيانة يومي لإعادة الجدولة حتى لو لم يُفتح التطبيق
            val maintCode = CODE_BASE + 90000
            val maintAt = nextAt(now, 0, 35)
            val mIntent = Intent(c, AlarmReceiver::class.java).apply {
                action = ACTION_FIRE; putExtra("type", "maint")
            }
            val mpi = PendingIntent.getBroadcast(
                c, maintCode, mIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            scheduleExact(am, c, maintAt, mpi)
            if (codes.isNotEmpty()) codes.append(",")
            codes.append(maintCode)

            c.getSharedPreferences("mtb_sched", Context.MODE_PRIVATE)
                .edit().putString(K_CODES, codes.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun scheduleExact(am: AlarmManager, c: Context, t: Long, pi: PendingIntent) {
        val show = PendingIntent.getActivity(
            c, 7, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi)
            } else {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(t, show), pi)
            }
        } catch (se: SecurityException) {
            try { am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi) } catch (_: Exception) {}
        }
    }

    private fun cancelAll(c: Context) {
        try {
            val sp = c.getSharedPreferences("mtb_sched", Context.MODE_PRIVATE)
            val s = sp.getString(K_CODES, "") ?: ""
            if (s.isNotEmpty()) {
                val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                for (part in s.split(",")) {
                    val code = part.trim().toIntOrNull() ?: continue
                    val base = Intent(c, AlarmReceiver::class.java).apply { action = ACTION_FIRE }
                    val pi = PendingIntent.getBroadcast(
                        c, code, base,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    am.cancel(pi); pi.cancel()
                }
            }
            sp.edit().remove(K_CODES).apply()
        } catch (_: Exception) {}
    }

    fun buildEvents(c: Context, from: Long, to: Long): List<Ev> {
        val list = ArrayList<Ev>()
        val days = Store.days(c)
        val loc = Store.loc(c)
        val adhanOn = Prefs.adhanOn(c)
        val pre = Prefs.preMin(c)
        val iqamaOn = Prefs.iqamaOn(c)
        val wakeOn = Prefs.wakeOn(c)
        val wakeMode = Prefs.wakeMode(c)
        val wakeOff = Prefs.wakeOff(c)
        val wakeDays = Prefs.wakeDays(c).split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()

        for (day in days) {
            if (adhanOn || wakeOn) {
                for (i in 0 until 5) {
                    val st = day.byIndex(i)
                    if (st <= 0) continue
                    val name = Prefs.PRAYER_AR[i]
                    if (adhanOn && Prefs.prayerOn(c, Prefs.PRAYERS[i])) {
                        list.add(Ev(st, "adhan", i, "🕌 أذان $name", "$loc"))
                        if (pre > 0) list.add(
                            Ev(st - pre * 60000L, "pre", i, "⏰ اقترب وقت $name", "بقي نحو $pre دقيقة")
                        )
                        if (iqamaOn) {
                            val q = st + Prefs.iqamaOff(c, i) * 60000L
                            list.add(Ev(q, "iqama", i, "🕌 إقامة $name", "حيّ على الصلاة"))
                        }
                    }
                }
            }
            if (wakeOn) {
                val base: Long = if (wakeMode == "time") {
                    dayAt(day.fajr, Prefs.wakeTime(c))
                } else {
                    if (day.fajr > 0) day.fajr - wakeOff * 60000L else -1L
                }
                if (base > 0) {
                    val cal = Calendar.getInstance().apply { timeInMillis = base }
                    val dow = cal.get(Calendar.DAY_OF_WEEK) // 1=Sun..7=Sat
                    if (wakeDays.isEmpty() || wakeDays.contains(dow)) {
                        list.add(Ev(base, "wake", -1, "⏰ الإيقاظ لصلاة الفجر", "الصلاةُ خيرٌ من النوم"))
                    }
                }
            }
        }
        return list.filter { it.t > from + 1000 && it.t <= to }.sortedBy { it.t }
    }

    /** يبني وقتاً في نفس يوم المرجع لكن بساعة HH:mm. */
    private fun dayAt(refMs: Long, hhmm: String): Long {
        return try {
            val p = hhmm.split(":")
            val cal = Calendar.getInstance().apply {
                timeInMillis = if (refMs > 0) refMs else System.currentTimeMillis()
                set(Calendar.HOUR_OF_DAY, p[0].toInt())
                set(Calendar.MINUTE, p[1].toInt())
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            cal.timeInMillis
        } catch (_: Exception) { -1L }
    }

    private fun nextAt(now: Long, h: Int, m: Int): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (cal.timeInMillis <= now) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /** جدولة غفوة لمرة واحدة. */
    fun scheduleSnooze(c: Context, minutes: Int) {
        try {
            val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val t = System.currentTimeMillis() + minutes * 60000L
            val i = Intent(c, AlarmReceiver::class.java).apply {
                action = ACTION_FIRE
                putExtra("type", "wake"); putExtra("prayer", -1)
                putExtra("title", "⏰ الإيقاظ (غفوة)"); putExtra("text", "حان وقت القيام")
                putExtra("t", t)
            }
            val pi = PendingIntent.getBroadcast(
                c, CODE_BASE + 80000, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            scheduleExact(am, c, t, pi)
        } catch (_: Exception) {}
    }
}
