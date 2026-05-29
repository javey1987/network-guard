package com.networkguard.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.networkguard.services.NetworkGuardVpnService
import com.networkguard.services.AlarmScheduler

/**
 * 开机启动接收器。
 * 1. 恢复 VPN 监控模式
 * 2. 重新注册所有持久化的定时闹钟
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
        val statusBefore = prefs.getString(NetworkGuardVpnService.STATUS_FILE, "stopped")

        // 1. 恢复 VPN 监控
        if (statusBefore == "running") {
            val vpnIntent = Intent(context, NetworkGuardVpnService::class.java).apply {
                action = NetworkGuardVpnService.ACTION_MONITOR
            }
            context.startForegroundService(vpnIntent)
        }

        // 2. 恢复所有定时闹钟
        val alarmScheduler = AlarmScheduler(context)
        alarmScheduler.rescheduleAllOnBoot()
    }
}
