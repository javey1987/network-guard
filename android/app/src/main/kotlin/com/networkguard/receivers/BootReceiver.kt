package com.networkguard.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.networkguard.services.NetworkGuardVpnService

/**
 * 开机启动接收器。
 * 检查是否有未完成的定时规则需要继续执行，或恢复后台监控模式。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
        val statusBefore = prefs.getString(NetworkGuardVpnService.STATUS_FILE, "stopped")

        if (statusBefore == "running") {
            // 恢复之前的状态（可能是监控模式或断网模式）
            val blockWifi = prefs.getBoolean("blockWifi", false)
            val blockMobile = prefs.getBoolean("blockMobile", false)
            val reason = prefs.getString("reason", "后台监控") ?: "后台监控"

            if (reason == "后台监控" || (!blockWifi && !blockMobile)) {
                // 监控模式
                val vpnIntent = Intent(context, NetworkGuardVpnService::class.java).apply {
                    action = NetworkGuardVpnService.ACTION_MONITOR
                }
                context.startForegroundService(vpnIntent)
            } else {
                // 断网模式
                val vpnIntent = Intent(context, NetworkGuardVpnService::class.java).apply {
                    action = NetworkGuardVpnService.ACTION_START
                    putExtra("blockWifi", blockWifi)
                    putExtra("blockMobile", blockMobile)
                    putExtra("reason", reason)
                }
                context.startForegroundService(vpnIntent)
            }
        }
    }
}
