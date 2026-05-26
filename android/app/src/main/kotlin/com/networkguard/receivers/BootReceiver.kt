package com.networkguard.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import com.networkguard.services.NetworkGuardVpnService

/**
 * 开机启动接收器。
 * 检查是否有未完成的定时规则需要继续执行。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        val prefs = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
        val statusBefore = prefs.getString(NetworkGuardVpnService.STATUS_FILE, "stopped")

        // 如果关机前 VPN 在运行，重启时恢复
        if (statusBefore == "running") {
            val blockWifi = prefs.getBoolean("blockWifi", true)
            val blockMobile = prefs.getBoolean("blockMobile", true)
            val reason = prefs.getString("reason", "定时断网") ?: "定时断网"

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
