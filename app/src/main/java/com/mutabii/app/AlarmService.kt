package com.mutabii.app

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.app.Notification

class AlarmService : Service() {

    private var mp: MediaPlayer? = null
    private var wl: PowerManager.WakeLock? = null
    private var vib: Vibrator? = null
    private val h = Handler(Looper.getMainLooper())
    private var stopper: Runnable? = null
    private var ramper: Runnable? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") { stopEverything(); return START_NOT_STICKY }
        Notif.ensure(this)

        val mode = intent?.getStringExtra("mode") ?: "adhan"
        val title = intent?.getStringExtra("title") ?: "مُتابِعي"
        val text = intent?.getStringExtra("text") ?: ""
        val uriStr = intent?.getStringExtra("uri") ?: ""
        val loop = intent?.getBooleanExtra("loop", false) ?: false
        val autoStopMs = intent?.getLongExtra("autoStopMs", 0L) ?: 0L
        val forceMax = intent?.getBooleanExtra("forceMax", false) ?: false
        val vibrate = intent?.getBooleanExtra("vibrate", false) ?: false
        val volStart = (intent?.getIntExtra("volStart", 80) ?: 80).coerceIn(0, 100)
        val rampSec = intent?.getIntExtra("rampSec", 0) ?: 0

        startForegroundNotif(mode, title, text)
        acquireWake()
        forceStreamVolume(forceMax)
        startAudio(mode, uriStr, loop, volStart, rampSec)
        if (vibrate) startVibrate()

        if (autoStopMs > 0) {
            stopper = Runnable { stopEverything() }
            h.postDelayed(stopper!!, autoStopMs)
        }
        return START_STICKY
    }

    private fun startForegroundNotif(mode: String, title: String, text: String) {
        val stopIntent = Intent(this, AlarmService::class.java).apply { action = "STOP" }
        val stopPi = PendingIntent.getService(
            this, 33, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 34, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val ch = if (mode == "wake") Notif.CH_WAKE else Notif.CH_ADHAN
        val stopLabel = if (mode == "wake") "🛑 إيقاف" else "🛑 إيقاف الأذان"
        @Suppress("DEPRECATION")
        val n = Notification.Builder(this, ch)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title).setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .addAction(R.drawable.ic_notify, stopLabel, stopPi)
            .build()
        val id = if (mode == "wake") Notif.ID_WAKE else Notif.ID_ADHAN
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(id, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(id, n)
        }
    }

    private fun forceStreamVolume(forceMax: Boolean) {
        try {
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val target = if (forceMax) max else (max * 0.9).toInt().coerceAtLeast(1)
            am.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
        } catch (_: Exception) {}
    }

    private fun startAudio(mode: String, uriStr: String, loop: Boolean, volStart: Int, rampSec: Int) {
        try {
            mp?.release()
            val p = MediaPlayer()
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            var usedFallback = false
            try {
                if (uriStr.isNotEmpty()) {
                    p.setDataSource(this, Uri.parse(uriStr))
                } else usedFallback = true
            } catch (_: Exception) { usedFallback = true }

            if (usedFallback) {
                val res = if (mode == "wake") R.raw.wake_tone else R.raw.adhan_tone
                val afd = resources.openRawResourceFd(res)
                p.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
            }
            p.isLooping = loop
            val v0 = (volStart.coerceIn(0, 100)) / 100f
            p.setVolume(v0, v0)
            p.setOnPreparedListener {
                it.start()
                if (rampSec > 0) startRamp(volStart, rampSec)
            }
            p.setOnCompletionListener {
                if (!loop) stopEverything()
            }
            p.setOnErrorListener { _, _, _ -> true }
            p.prepareAsync()
            mp = p
        } catch (_: Exception) {
            // احتياطي أخير: نغمة مضمّنة
            try {
                val res = if (mode == "wake") R.raw.wake_tone else R.raw.adhan_tone
                mp = MediaPlayer.create(this, res)?.apply {
                    isLooping = loop
                    start()
                    setOnCompletionListener { if (!loop) stopEverything() }
                }
            } catch (_: Exception) {}
        }
    }

    private fun startRamp(startPct: Int, rampSec: Int) {
        val steps = 30
        val interval = (rampSec * 1000L / steps).coerceAtLeast(200)
        var step = 0
        ramper = object : Runnable {
            override fun run() {
                step++
                val frac = step.toFloat() / steps
                val v = (startPct / 100f) + frac * (1f - startPct / 100f)
                try { mp?.setVolume(v.coerceIn(0f, 1f), v.coerceIn(0f, 1f)) } catch (_: Exception) {}
                if (step < steps) h.postDelayed(this, interval)
            }
        }
        h.postDelayed(ramper!!, interval)
    }

    private fun startVibrate() {
        try {
            vib = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            }
            val pattern = longArrayOf(0, 600, 400, 600, 400, 900, 700)
            vib?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        } catch (_: Exception) {}
    }

    private fun acquireWake() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wl = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "mtb:alarm"
            )
            wl?.acquire(20 * 60 * 1000L)
        } catch (_: Exception) {}
    }

    private fun stopEverything() {
        try { stopper?.let { h.removeCallbacks(it) } } catch (_: Exception) {}
        try { ramper?.let { h.removeCallbacks(it) } } catch (_: Exception) {}
        try { mp?.stop() } catch (_: Exception) {}
        try { mp?.release() } catch (_: Exception) {}
        mp = null
        try { vib?.cancel() } catch (_: Exception) {}
        try { if (wl?.isHeld == true) wl?.release() } catch (_: Exception) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        stopEverything()
        super.onDestroy()
    }
}
