package com.mutabii.app

import android.content.Context

/**
 * أصوات الأذان والإيقاظ المضمّنة.
 *
 * تُحلّ الموارد بالاسم وقت التشغيل عبر getIdentifier بدل الإشارة المباشرة لـR.raw،
 * حتى يبنى التطبيق ويعمل ولو لم تُضَف الملفات بعد (فيرجع للنغمة الافتراضية).
 * لتفعيل الأصوات: ضع الملفات في app/src/main/res/raw بالأسماء أدناه
 * (أحرف صغيرة، بلا مسافات — mp3/ogg/m4a/wav):
 *   • adhan_makki   — أذان الحرم المكي كاملاً
 *   • adhan_madani  — أذان المسجد النبوي كاملاً
 *   • wake_strong   — تنبيه إيقاظ قوي جداً للفجر
 */
object SoundLib {

    /** معرّف مورد raw بالاسم، أو 0 إن لم يكن الملف موجوداً. */
    fun rawId(c: Context, name: String): Int = try {
        c.resources.getIdentifier(name, "raw", c.packageName)
    } catch (_: Exception) { 0 }

    /** مورد الأذان المضمّن المختار (0 يعني: استخدم ملف المستخدم أو النغمة الافتراضية). */
    fun adhanRes(c: Context): Int = when (Prefs.adhanSound(c)) {
        "makki" -> rawId(c, "adhan_makki")
        "madani" -> rawId(c, "adhan_madani")
        else -> 0
    }

    /** مورد الإيقاظ المضمّن المختار (0 يعني: ملف المستخدم أو نغمة الإيقاظ الافتراضية). */
    fun wakeRes(c: Context): Int = when (Prefs.wakeSound(c)) {
        "strong" -> rawId(c, "wake_strong")
        else -> 0
    }
}
