package com.mutabii.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try { AlarmScheduler.rescheduleAll(context) } catch (_: Exception) {}
    }
}
