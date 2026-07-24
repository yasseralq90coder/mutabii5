package com.mutabii.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * أزرار مشغّل القرآن في الإشعار.
 *
 * لماذا مستقبِل منفصل بدل إرسالها إلى MediaService مباشرة؟
 * لأن MediaService مُصدَّرة إجباراً (أندرويد أوتو يشترط ذلك عبر MediaBrowserService)،
 * وكانت أوامر التشغيل/الإيقاف تصل إليها عبر Intent — أي أن أيّ تطبيق مثبَّت على الجهاز
 * كان يستطيع إيقاف تلاوتك أو تبديل السورة. هذا المستقبِل غير مُصدَّر، فلا يصله إلا نحن.
 */
class MediaButtonReceiver : BroadcastReceiver() {

    companion object {
        const val ACT_PLAY = "com.mutabii.app.MB_PLAY"
        const val ACT_PAUSE = "com.mutabii.app.MB_PAUSE"
        const val ACT_NEXT = "com.mutabii.app.MB_NEXT"
        const val ACT_PREV = "com.mutabii.app.MB_PREV"
        const val ACT_STOP = "com.mutabii.app.MB_STOP"
    }

    override fun onReceive(c: Context, i: Intent) {
        try {
            when (i.action) {
                ACT_PLAY -> WebHolder.cmd("play")
                ACT_PAUSE -> WebHolder.cmd("pause")
                ACT_NEXT -> WebHolder.cmd("next")
                ACT_PREV -> WebHolder.cmd("prev")
                // الصفحة هي من يوقف الصوت؛ ثم تُبلّغ الطبقة الأصلية فتطوي الإشعار
                ACT_STOP -> { WebHolder.cmd("stop"); MediaService.stop(c) }
            }
        } catch (_: Exception) {}
    }
}
