package com.networkguard.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.networkguard.services.NetworkGuardVpnService
import com.networkguard.services.AlarmScheduler

/**
 * 定时闹钟接收器。
 * 当 AlarmManager 触发的定时到达时，启动/停止 VPN 断网或注册下一轮闹钟。
 * 此接收器在 App 进程被杀死、手机休眠时仍然有效。
 */
class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_START_BLOCK = "com.networkguard.ALARM_START_BLOCK"
        const val ACTION_STOP_BLOCK = "com.networkguard.ALARM_STOP_BLOCK"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val ruleName = intent.getStringExtra("ruleName") ?: "定时断网"
        val blockWifi = intent.getBooleanExtra("blockWifi", true)
        val blockMobile = intent.getBooleanExtra("blockMobile", true)
        val allowedApps = intent.getStringArrayListExtra("allowedApps") ?: arrayListOf()

        when (action) {
            ACTION_START_BLOCK -> {
                startBlocking(context, ruleName, blockWifi, blockMobile, allowedApps)
            }

            ACTION_STOP_BLOCK -> {
                stopBlocking(context, ruleName)

                // 从持久化数据中读取规则信息，调度下一次
                val ruleId = intent.getIntExtra("ruleId", -1)
                if (ruleId != -1) {
                    val alarmScheduler = AlarmScheduler(context)
                    alarmScheduler.rescheduleNext(ruleId)
                }
            }
        }
    }

    private fun startBlocking(
        context: Context,
        ruleName: String,
        blockWifi: Boolean,
        blockMobile: Boolean,
        allowedApps: java.util.ArrayList<String>
    ) {
        val isVpnRunning = context.getSharedPreferences("vpn_prefs", Context.MODE_PRIVATE)
            .getString(NetworkGuardVpnService.STATUS_FILE, "stopped") == "running"

        if (isVpnRunning) {
            val vpnIntent = Intent(context, NetworkGuardVpnService::class.java).apply {
                action = NetworkGuardVpnService.ACTION_UPDATE
                putExtra("blockWifi", blockWifi)
                putExtra("blockMobile", blockMobile)
                putExtra("reason", ruleName)
                putStringArrayListExtra("allowedApps", allowedApps)
            }
            context.startService(vpnIntent)
        } else {
            val vpnIntent = Intent(context, NetworkGuardVpnService::class.java).apply {
                action = NetworkGuardVpnService.ACTION_START
                putExtra("blockWifi", blockWifi)
                putExtra("blockMobile", blockMobile)
                putExtra("reason", ruleName)
                putStringArrayListExtra("allowedApps", allowedApps)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(vpnIntent)
            } else {
                context.startService(vpnIntent)
            }
        }
    }

    private fun stopBlocking(context: Context, ruleName: String) {
        val vpnIntent = Intent(context, NetworkGuardVpnService::class.java).apply {
            action = NetworkGuardVpnService.ACTION_UPDATE
            putExtra("blockWifi", false)
            putExtra("blockMobile", false)
            putExtra("reason", "后台监控")
        }
        context.startService(vpnIntent)
    }
}
