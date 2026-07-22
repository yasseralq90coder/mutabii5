package com.mutabii.app

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.service.media.MediaBrowserService

/** مشغّل القرآن: شريط في الإشعارات + شاشة القفل + أندرويد أوتو. */
class MediaService : MediaBrowserService() {

    companion object {
        const val ACT_UPDATE = "com.mutabii.app.M_UPDATE"
        const val ACT_STOP = "com.mutabii.app.M_STOP"
        const val ACT_PLAY = "com.mutabii.app.M_PLAY"
        const val ACT_PAUSE = "com.mutabii.app.M_PAUSE"
        const val ACT_NEXT = "com.mutabii.app.M_NEXT"
        const val ACT_PREV = "com.mutabii.app.M_PREV"
        const val NOTIF_ID = 2001
        private const val ROOT = "mtb_root"
        private const val NODE_SURAHS = "mtb_surahs"

        fun update(c: Context) {
            try {
                val i = Intent(c, MediaService::class.java).apply { action = ACT_UPDATE }
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
            } catch (_: Exception) {}
        }

        fun stop(c: Context) {
            try { c.startService(Intent(c, MediaService::class.java).apply { action = ACT_STOP }) }
            catch (_: Exception) {}
        }
    }

    private var session: MediaSession? = null
    private var inForeground = false

    override fun onCreate() {
        super.onCreate()
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
                val n = mediaId?.removePrefix("surah_")?.toIntOrNull() ?: return
                WebHolder.cmd("playsurah", n.toString())
            }
        })
        s.setSessionActivity(
            PendingIntent.getActivity(
                this, 60, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        s.isActive = true
        session = s
        sessionToken = s.sessionToken
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACT_STOP -> { WebHolder.cmd("stop"); shutdown(); return START_NOT_STICKY }
            ACT_PLAY -> WebHolder.cmd("play")
            ACT_PAUSE -> WebHolder.cmd("pause")
            ACT_NEXT -> WebHolder.cmd("next")
            ACT_PREV -> WebHolder.cmd("prev")
        }
        refresh()
        return START_STICKY
    }

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
                .setState(st, MediaState.pos, 1.0f)
                .build()
        )

        val n = buildNotif(s)
        // إلزامي: بعد startForegroundService يجب استدعاء startForeground خلال ٥ ثوانٍ
        // وإلا انهار التطبيق (ForegroundServiceDidNotStartInTime) — حتى لو كان متوقفاً مؤقتاً.
        if (!inForeground) {
            try {
                if (Build.VERSION.SDK_INT >= 29)
                    startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                else startForeground(NOTIF_ID, n)
                inForeground = true
            } catch (_: Exception) { notifyOnly(n) }
        } else {
            notifyOnly(n)
        }
        // عند الإيقاف المؤقت: أبقِ الإشعار ظاهراً لكن افصل الخدمة عن الواجهة الأمامية
        if (!MediaState.playing && inForeground) {
            try { stopForeground(Service.STOP_FOREGROUND_DETACH) } catch (_: Exception) {}
            inForeground = false
        }
    }

    private fun notifyOnly(n: Notification) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, n)
        } catch (_: Exception) {}
    }

    private fun svcPI(action: String, code: Int): PendingIntent {
        val i = Intent(this, MediaService::class.java).apply { this.action = action }
        return PendingIntent.getService(
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
            .setDeleteIntent(svcPI(ACT_STOP, 65))

        b.addAction(R.drawable.ic_prev, "السابقة", svcPI(ACT_PREV, 62))
        if (MediaState.playing) b.addAction(R.drawable.ic_pause, "إيقاف مؤقت", svcPI(ACT_PAUSE, 63))
        else b.addAction(R.drawable.ic_play, "تشغيل", svcPI(ACT_PLAY, 63))
        b.addAction(R.drawable.ic_next, "التالية", svcPI(ACT_NEXT, 64))

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
        stopSelf()
    }

    override fun onDestroy() {
        try { session?.isActive = false; session?.release() } catch (_: Exception) {}
        session = null
        super.onDestroy()
    }

    // ===== أندرويد أوتو =====
    override fun onGetRoot(
        clientPackageName: String, clientUid: Int, rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(ROOT, null)

    override fun onLoadChildren(
        parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>
    ) {
        val items = ArrayList<MediaBrowser.MediaItem>()
        try {
            if (parentId == ROOT) {
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
