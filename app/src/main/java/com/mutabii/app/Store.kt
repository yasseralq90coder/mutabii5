package com.mutabii.app

import android.content.Context
import org.json.JSONObject

/** يخزّن مواقيت الصلاة القادمة (بالميلي ثانية) القادمة من صفحة الويب عبر pTimes. */
object Store {
    private const val F = "mtb_times"
    private const val K_DAYS = "days_json"
    private const val K_LOC = "loc"

    fun saveSync(c: Context, json: String) {
        try {
            val o = JSONObject(json)
            val loc = o.optString("loc", "مكة المكرمة")
            val days = o.optJSONArray("days")
            if (days != null && days.length() > 0) {
                c.getSharedPreferences(F, Context.MODE_PRIVATE).edit()
                    .putString(K_DAYS, days.toString())
                    .putString(K_LOC, loc)
                    .apply()
            }
        } catch (_: Exception) {}
    }

    fun loc(c: Context): String =
        c.getSharedPreferences(F, Context.MODE_PRIVATE).getString(K_LOC, "مكة المكرمة") ?: "مكة المكرمة"

    data class Day(
        val fajr: Long, val dhuhr: Long, val asr: Long,
        val maghrib: Long, val isha: Long
    ) {
        fun byIndex(i: Int) = when (i) {
            0 -> fajr; 1 -> dhuhr; 2 -> asr; 3 -> maghrib; else -> isha
        }
    }

    fun days(c: Context): List<Day> {
        val out = ArrayList<Day>()
        try {
            val s = c.getSharedPreferences(F, Context.MODE_PRIVATE).getString(K_DAYS, null) ?: return out
            val arr = org.json.JSONArray(s)
            for (i in 0 until arr.length()) {
                val d = arr.getJSONObject(i)
                out.add(
                    Day(
                        d.optLong("fajr"), d.optLong("dhuhr"), d.optLong("asr"),
                        d.optLong("maghrib"), d.optLong("isha")
                    )
                )
            }
        } catch (_: Exception) {}
        return out
    }
}
