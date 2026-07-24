package com.mutabii.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.service.media.MediaBrowserService

/**
 * قشرة MediaBrowserService — لا تشغّل صوتاً بنفسها إطلاقاً.
 * محرّك الصوت هو عنصر <audio> داخل WebView؛ هذه الخدمة تعكس الحالة وتمرّر أزرار التحكّم.
 *
 * ⚠️ تحذير دائم: لا تطلب هذه الخدمة تركيز الصوت (AudioFocus) أبداً.
 * WebView (Chromium) يملك تركيز الصوت لعنصر <audio>. وأيّ طلب AUDIOFOCUS_GAIN من هنا
 * يسحب التركيز منه، فيوقف Chromium التشغيل فوراً. ولأن الصفحة كانت تُرسل تحديث الحالة
 * كل ثانية، كان أوّل تحديث بعد الضغط على ▶ يسرق التركيز — فيعمل الصوت ثانية ثم ينطفئ.
 * هذا كان سبب العطل بالضبط. لا تُعِد إضافة requestAudioFocus هنا.
 */
class MediaService : MediaBrowserService() {

    companion object {
        /** يحدّث الإشعار من MediaState — بلا أي أثر على التشغيل نفسه. */
        const val ACT_UPDATE = "com.mutabii.app.M_UPDATE"

        /** يطوي الإشعار والخدمة بلا إرسال أمر «stop» عائد إلى الصفحة (وإلا دارت الحلقة). */
        const val ACT_SHUTDOWN = "com.mutabii.app.M_SHUTDOWN"

        const val NOTIF_ID = 2001
        private const val ROOT = "mtb_root"
        private const val EMPTY_ROOT = "mtb_empty"
        private const val NODE_SURAHS = "mtb_surahs"

        /**
         * مرجع الخدمة الحيّة. الصفحة تُحدّث الحالة كثيراً، وإطلاق
         * startForegroundService في كل مرّة عملية ثقيلة وقد ترفضها أندرويد ١٢+
         * حين يكون التطبيق في الخلفية — فنستدعي الخدمة القائمة مباشرة.
         */
        @Volatile private var live: MediaService? = null
        private val main = Handler(Looper.getMainLooper())

        fun update(c: Context) {
            val s = live
            if (s != null) {
                main.post { try { s.refresh() } catch (_: Exception) {} }
                return
            }
            try {
                val i = Intent(c, MediaService::class.java).apply { action = ACT_UPDATE }
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (_: Exception) {}
        }

        fun stop(c: Context) {
            val s = live
            if (s != null) {
                main.post { try { s.shutdown() } catch (_: Exception) {} }
                return
            }
            try { c.startService(Intent(c, MediaService::class.java).apply { action = ACT_SHUTDOWN }) }
            catch (_: Exception) {}
        }

        /**
         * عملاء متصفّح الوسائط المسموح لهم برؤية قائمة السور.
         * الخدمة مُصدَّرة إجباراً (أندرويد أوتو يتطلّب ذلك)، فلولا هذا الفحص
         * لاستطاع أيّ تطبيق مثبَّت تصفّح محتوانا والتحكّم بالتشغيل.
         */
        private val ALLOWED_CLIENTS = setOf(
            "com.google.android.projection.gearhead",   // أندرويد أوتو
            "com.google.android.autosimulator",
            "com.google.android.carassistant",
            "com.google.android.googlequicksearchbox",  // مساعد قوقل
            "com.google.android.wearable.app",          // Wear OS
            "com.android.bluetooth",                    // سمّاعات ومشغّلات السيارة
            "com.android.systemui"
        )

        /** العملاء الذين يعني اتصالهم أنك في السيارة (أندرويد أوتو ومحاكيه). */
        private val CAR_CLIENTS = setOf(
            "com.google.android.projection.gearhead",
            "com.google.android.autosimulator"
        )
        @Volatile private var lastCarPing = 0L
    }

    private var session: MediaSession? = null
    private var inForeground = false
    private var lastSig = ""

    override fun onCreate() {
        super.onCreate()
        live = this
        Notif.ensure(this)

        val s = MediaSession(this, "MutabiiQuran")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { WebHolder.cmd("play") }
            override fun onPause() { WebHolder.cmd("pause") }
            override fun onStop() { WebHolder.cmd("stop") }
            override fun onSkipToNext() { WebHolder.cmd("next") }
            override fun onSkipToPrevious() { WebHolder.cmd("prev") }
            override fun onFastForward() { WebHolder.cmd("ff", "30") }
            override fun onRewind() { WebHolder.cmd("rw", "10") }
            override fun onSeekTo(pos: Long) { WebHolder.cmd("seek", pos.toString()) }
            override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                if (mediaId == "resume_khatma") {
                    WebHolder.cmd("resumekhatma", "0")
                    return
                }
                val n = mediaId?.removePrefix("surah_")?.toIntOrNull() ?: return
                if (n < 1 || n > 114) return
                WebHolder.cmd("playsurah", n.toString())
            }
        })
        s.setSessionActivity(
            PendingIntent.getActivity(
                this, 60, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        // أزرار الصوت تُوجَّه لمجرى الوسائط — نفس المجرى الذي يشغّل عليه WebView التلاوة
        try {
            s.setPlaybackToLocal(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
        } catch (_: Exception) {}
        s.isActive = true
        session = s
        sessionToken = s.sessionToken
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // عقد startForegroundService: لا بدّ من startForeground خلال ٥ ثوانٍ وإلا قُتلت العملية،
        // حتى لو كان التشغيل متوقّفاً مؤقتاً. لذلك نرفعها أولاً ثم تقرّر refresh الفصل من عدمه.
        if (intent?.action == ACT_SHUTDOWN) { shutdown(); return START_NOT_STICKY }
        ensureForeground()
        refresh()
        return START_STICKY
    }

    /**
     * يرفع الخدمة إلى المقدّمة بإشعار صالح.
     * بعد startForegroundService لا بدّ من startForeground خلال ٥ ثوانٍ وإلا قُتلت العملية
     * (ومعها الـWebView، أي الصوت). لذلك حتى لو فشل بناء الإشعار الكامل نرفع إشعاراً بسيطاً.
     */
    private fun ensureForeground() {
        if (inForeground) return
        val s = session ?: return
        if (MediaState.surah <= 0) return
        val n = try { buildNotif(s) } catch (_: Exception) { minimalNotif() }
        try {
            if (Build.VERSION.SDK_INT >= 29)
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            else startForeground(NOTIF_ID, n)
            inForeground = true
            lastSig = sig()
        } catch (_: Exception) {
            try {
                if (Build.VERSION.SDK_INT >= 29)
                    startForeground(NOTIF_ID, minimalNotif(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                else startForeground(NOTIF_ID, minimalNotif())
                inForeground = true
                lastSig = sig()
            } catch (_: Exception) {}
        }
    }

    private fun minimalNotif(): Notification =
        Notification.Builder(this, Notif.CH_MEDIA)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (MediaState.title.isNotEmpty()) MediaState.title else "القرآن الكريم")
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .build()

    /** بصمة ما يظهر في الإشعار فقط — الموضع خارجها عمداً (أندرويد يستنتجه من PlaybackState). */
    private fun sig(): String =
        "${MediaState.surah}|${MediaState.playing}|${MediaState.title}|${MediaState.artist}"

    private fun refresh() {
        val s = session ?: return
        if (MediaState.surah <= 0) { shutdown(); return }

        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, MediaState.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, MediaState.artist)
                .putString(MediaMetadata.METADATA_KEY_ALBUM, "القرآن الكريم")
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, MediaState.title)
                .putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaState.artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, if (MediaState.dur > 0) MediaState.dur else -1L)
                .build()
        )

        val st = if (MediaState.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        s.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or
                        PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or
                        PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_SEEK_TO or
                        PlaybackState.ACTION_FAST_FORWARD or
                        PlaybackState.ACTION_REWIND or
                        PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
                )
                // السرعة ١٫٠ أثناء التشغيل تجعل النظام يُحرّك الموضع بنفسه،
                // فلا نحتاج دفع تحديث كل ثانية من الصفحة.
                .setState(st, MediaState.pos, if (MediaState.playing) 1.0f else 0f)
                .build()
        )

        val cur = sig()
        if (MediaState.playing) {
            if (!inForeground) ensureForeground()
            else if (cur != lastSig) { notifyOnly(buildNotif(s)); lastSig = cur }
        } else {
            if (cur != lastSig || inForeground) { notifyOnly(buildNotif(s)); lastSig = cur }
            if (inForeground) {
                // نفصل الإشعار عن المقدّمة عند الإيقاف المؤقّت ليصير قابلاً للإزالة
                try { stopForeground(Service.STOP_FOREGROUND_DETACH) } catch (_: Exception) {}
                inForeground = false
            }
        }
    }

    private fun notifyOnly(n: Notification) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
        } catch (_: Exception) {}
    }

    /**
     * أزرار الإشعار تمرّ بمستقبِل غير مُصدَّر لا بالخدمة نفسها.
     * الخدمة مُصدَّرة (لأندرويد أوتو)، فتوجيه أوامر التشغيل إليها كان يفتح للتطبيقات
     * الأخرى بابَ التحكّم بالتشغيل وإيقافه عن بُعد.
     */
    private fun btnPI(action: String, code: Int): PendingIntent {
        val i = Intent(this, MediaButtonReceiver::class.java).apply { this.action = action }
        return PendingIntent.getBroadcast(
            this, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @Suppress("DEPRECATION")
    private fun buildNotif(s: MediaSession): Notification {
        val open = PendingIntent.getActivity(
            this, 61, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = Notification.Builder(this, Notif.CH_MEDIA)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(MediaState.title)
            .setContentText(MediaState.artist)
            .setSubText("القرآن الكريم")
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setDeleteIntent(btnPI(MediaButtonReceiver.ACT_STOP, 65))

        b.addAction(R.drawable.ic_prev, "السابقة", btnPI(MediaButtonReceiver.ACT_PREV, 62))
        if (MediaState.playing)
            b.addAction(R.drawable.ic_pause, "إيقاف مؤقت", btnPI(MediaButtonReceiver.ACT_PAUSE, 63))
        else
            b.addAction(R.drawable.ic_play, "تشغيل", btnPI(MediaButtonReceiver.ACT_PLAY, 63))
        b.addAction(R.drawable.ic_next, "التالية", btnPI(MediaButtonReceiver.ACT_NEXT, 64))

        val style = Notification.MediaStyle()
            .setMediaSession(s.sessionToken)
            .setShowActionsInCompactView(0, 1, 2)
        b.setStyle(style)
        return b.build()
    }

    private fun shutdown() {
        try {
            session?.setPlaybackState(
                PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build()
            )
        } catch (_: Exception) {}
        try { stopForeground(Service.STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
        inForeground = false
        lastSig = ""
        stopSelf()
    }

    override fun onDestroy() {
        if (live === this) live = null
        try { session?.isActive = false; session?.release() } catch (_: Exception) {}
        session = null
        super.onDestroy()
    }

    /** يمنح الجذر الحقيقي للعملاء الموثوقين فقط؛ وغيرهم يرى جذراً فارغاً. */
    override fun onGetRoot(
        clientPackageName: String, clientUid: Int, rootHints: Bundle?
    ): BrowserRoot {
        val trusted = try {
            clientUid == Process.myUid() ||
                clientUid == Process.SYSTEM_UID ||
                ALLOWED_CLIENTS.contains(clientPackageName) ||
                packageManager.checkPermission(
                    android.Manifest.permission.MEDIA_CONTENT_CONTROL, clientPackageName
                ) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }

        // اتصال أندرويد أوتو أو مشغّل السيارة = «أنت في السيارة» — نُبلّغ الصفحة لتشغّل
        // أدعية الركوب صوتياً. نمرّرها مرّة واحدة كل دقيقة حتى لا تتكرّر مع كل استعلام.
        if (CAR_CLIENTS.contains(clientPackageName)) {
            val now = System.currentTimeMillis()
            if (now - lastCarPing > 60000) {
                lastCarPing = now
                WebHolder.eval("window.__mtbCarConnected && window.__mtbCarConnected(true);")
            }
        }
        return BrowserRoot(if (trusted) ROOT else EMPTY_ROOT, null)
    }

    override fun onLoadChildren(
        parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>
    ) {
        val items = ArrayList<MediaBrowser.MediaItem>()
        try {
            if (parentId == ROOT) {
                items.add(
                    MediaBrowser.MediaItem(
                        MediaDescription.Builder()
                            .setMediaId("resume_khatma")
                            .setTitle("إكمال الختمة السماعية")
                            .setSubtitle("متابعة الاستماع من حيث توقفت")
                            .build(),
                        MediaBrowser.MediaItem.FLAG_PLAYABLE
                    )
                )
                items.add(
                    MediaBrowser.MediaItem(
                        MediaDescription.Builder()
                            .setMediaId(NODE_SURAHS)
                            .setTitle("السور")
                            .setSubtitle("القرآن الكريم")
                            .build(),
                        MediaBrowser.MediaItem.FLAG_BROWSABLE
                    )
                )
            } else if (parentId == NODE_SURAHS) {
                val names = SurahNames.LIST
                val sub = if (MediaState.artist.isEmpty()) "القرآن الكريم" else MediaState.artist
                for (i in names.indices) {
                    items.add(
                        MediaBrowser.MediaItem(
                            MediaDescription.Builder()
                                .setMediaId("surah_" + (i + 1))
                                .setTitle("${i + 1}. ${names[i]}")
                                .setSubtitle(sub)
                                .build(),
                            MediaBrowser.MediaItem.FLAG_PLAYABLE
                        )
                    )
                }
            }
        } catch (_: Exception) {}
        result.sendResult(items)
    }
}
