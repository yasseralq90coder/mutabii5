package com.mutabii.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.app.Notification

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra("type") ?: return
        Notif.ensure(context)
        try {
            when (type) {
                "maint" -> { /* إعادة الجدولة فقط */ }
                "wake" -> fireWake(context, intent)
                "pre" -> firePre(context, intent)
                "reminder" -> fireReminder(context, intent)
                "kincheck" -> fireKinCheck(context)
                else -> fireAdhan(context, intent, type) // adhan | iqama
            }
        } catch (_: Exception) {}
        // إعادة تعبئة أفق جدولة الصلاة بعد كل إطلاق — عدا التذكيرات (تدير جدولتها بنفسها)
        if (type != "reminder" && type != "kincheck") {
            try { AlarmScheduler.rescheduleAll(context) } catch (_: Exception) {}
        }
    }

    private fun firePre(c: Context, i: Intent) {
        val title = i.getStringExtra("title") ?: "اقترب وقت الصلاة"
        val text = i.getStringExtra("text") ?: ""
        val open = PendingIntent.getActivity(
            c, 11, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, Notif.CH_PRE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title).setContentText(text)
            .setAutoCancel(true).setContentIntent(open)
            .build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(Notif.ID_PRE, n)
    }

    private fun fireAdhan(c: Context, i: Intent, type: String) {
        val title = i.getStringExtra("title") ?: "الأذان"
        val text = i.getStringExtra("text") ?: ""
        // الصوت المختار: أذان الحرمين المضمّن، أو ملف المستخدم، أو النغمة الافتراضية
        val sound = Prefs.adhanSound(c)
        val resId = SoundLib.adhanRes(c)
        val uri = if (sound == "custom") Prefs.adhanUri(c) else ""
        // «التكبيرة فقط» = قصّ أول مدّة بإيقاف التشغيل بعدها
        val stopMs = if (Prefs.adhanTakbeer(c)) Prefs.adhanTakbeerSec(c) * 1000L
                     else Prefs.adhanStopSec(c) * 1000L
        val svc = Intent(c, AlarmService::class.java).apply {
            putExtra("mode", "adhan")
            putExtra("title", title); putExtra("text", text)
            putExtra("uri", uri)
            putExtra("resId", resId)
            putExtra("vol", Prefs.adhanVol(c))
            putExtra("loop", false)
            putExtra("autoStopMs", stopMs)
            putExtra("forceMax", false)
            putExtra("vibrate", false)
            putExtra("volStart", Prefs.adhanVol(c))
            putExtra("rampSec", 0)
        }
        startFg(c, svc)
    }

    private fun fireWake(c: Context, i: Intent) {
        val title = i.getStringExtra("title") ?: "الإيقاظ"
        val text = i.getStringExtra("text") ?: "الصلاةُ خيرٌ من النوم"
        // إيقاظ اليوم الجديد يستعيد رصيد المعاودات؛ المعاودة نفسها لا تستعيده وإلا دارت بلا نهاية
        if (!i.getBooleanExtra("retry", false)) {
            try { Prefs.resetWakeRetries(c); Prefs.setSnoozeUsed(c, 0) } catch (_: Exception) {}
        }
        // ابدأ الصوت القوي عبر الخدمة
        val wakeSound = Prefs.wakeSound(c)
        val wakeRes = SoundLib.wakeRes(c)
        val wakeUri = if (wakeSound == "custom") Prefs.wakeUri(c) else ""
        val svc = Intent(c, AlarmService::class.java).apply {
            putExtra("mode", "wake")
            putExtra("title", title); putExtra("text", text)
            putExtra("uri", wakeUri)
            putExtra("resId", wakeRes)
            putExtra("vol", 100)
            putExtra("loop", true)
            putExtra("autoStopMs", Prefs.wakeAutoStop(c) * 60000L)
            putExtra("forceMax", Prefs.wakeForceMax(c))
            putExtra("vibrate", Prefs.wakeVib(c))
            putExtra("volStart", Prefs.wakeVolStart(c))
            putExtra("rampSec", Prefs.wakeRamp(c))
        }
        startFg(c, svc)

        // شاشة الإيقاظ الكاملة: يحملها الآن إشعار الخدمة نفسه (full-screen intent) بمعرّف ID_WAKE،
        // فلا نبثّ إشعاراً منفصلاً بنفس المعرّف حتى لا يدهس أحدهما الآخر.
        // نبقي المحاولة المباشرة احتياطاً (تُحجَب على الإصدارات الحديثة لكنها غير ضارّة).
        val act = Intent(c, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("title", title); putExtra("text", text)
        }
        try { c.startActivity(act) } catch (_: Exception) {}
    }

    private fun fireReminder(c: Context, i: Intent) {
        val rid = i.getStringExtra("rid") ?: return
        // إن أُلغيت المهمة (لم تعد في المخزن) فلا نُطلق ولا نعاود
        if (ReminderStore.get(c, rid) == null) return
        val title = i.getStringExtra("title") ?: "🤲 تذكير برّ"
        val text = i.getStringExtra("text") ?: ""
        val rep = i.getIntExtra("rep", 0)
        val open = PendingIntent.getActivity(
            c, 41, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, Notif.CH_TASK)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title).setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true).setContentIntent(open)
            .build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(Notif.ID_TASK_BASE + Math.abs(rid.hashCode() % 1000), n)
        // معاودة كل rep دقيقة حتى تُنجز المهمة (تُلغى بإنجازها من التطبيق)
        if (rep > 0) {
            val next = System.currentTimeMillis() + rep * 60000L
            try { AlarmScheduler.scheduleReminder(c, rid, next, title, text, rep) } catch (_: Exception) {}
        } else {
            try { ReminderStore.remove(c, rid) } catch (_: Exception) {}
        }
    }

    private fun fireKinCheck(c: Context) {
        val open = PendingIntent.getActivity(
            c, 42, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, Notif.CH_TASK)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("🤲 صلة الرحم")
            .setContentText("تفقّد من لم تصله من أهلك اليوم — سلامٌ أو اتصال يكفي")
            .setCategory(Notification.CATEGORY_REMINDER)
            .setAutoCancel(true).setContentIntent(open)
            .build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(Notif.ID_KIN, n)
        // أعد جدولة فحص الغد
        try { AlarmScheduler.scheduleKinCheck(c, ReminderStore.kinCheck(c)) } catch (_: Exception) {}
    }

    private fun startFg(c: Context, svc: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(svc)
            else c.startService(svc)
        } catch (_: Exception) {
            try { c.startService(svc) } catch (_: Exception) {}
        }
    }
}
