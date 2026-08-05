package com.mutabii.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * تذكيرات برّ الوالدين + وقت فحص صلة الرحم اليومي.
 * تُخزَّن كـJSON في SharedPreferences لتصمد بعد الإقلاع وتُعاد جدولتها من BootReceiver.
 * كل تذكير: {id, at(الوقت القادم ملّي), title, body, rep(دقائق المعاودة حتى الإنجاز)}.
 */
object ReminderStore {
    private const val F = "mtb_reminders"
    private const val K = "list"
    private const val K_KIN = "kin_check"

    private fun sp(c: Context) = c.getSharedPreferences(F, Context.MODE_PRIVATE)

    fun all(c: Context): JSONArray = try {
        JSONArray(sp(c).getString(K, "[]") ?: "[]")
    } catch (_: Exception) { JSONArray() }

    /** يضيف/يحدّث تذكيراً بالمعرّف نفسه. */
    fun put(c: Context, id: String, at: Long, title: String, body: String, rep: Int) {
        try {
            val arr = all(c)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id") != id) out.put(o)
            }
            out.put(
                JSONObject().put("id", id).put("at", at)
                    .put("title", title).put("body", body).put("rep", rep)
            )
            sp(c).edit().putString(K, out.toString()).apply()
        } catch (_: Exception) {}
    }

    fun get(c: Context, id: String): JSONObject? {
        val arr = all(c)
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("id") == id) return o
        }
        return null
    }

    fun remove(c: Context, id: String) {
        try {
            val arr = all(c)
            val out = JSONArray()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                if (o.optString("id") != id) out.put(o)
            }
            sp(c).edit().putString(K, out.toString()).apply()
        } catch (_: Exception) {}
    }

    fun kinCheck(c: Context): String = sp(c).getString(K_KIN, "17:00") ?: "17:00"
    fun setKinCheck(c: Context, v: String) = sp(c).edit().putString(K_KIN, v).apply()
}
