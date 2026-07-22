package com.mutabii.app

import android.app.Activity
import android.app.AlarmManager
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private val GOLD = Color.parseColor("#C9A44C")
    private val TEXT = Color.parseColor("#F2E9D8")
    private val MUTED = Color.parseColor("#9A8A6E")
    private val CARD = Color.parseColor("#2A2116")
    private val BG = Color.parseColor("#1A1410")

    private var pickTarget = "adhan"
    private var adhanLabel: TextView? = null
    private var wakeLabel: TextView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(20), dp(16), dp(30))
        }

        root.addView(header("🔔 تنبيهات الأذان والإيقاظ"))
        root.addView(note("تعمل هذه التنبيهات حتى والتطبيق مغلق. المواقيت تُؤخذ تلقائياً من التطبيق (أذان الحرم كأساس)."))

        // ===== الأذان =====
        root.addView(section("الأذان"))
        root.addView(switchRow("تفعيل تنبيهات الأذان", Prefs.adhanOn(this)) { v ->
            Prefs.sp(this).edit().putBoolean(Prefs.K_ADHAN_ON, v).apply()
        })
        for (i in 0 until 5) {
            val p = Prefs.PRAYERS[i]
            root.addView(switchRow("أذان ${Prefs.PRAYER_AR[i]}", Prefs.prayerOn(this, p)) { v ->
                Prefs.setPrayerOn(this, p, v)
            })
        }
        root.addView(seekRow("تنبيه قبل الأذان", Prefs.preMin(this), 0, 30, "دقيقة (0=إيقاف)") { v ->
            Prefs.sp(this).edit().putInt(Prefs.K_PRE_MIN, v).apply()
        })
        root.addView(switchRow("تنبيه الإقامة", Prefs.iqamaOn(this)) { v ->
            Prefs.sp(this).edit().putBoolean(Prefs.K_IQAMA_ON, v).apply()
        })
        root.addView(seekRow("مستوى صوت الأذان", Prefs.adhanVol(this), 10, 100, "%") { v ->
            Prefs.sp(this).edit().putInt(Prefs.K_ADHAN_VOL, v).apply()
        })
        root.addView(seekRow("إيقاف الأذان تلقائياً بعد", Prefs.adhanStopSec(this), 30, 600, "ثانية") { v ->
            Prefs.sp(this).edit().putInt(Prefs.K_ADHAN_STOP, v).apply()
        })
        adhanLabel = valueLabel(soundName(Prefs.adhanUri(this), "النغمة الافتراضية"))
        root.addView(rowWithButton("صوت الأذان", "اختيار ملف", adhanLabel!!) {
            pickTarget = "adhan"; pickAudio()
        })
        root.addView(smallButton("استخدام النغمة الافتراضية للأذان") {
            Prefs.sp(this).edit().putString(Prefs.K_ADHAN_URI, "").apply()
            adhanLabel?.text = "النغمة الافتراضية"
        })

        // ===== الإيقاظ =====
        root.addView(section("منبّه الاستيقاظ (قوي)"))
        root.addView(switchRow("تفعيل منبّه الاستيقاظ", Prefs.wakeOn(this)) { v ->
            Prefs.sp(this).edit().putBoolean(Prefs.K_WAKE_ON, v).apply()
        })

        val modeGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbFajr = radio("قبل الفجر بدقائق")
        val rbTime = radio("وقت ثابت")
        modeGroup.addView(rbFajr); modeGroup.addView(rbTime)
        if (Prefs.wakeMode(this) == "time") rbTime.isChecked = true else rbFajr.isChecked = true
        modeGroup.setOnCheckedChangeListener { _, id ->
            val m = if (id == rbTime.id) "time" else "fajr"
            Prefs.sp(this).edit().putString(Prefs.K_WAKE_MODE, m).apply()
        }
        root.addView(labeled("طريقة التوقيت", modeGroup))

        root.addView(seekRow("قبل الفجر بـ", Prefs.wakeOff(this), 0, 90, "دقيقة") { v ->
            Prefs.sp(this).edit().putInt(Prefs.K_WAKE_OFF, v).apply()
        })

        val timeLbl = valueLabel(Prefs.wakeTime(this))
        root.addView(rowWithButton("الوقت الثابت للإيقاظ", "تغيير", timeLbl) {
            val parts = Prefs.wakeTime(this).split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 4
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 30
            TimePickerDialog(this, { _, hh, mm ->
                val s = String.format("%02d:%02d", hh, mm)
                Prefs.sp(this).edit().putString(Prefs.K_WAKE_TIME, s).apply()
                timeLbl.text = s
            }, h, m, true).show()
        })

        // أيام الأسبوع
        root.addView(labelText("أيام التكرار:"))
        val daysRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val dayNames = arrayOf("أحد", "إثن", "ثلا", "أرب", "خمي", "جمع", "سبت")
        val selected = Prefs.wakeDays(this).split(",").mapNotNull { it.trim().toIntOrNull() }.toMutableSet()
        for (d in 1..7) {
            val cb = CheckBox(this).apply {
                text = dayNames[d - 1]; setTextColor(TEXT); textSize = 11f
                isChecked = selected.contains(d)
                setOnCheckedChangeListener { _, on ->
                    if (on) selected.add(d) else selected.remove(d)
                    Prefs.sp(this@SettingsActivity).edit()
                        .putString(Prefs.K_WAKE_DAYS, selected.sorted().joinToString(",")).apply()
                }
            }
            val lp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            cb.layoutParams = lp
            daysRow.addView(cb)
        }
        root.addView(card(daysRow))

        wakeLabel = valueLabel(soundName(Prefs.wakeUri(this), "نغمة الإيقاظ الافتراضية"))
        root.addView(rowWithButton("صوت الإيقاظ", "اختيار ملف", wakeLabel!!) {
            pickTarget = "wake"; pickAudio()
        })
        root.addView(smallButton("استخدام نغمة الإيقاظ الافتراضية") {
            Prefs.sp(this).edit().putString(Prefs.K_WAKE_URI, "").apply()
            wakeLabel?.text = "نغمة الإيقاظ الافتراضية"
        })

        root.addView(seekRow("مستوى الصوت الابتدائي", Prefs.wakeVolStart(this), 5, 100, "%") { v ->
            Prefs.sp(this).edit().putInt(Prefs.K_WAKE_VOLSTART, v).apply()
        })
        root.addView(seekRow("مدة تصاعد الصوت", Prefs.wakeRamp(this), 0, 120, "ثانية") { v ->
            Prefs.sp(this).edit().putInt(Prefs.K_WAKE_RAMP, v).apply()
        })
        root.addView(switchRow("فرض أعلى مستوى صوت للمنبّه", Prefs.wakeForceMax(this)) { v ->
            Prefs.sp(this).edit().putBoolean(Prefs.K_WAKE_FORCEMAX, v).apply()
        })
        root.addView(switchRow("اهتزاز قوي", Prefs.wakeVib(this)) { v ->
            Prefs.sp(this).edit().putBoolean(Prefs.K_WAKE_VIB, v).apply()
        })

        // تحدّي الإيقاف
        val chGroup = RadioGroup(this).apply { orientation = RadioGroup.HORIZONTAL }
        val rbNone = radio("بدون")
        val rbTap = radio("نقرات")
        val rbMath = radio("مسألة")
        chGroup.addView(rbNone); chGroup.addView(rbTap); chGroup.addView(rbMath)
        when (Prefs.wakeChallenge(this)) {
            "none" -> rbNone.isChecked = true
            "tap" -> rbTap.isChecked = true
            else -> rbMath.isChecked = true
        }
        chGroup.setOnCheckedChangeListener { _, id ->
            val v = when (id) { rbNone.id -> "none"; rbTap.id -> "tap"; else -> "math" }
            Prefs.sp(this).edit().putString(Prefs.K_WAKE_CHALLENGE, v).apply()
        }
        root.addView(labeled("طريقة إيقاف المنبّه", chGroup))

        root.addView(seekRow("صعوبة المسألة الحسابية", Prefs.wakeMathLvl(this), 1, 3, "مستوى") { v ->
            Prefs.sp(this).edit().putInt(Prefs.K_WAKE_MATHLVL, v).apply()
        })
        root.addView(seekRow("عدد النقرات للإيقاف", Prefs.wakeTaps(this), 5, 50, "نقرة") { v ->
            Prefs.sp(this).edit().putInt(Prefs.K_WAKE_TAPS, v).apply()
        })
        root.addView(seekRow("مدة الغفوة", Prefs.wakeSnoozeMin(this), 1, 15, "دقيقة") { v ->
            Prefs.sp(this).edit().putInt(Prefs.K_WAKE_SNOOZE_MIN, v).apply()
        })
        root.addView(seekRow("عدد مرات الغفوة المسموحة", Prefs.wakeSnoozeMax(this), 0, 10, "مرة") { v ->
            Prefs.sp(this).edit().putInt(Prefs.K_WAKE_SNOOZE_MAX, v).apply()
        })
        root.addView(seekRow("إيقاف المنبّه تلقائياً بعد", Prefs.wakeAutoStop(this), 0, 60, "دقيقة (0=حتى الإيقاف يدوياً)") { v ->
            Prefs.sp(this).edit().putInt(Prefs.K_WAKE_AUTOSTOP, v).apply()
        })

        // ===== أذونات النظام =====
        root.addView(section("أذونات مهمة للتشغيل"))
        root.addView(note("للتأكد من عمل المنبّه بدقة ولو كان الجوال نائماً، فعّل هذين الإذنين:"))
        root.addView(bigButton("① السماح بالمنبّهات الدقيقة") { openExactAlarm() })
        root.addView(bigButton("② تجاهل تقييد البطارية") { openBattery() })

        // ===== اختبار =====
        root.addView(section("اختبار"))
        root.addView(bigButton("▶ تجربة صوت الأذان") { testSound("adhan") })
        root.addView(bigButton("▶ تجربة شاشة الإيقاظ") { testWake() })
        root.addView(smallButton("⏹ إيقاف الصوت") {
            startService(Intent(this, AlarmService::class.java).apply { action = "STOP" })
        })

        // حفظ
        root.addView(TextView(this).apply { setPadding(0, dp(10), 0, 0) })
        root.addView(bigButton("💾 حفظ وتفعيل التنبيهات") {
            Prefs.sp(this).edit().putBoolean(Prefs.K_ADHAN_ON, Prefs.adhanOn(this)).apply()
            AlarmScheduler.rescheduleAll(this)
            Toast.makeText(this, "تم الحفظ وجدولة التنبيهات ✅", Toast.LENGTH_LONG).show()
            finish()
        })

        val sv = ScrollView(this).apply { addView(root); setBackgroundColor(BG) }
        setContentView(sv)
    }

    // ====== مُساعدات واجهة ======
    private fun header(t: String) = TextView(this).apply {
        text = t; textSize = 22f; setTextColor(GOLD); gravity = Gravity.CENTER
        setPadding(0, 0, 0, dp(6))
    }
    private fun section(t: String) = TextView(this).apply {
        text = "◆ $t"; textSize = 17f; setTextColor(GOLD)
        setPadding(0, dp(18), 0, dp(8))
    }
    private fun note(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(MUTED); setPadding(dp(2), 0, dp(2), dp(6))
    }
    private fun labelText(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(TEXT); setPadding(dp(2), dp(8), dp(2), dp(4))
    }
    private fun valueLabel(t: String) = TextView(this).apply {
        text = t; textSize = 12f; setTextColor(MUTED)
    }
    private fun card(inner: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(CARD)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(6); layoutParams = lp
        addView(inner)
    }
    private fun labeled(label: String, v: View) = card(LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(this@SettingsActivity).apply { text = label; setTextColor(TEXT); textSize = 14f })
        addView(v)
    })

    private fun switchRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val tv = TextView(this).apply {
            text = label; setTextColor(TEXT); textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val sw = Switch(this).apply {
            isChecked = initial
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        row.addView(tv); row.addView(sw)
        return card(row)
    }

    private fun seekRow(label: String, initial: Int, lo: Int, hi: Int, unit: String, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val head = TextView(this).apply {
            text = "$label: $initial $unit"; setTextColor(TEXT); textSize = 14f
        }
        val sb = SeekBar(this).apply {
            max = hi - lo
            progress = (initial - lo).coerceIn(0, hi - lo)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = p + lo
                    head.text = "$label: $v $unit"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    onChange((s?.progress ?: 0) + lo)
                }
            })
        }
        box.addView(head); box.addView(sb)
        return card(box)
    }

    private fun rowWithButton(label: String, btn: String, valueView: TextView, onClick: () -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        top.addView(TextView(this).apply {
            text = label; setTextColor(TEXT); textSize = 15f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        top.addView(Button(this).apply {
            text = btn; textSize = 13f
            setTextColor(BG); setBackgroundColor(GOLD)
            setOnClickListener { onClick() }
        })
        box.addView(top); box.addView(valueView)
        return card(box)
    }

    private fun bigButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 16f; setTextColor(BG); setBackgroundColor(GOLD)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
        lp.topMargin = dp(8); layoutParams = lp
        setOnClickListener { onClick() }
    }
    private fun smallButton(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label; textSize = 13f; setTextColor(GOLD); setBackgroundColor(CARD)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44))
        lp.topMargin = dp(6); layoutParams = lp
        setOnClickListener { onClick() }
    }
    private fun radio(t: String) = RadioButton(this).apply { id = View.generateViewId(); text = t; setTextColor(TEXT); textSize = 13f }

    // ====== منطق ======
    private fun pickAudio() {
        try {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "audio/*"
                addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivityForResult(i, 555)
        } catch (_: Exception) {
            Toast.makeText(this, "تعذّر فتح منتقي الملفات", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 555 && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {}
            if (pickTarget == "wake") {
                Prefs.sp(this).edit().putString(Prefs.K_WAKE_URI, uri.toString()).apply()
                wakeLabel?.text = soundName(uri.toString(), "")
            } else {
                Prefs.sp(this).edit().putString(Prefs.K_ADHAN_URI, uri.toString()).apply()
                adhanLabel?.text = soundName(uri.toString(), "")
            }
        }
    }

    private fun soundName(uri: String, def: String): String {
        if (uri.isEmpty()) return def
        return "ملف مختار ✓"
    }

    private fun testSound(mode: String) {
        val svc = Intent(this, AlarmService::class.java).apply {
            putExtra("mode", mode)
            putExtra("title", "تجربة الأذان"); putExtra("text", Store.loc(this@SettingsActivity))
            putExtra("uri", Prefs.adhanUri(this@SettingsActivity))
            putExtra("vol", Prefs.adhanVol(this@SettingsActivity))
            putExtra("loop", false)
            putExtra("autoStopMs", 20000L)
            putExtra("forceMax", false); putExtra("vibrate", false)
            putExtra("volStart", Prefs.adhanVol(this@SettingsActivity)); putExtra("rampSec", 0)
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
    }

    private fun testWake() {
        Prefs.setSnoozeUsed(this, 0)
        startActivity(Intent(this, AlarmActivity::class.java).apply {
            putExtra("title", "⏰ تجربة الإيقاظ"); putExtra("text", "الصلاةُ خيرٌ من النوم")
        })
        val svc = Intent(this, AlarmService::class.java).apply {
            putExtra("mode", "wake")
            putExtra("title", "تجربة الإيقاظ"); putExtra("text", "الصلاةُ خيرٌ من النوم")
            putExtra("uri", Prefs.wakeUri(this@SettingsActivity))
            putExtra("vol", 100); putExtra("loop", true)
            putExtra("autoStopMs", 120000L)
            putExtra("forceMax", Prefs.wakeForceMax(this@SettingsActivity))
            putExtra("vibrate", Prefs.wakeVib(this@SettingsActivity))
            putExtra("volStart", Prefs.wakeVolStart(this@SettingsActivity))
            putExtra("rampSec", Prefs.wakeRamp(this@SettingsActivity))
        }
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
    }

    private fun openExactAlarm() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "غير مطلوب في هذا الإصدار", Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
    }

    private fun openBattery() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
