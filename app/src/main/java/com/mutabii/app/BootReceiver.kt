package com.mutabii.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * يُعيد جدولة المنبّهات بعد الإقلاع أو بعد تحديث التطبيق.
 * المستقبِل مُصدَّر (النظام هو من يبثّ BOOT_COMPLETED)، فنتحقّق من نوع البثّ
 * حتى لا يستنزف تطبيقٌ آخر بطاريتك ببثّ متكرّر يُعيد الجدولة بلا سبب.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val ok = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> true
            else -> false
        }
        if (!ok) return
        try { AlarmScheduler.rescheduleAll(context) } catch (_: Exception) {}
        // تذكيرات البرّ + فحص صلة الرحم اليومي تصمد بعد الإقلاع/تحديث التطبيق
        try { AlarmScheduler.rescheduleReminders(context) } catch (_: Exception) {}
        try { AlarmScheduler.scheduleKinCheck(context, ReminderStore.kinCheck(context)) } catch (_: Exception) {}
    }
}
