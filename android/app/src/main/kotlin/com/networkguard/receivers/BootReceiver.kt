package com.networkguard.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.networkguard.services.AlarmScheduler
import com.networkguard.services.SchedulerService

/**
 * 开机广播接收器。
 * 恢复所有持久化的定时规则，并启动常驻调度服务。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Log.i("BootReceiver", "手机开机，恢复定时规则 + 启动调度服务")

        // 1. 恢复 AlarmManager 闹钟（传统方案）
        val alarmScheduler = AlarmScheduler(context)
        alarmScheduler.rescheduleAllOnBoot()

        // 2. 启动常驻前台调度服务（国产手机兼容方案）
        SchedulerService.start(context)
    }
}
