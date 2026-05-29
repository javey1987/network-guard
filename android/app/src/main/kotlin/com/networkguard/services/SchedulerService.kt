package com.networkguard.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.app.NotificationCompat
import com.networkguard.MainActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Calendar

/**
 * 常驻前台调度服务。
 * 负责：
 * 1. 周期性检查规则，在正确时间启动/停止 VPN
 * 2. 即便用户滑掉 App 进程，此服务也尽力保持运行
 * 3. 通过 Handler.postDelayed 按规则时间精准调度，不依赖 AlarmManager
 */
class SchedulerService : Service() {

    companion object {
        private const val TAG = "SchedulerService"
        private const val NOTIFICATION_ID = 9002
        private const val CHANNEL_ID = "scheduler_channel"
        private const val PREFS_NAME = "scheduler_rules"
        private const val RULES_KEY = "rules_json"
        private const val CHECK_INTERVAL_MS = 30_000L // 30秒兜底检查
        private const val VPN_MINIMAL_ROUTE = "10.0.0.99"
        private const val VPN_NOTIFICATION_ID = 9001

        // Actions
        const val ACTION_START = "com.networkguard.SCHEDULER_START"
        const val ACTION_STOP = "com.networkguard.SCHEDULER_STOP"

        fun start(context: Context) {
            val intent = Intent(context, SchedulerService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, SchedulerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * 从 Dart 侧保存规则快照到 SharedPreferences
         */
        fun saveRules(context: Context, rulesJson: String) {
            context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(RULES_KEY, rulesJson)
                .apply()
            Log.i(TAG, "saveRules: 已保存 ${JSONArray(rulesJson).length()} 条规则")
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val checkRunnable = Runnable { performCheck() }
    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnInput: FileInputStream? = null
    private var vpnOutput: FileOutputStream? = null
    private var isVpnActive = false
    private var currentlyBlocking = false
    private var isDestroyed = false

    // ─── 生命周期 ────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Log.i(TAG, "onCreate: 调度服务启动")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(NOTIFICATION_ID, buildNotification("调度运行中", false))
                performCheck()
            }
            ACTION_STOP -> {
                stopVpn()
                handler.removeCallbacksAndMessages(null)
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        isDestroyed = true
        handler.removeCallbacksAndMessages(null)
        stopVpn()
        super.onDestroy()
        Log.i(TAG, "onDestroy: 调度服务已停止")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── 核心调度 ────────────────────────────────────────────────

    private fun performCheck() {
        if (isDestroyed) return

        try {
            val rules = loadRules() ?: return
            checkAndActivate(rules)
        } catch (e: Exception) {
            Log.e(TAG, "performCheck 错误: ${e.message}")
        }

        // 下一次检查
        if (!isDestroyed) {
            handler.removeCallbacks(checkRunnable)
            handler.postDelayed(checkRunnable, CHECK_INTERVAL_MS)
        }
    }

    private data class RuleInfo(
        val id: Int,
        val name: String,
        val startMinutes: Int,
        val durationMs: Long,
        val enabled: Boolean,
        val blockWifi: Boolean,
        val blockMobile: Boolean,
        val allowedApps: List<String>
    )

    private data class ActiveRule(
        val name: String,
        val blockWifi: Boolean,
        val blockMobile: Boolean,
        val allowedApps: List<String>
    )

    private fun loadRules(): List<RuleInfo>? {
        val jsonStr = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(RULES_KEY, null) ?: return null

        val arr = JSONArray(jsonStr)
        val rules = mutableListOf<RuleInfo>()
        val now = System.currentTimeMillis()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val id = obj.getInt("id")
            val name = obj.optString("name", "定时断网")
            val startMinutes = obj.getInt("startMinutes")
            val durationMs = obj.getLong("durationMs")
            val enabled = obj.optBoolean("enabled", true)
            val blockWifi = obj.optBoolean("blockWifi", true)
            val blockMobile = obj.optBoolean("blockMobile", true)
            val allowedApps = try {
                val arrApps = obj.getJSONArray("allowedApps")
                (0 until arrApps.length()).map { arrApps.getString(it) }
            } catch (_: Exception) { emptyList() }

            rules.add(RuleInfo(id, name, startMinutes, durationMs, enabled, blockWifi, blockMobile, allowedApps))
        }
        return rules
    }

    private fun checkAndActivate(rules: List<RuleInfo>) {
        val now = System.currentTimeMillis()
        val nowCal = Calendar.getInstance().also { it.timeInMillis = now }

        // 计算当天的分钟数
        val currentMinutes = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)

        // 寻找当前活跃的规则
        var activeRule: ActiveRule? = null

        for (rule in rules) {
            if (!rule.enabled) continue

            // 计算规则今天的开始时间和结束时间
            val ruleStartMinutes = rule.startMinutes
            val ruleEndMinutes = (ruleStartMinutes + rule.durationMs / 60_000).toInt() % (24 * 60)
            val crossesMidnight = ruleEndMinutes < ruleStartMinutes

            val isActive = if (crossesMidnight) {
                // 跨天规则：如 22:00-06:00
                currentMinutes >= ruleStartMinutes && currentMinutes < 24 * 60 ||
                currentMinutes >= 0 && currentMinutes < ruleEndMinutes
            } else {
                currentMinutes >= ruleStartMinutes && currentMinutes < ruleEndMinutes
            }

            if (isActive) {
                activeRule = ActiveRule(rule.name, rule.blockWifi, rule.blockMobile, rule.allowedApps)
                break
            }
        }

        if (activeRule != null) {
            if (!currentlyBlocking) {
                Log.i(TAG, "检测到规则激活: ${activeRule.name}，启动 VPN 封锁")
                startVpn(activeRule)
                currentlyBlocking = true
                updateNotification("🌙 网络已封锁 - ${activeRule.name}", true)
            }
        } else {
            if (currentlyBlocking) {
                Log.i(TAG, "规则结束，停止 VPN")
                stopVpn()
                currentlyBlocking = false
                updateNotification("调度运行中", false)
            }
        }
    }

    // ─── VPN 管理 ────────────────────────────────────────────────

    private fun startVpn(rule: ActiveRule) {
        try {
            // 如果有旧 VPN，先停止
            stopVpnInternal()

            val builder = VpnService.Builder()
                .setSession("定时断网助手")
                .setConfigureIntent(
                    PendingIntent.getActivity(
                        this, 0, Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )

            // 封锁模式：拦截所有流量
            builder.addAddress("10.0.0.2", 32)
            builder.addDnsServer("8.8.8.8")
            builder.addDnsServer("1.1.1.1")

            builder.addRoute("0.0.0.0", 1)
            builder.addRoute("128.0.0.0", 1)
            builder.addAddress("fd00:1:2:3::2", 126)
            builder.addRoute("::", 0)
            builder.setMtu(1500)

            // 白名单应用
            if (rule.allowedApps.isNotEmpty()) {
                for (pkg in rule.allowedApps) {
                    try { builder.addDisallowedApplication(pkg) } catch (_: Exception) {}
                }
            }

            vpnInterface = builder.establish()
            if (vpnInterface == null) {
                Log.e(TAG, "startVpn: establish 返回 null")
                return
            }

            vpnInput = FileInputStream(vpnInterface!!.fileDescriptor)
            vpnOutput = FileOutputStream(vpnInterface!!.fileDescriptor)
            isVpnActive = true

            // 启动包丢弃线程（读 TUN 但丢弃，实现断网）
            Thread {
                val buffer = ByteArray(32767)
                try {
                    while (isVpnActive && !Thread.interrupted()) {
                        val len = vpnInput?.read(buffer) ?: break
                        if (len < 0) break
                        // 丢弃！不写入输出，实现断网
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; start() }

        } catch (e: Exception) {
            Log.e(TAG, "startVpn 失败: ${e.message}")
            isVpnActive = false
        }
    }

    private fun stopVpn() {
        stopVpnInternal()
    }

    private fun stopVpnInternal() {
        isVpnActive = false
        try { vpnInput?.close() } catch (_: Exception) {}
        vpnInput = null
        try { vpnOutput?.close() } catch (_: Exception) {}
        vpnOutput = null
        try { vpnInterface?.close() } catch (_: Exception) {}
        vpnInterface = null
    }

    // ─── 通知 ────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "定时断网调度",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "定时断网助手调度服务"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String, isBlocking: Boolean): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(if (isBlocking) "🌙 网络已封锁" else "⏰ 定时断网运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String, isBlocking: Boolean) {
        val notification = buildNotification(text, isBlocking)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
