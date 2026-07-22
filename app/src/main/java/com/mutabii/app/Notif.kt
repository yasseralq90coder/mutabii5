package com.mutabii.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

object Notif {
    const val CH_ADHAN = "mtb_adhan"
    const val CH_PRE = "mtb_pre"
    const val CH_WAKE = "mtb_wake"
    const val CH_MEDIA = "mtb_media"
    const val ID_ADHAN = 1001
    const val ID_WAKE = 1002
    const val ID_PRE = 1003

    fun ensure(c: Context) {
        val nm = c.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        chan(nm, CH_ADHAN, "الأذان والإقامة", NotificationManager.IMPORTANCE_HIGH, false, true)
        chan(nm, CH_PRE, "قرب وقت الصلاة", NotificationManager.IMPORTANCE_DEFAULT, true, true)
        chan(nm, CH_WAKE, "منبّه الاستيقاظ", NotificationManager.IMPORTANCE_HIGH, false, true)
        chan(nm, CH_MEDIA, "مشغّل القرآن", NotificationManager.IMPORTANCE_LOW, false, false)
    }

    private fun chan(
        nm: NotificationManager, id: String, name: String,
        imp: Int, sound: Boolean, vibrate: Boolean
    ) {
        val ch = NotificationChannel(id, name, imp)
        ch.enableVibration(vibrate)
        if (imp >= NotificationManager.IMPORTANCE_HIGH) ch.setBypassDnd(true)
        if (!sound) ch.setSound(null, null)
        nm.createNotificationChannel(ch)
    }
}
