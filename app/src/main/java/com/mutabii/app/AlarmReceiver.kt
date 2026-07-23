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
                else -> fireAdhan(context, intent, type) // adhan | iqama
            }
        } catch (_: Exception) {}
        // إعادة تعبئة أفق الجدولة بعد كل إطلاق
        try { AlarmScheduler.rescheduleAll(context) } catch (_: Exception) {}
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
        val svc = Intent(c, AlarmService::class.java).apply {
            putExtra("mode", "adhan")
            putExtra("title", title); putExtra("text", text)
            putExtra("uri", Prefs.adhanUri(c))
            putExtra("vol", Prefs.adhanVol(c))
            putExtra("loop", false)
            putExtra("autoStopMs", Prefs.adhanStopSec(c) * 1000L)
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
        val svc = Intent(c, AlarmService::class.java).apply {
            putExtra("mode", "wake")
            putExtra("title", title); putExtra("text", text)
            putExtra("uri", Prefs.wakeUri(c))
            putExtra("vol", 100)
            putExtra("loop", true)
            putExtra("autoStopMs", Prefs.wakeAutoStop(c) * 60000L)
            putExtra("forceMax", Prefs.wakeForceMax(c))
            putExtra("vibrate", Prefs.wakeVib(c))
            putExtra("volStart", Prefs.wakeVolStart(c))
            putExtra("rampSec", Prefs.wakeRamp(c))
        }
        startFg(c, svc)

        // شاشة الإيقاظ الكاملة عبر full-screen intent
        val act = Intent(c, AlarmActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("title", title); putExtra("text", text)
        }
        val fsPi = PendingIntent.getActivity(
            c, 22, act,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            c, 23, act,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(c, Notif.CH_WAKE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title).setContentText(text)
            .setCategory(Notification.CATEGORY_ALARM)
            .setOngoing(true)
            .setFullScreenIntent(fsPi, true)
            .setContentIntent(open)
            .build()
        (c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(Notif.ID_WAKE, n)

        // محاولة تشغيل مباشر أيضاً (احتياطي)
        try { c.startActivity(act) } catch (_: Exception) {}
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
