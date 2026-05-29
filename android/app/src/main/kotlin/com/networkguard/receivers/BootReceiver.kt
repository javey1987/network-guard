package com.networkguard.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.networkguard.services.AlarmScheduler

/**
 * 开机启动接收器。
 * 重新注册所有持久化的定时闹钟（无需恢复 VPN，AlarmManager 到点会自动启动）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // 恢复所有定时闹钟
        val alarmScheduler = AlarmScheduler(context)
        alarmScheduler.rescheduleAllOnBoot()
    }
}
