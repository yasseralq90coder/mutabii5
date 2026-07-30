package com.mutabii.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AlarmActivity : Activity() {

    private var answer = 0
    private var tapsLeft = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()

        val ttl = intent.getStringExtra("title") ?: "⏰ الإيقاظ لصلاة الفجر"
        val sub = intent.getStringExtra("text") ?: "الصلاةُ خيرٌ من النوم"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#1A1410"))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(28), dp(40), dp(28), dp(40))
        }

        val clock = TextView(this).apply {
            text = SimpleDateFormat("hh:mm", Locale.US).format(Date())
            textSize = 64f
            setTextColor(Color.parseColor("#C9A44C"))
            gravity = Gravity.CENTER
        }
        val emoji = TextView(this).apply {
            text = "⏰"; textSize = 56f; gravity = Gravity.CENTER
        }
        val t1 = TextView(this).apply {
            text = "حيّ على الفلاح"
            textSize = 26f; setTextColor(Color.parseColor("#C9A44C"))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(4))
        }
        val t2 = TextView(this).apply {
            text = sub
            textSize = 18f; setTextColor(Color.parseColor("#F2E9D8"))
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(24))
        }

        root.addView(clock); root.addView(emoji); root.addView(t1); root.addView(t2)

        val challenge = Prefs.wakeChallenge(this)
        val hintLbl = TextView(this).apply {
            textSize = 15f; setTextColor(Color.parseColor("#9A8A6E"))
            gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10))
        }

        when (challenge) {
            "math" -> {
                val (q, a) = makeMath(Prefs.wakeMathLvl(this))
                answer = a
                hintLbl.text = "للإيقاف، احسب: $q ="
                val input = EditText(this).apply {
                    inputType = InputType.TYPE_CLASS_NUMBER
                    textSize = 22f; gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setHint("الإجابة")
                }
                val verify = bigButton("✅ إيقاف المنبّه") {
                    val v = input.text.toString().trim().toIntOrNull()
                    if (v == answer) dismiss()
                    else {
                        val (q2, a2) = makeMath(Prefs.wakeMathLvl(this))
                        answer = a2; hintLbl.text = "خطأ! احسب: $q2 ="
                        input.setText("")
                    }
                }
                root.addView(hintLbl); root.addView(input); root.addView(verify)
            }
            "tap" -> {
                tapsLeft = Prefs.wakeTaps(this).coerceAtLeast(1)
                hintLbl.text = "اضغط الزر $tapsLeft مرة للإيقاف"
                root.addView(hintLbl)
                val tapBtn = bigButton("اضغط ($tapsLeft)") {}
                tapBtn.setOnClickListener {
                    tapsLeft--
                    if (tapsLeft <= 0) dismiss()
                    else tapBtn.text = "اضغط ($tapsLeft)"
                }
                root.addView(tapBtn)
            }
            else -> {
                root.addView(bigButton("✅ قُمت — إيقاف") { dismiss() })
            }
        }

        // غفوة
        val used = Prefs.snoozeUsed(this)
        val max = Prefs.wakeSnoozeMax(this)
        if (used < max) {
            val sm = Prefs.wakeSnoozeMin(this)
            root.addView(TextView(this).apply { text = ""; setPadding(0, dp(8), 0, 0) })
            root.addView(bigButton("😴 غفوة $sm دقائق (${max - used} متبقية)") {
                Prefs.setSnoozeUsed(this@AlarmActivity, used + 1)
                stopSound()
                AlarmScheduler.scheduleSnooze(this@AlarmActivity, sm)
                finish()
            }.apply {
                setBackgroundColor(Color.parseColor("#2A2116"))
            })
        }

        val scroll = android.widget.ScrollView(this).apply {
            addView(root)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        setContentView(scroll)
    }

    private fun setupWindow() {
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            try {
                (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager)
                    .requestDismissKeyguard(this, null)
            } catch (_: Exception) {}
        }
    }

    private fun makeMath(level: Int): Pair<String, Int> {
        val r = java.util.Random()
        return when (level.coerceIn(1, 3)) {
            1 -> { val a = 2 + r.nextInt(8); val b = 2 + r.nextInt(8); Pair("$a + $b", a + b) }
            2 -> { val a = 5 + r.nextInt(15); val b = 3 + r.nextInt(12); Pair("$a × $b", a * b) }
            else -> {
                val a = 11 + r.nextInt(20); val b = 11 + r.nextInt(20); val cc = 2 + r.nextInt(8)
                Pair("$a + $b × $cc", a + b * cc)
            }
        }
    }

    private fun bigButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            textSize = 18f
            setTextColor(Color.parseColor("#1A1410"))
            setBackgroundColor(Color.parseColor("#C9A44C"))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)
            )
            lp.topMargin = dp(10)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun stopSound() {
        try {
            startService(Intent(this, AlarmService::class.java).apply { action = "STOP" })
        } catch (_: Exception) {}
    }

    private fun dismiss() {
        stopSound()
        Prefs.setSnoozeUsed(this, 0)
        Prefs.resetWakeRetries(this)   // قُمتَ فعلاً — أوقف المعاودات
        // ألغِ أي معاودة/غفوة معلّقة، وإلا رنّت بعد أن نهضت فعلاً
        try { AlarmScheduler.cancelWakeExtras(this) } catch (_: Exception) {}
        try { AlarmScheduler.rescheduleAll(this) } catch (_: Exception) {}
        finish()
    }

    override fun onBackPressed() { /* لا يُغلق بالرجوع */ }

    /** أزرار الصوت لا تُسكت الإيقاظ ولا تُغلق الشاشة — الإيقاف بالتحدّي وحده. */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_VOLUME_UP,
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN,
            android.view.KeyEvent.KEYCODE_VOLUME_MUTE,
            android.view.KeyEvent.KEYCODE_CAMERA,
            android.view.KeyEvent.KEYCODE_HEADSETHOOK -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
