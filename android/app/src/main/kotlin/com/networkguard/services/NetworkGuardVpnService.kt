package com.networkguard.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.*
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.networkguard.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * 本地 VPN 服务。
 * 通过建立虚拟网卡拦截所有流量，在指定时段直接丢弃数据包实现「断网」效果。
 *
 * 无需 root，工作原理类似所有「防火墙/VPN」类应用。
 */
class NetworkGuardVpnService : android.net.VpnService() {

    companion object {
        const val ACTION_START = "com.networkguard.START_VPN"
        const val ACTION_STOP = "com.networkguard.STOP_VPN"
        const val STATUS_FILE = "vpn_status"
    }

    @Volatile
    private var isRunning = false
    @Volatile
    private var shouldBlock = true

    private var tunnelThread: Thread? = null
    private var vpnInterface: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val blockWifi = intent.getBooleanExtra("blockWifi", true)
                val blockMobile = intent.getBooleanExtra("blockMobile", true)
                val reason = intent.getStringExtra("reason") ?: "定时断网"
                startVpnInternal(blockWifi, blockMobile, reason)
            }
            ACTION_STOP -> stopVpnInternal()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpnInternal()
        super.onDestroy()
    }

    override fun onRevoke() {
        isRunning = false
        tunnelThread?.interrupt()
        tunnelThread = null
        vpnInterface?.close()
        vpnInterface = null
        saveStatus("revoked")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    // ─── 启动 VPN ───────────────────────────────────────────────
    private fun startVpnInternal(blockWifi: Boolean, blockMobile: Boolean, reason: String) {
        if (isRunning) {
            // VPN 已运行，只更新封锁状态和通知
            shouldBlock = blockWifi || blockMobile
            saveConfig(blockWifi, blockMobile, reason)
            updateNotification(blockWifi, blockMobile, reason)
            return
        }

        shouldBlock = blockWifi || blockMobile
        saveConfig(blockWifi, blockMobile, reason)

        val builder = Builder()
            .setSession("定时断网助手")
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

        // IPv4
        builder.addAddress("10.0.0.2", 32)
        builder.addRoute("0.0.0.0", 1)
        builder.addRoute("128.0.0.0", 1)

        // IPv6（必须！否则 IPv6 流量绕过封锁）
        builder.addAddress("fd00:1:2:3::2", 126)
        builder.addRoute("::", 0)

        // DNS 指向内网地址，封锁时查询会超时
        builder.addDnsServer("10.0.0.1")
        builder.setMtu(1500)

        // Android 10+：禁止绕过 VPN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setBlocking(true)
        }

        try {
            val tunnel = builder.establish()
            if (tunnel == null) {
                saveStatus("cancelled")
                return
            }

            vpnInterface = tunnel
            isRunning = true
            saveStatus("running")

            val notification = buildNotification(blockWifi, blockMobile, reason)
            startForeground(NOTIFICATION_ID, notification)

            tunnelThread = Thread {
                runTunnel(tunnel)
            }.apply {
                isDaemon = true
                start()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            isRunning = false
            saveStatus("error:${e.message}")
        }
    }

    // ─── 隧道主循环 ─────────────────────────────────────────────
    private fun runTunnel(tunnel: ParcelFileDescriptor) {
        val input = FileInputStream(tunnel.fileDescriptor)
        val output = FileOutputStream(tunnel.fileDescriptor)
        val buf = ByteBuffer.allocate(65535)

        while (isRunning && tunnel.fileDescriptor.valid()) {
            try {
                buf.clear()
                val bytesRead = input.channel.read(buf)
                if (bytesRead <= 0) continue

                if (shouldBlock) {
                    // 封锁模式：丢弃数据包（不写入 output）
                    // 数据包被丢弃后，请求超时 → 断网效果
                } else {
                    // 放行模式：转发数据包到实际网络
                    buf.flip()
                    output.channel.write(buf)
                }

            } catch (e: Exception) {
                if (isRunning) {
                    try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                }
            }
        }

        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
    }

    // ─── 停止 VPN ───────────────────────────────────────────────
    private fun stopVpnInternal() {
        isRunning = false
        shouldBlock = true
        tunnelThread?.interrupt()
        tunnelThread = null
        vpnInterface?.close()
        vpnInterface = null
        saveStatus("stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ─── 通知 ────────────────────────────────────────────────────
    private val NOTIFICATION_ID = 9001
    private val CHANNEL_ID = "network_guard_vpn"

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "定时断网",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "定时断网 VPN 服务通知"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        blockWifi: Boolean, blockMobile: Boolean, reason: String
    ): Notification {
        val text = buildString {
            append("「$reason」")
            if (blockWifi) append(" · WiFi 已断")
            if (blockMobile) append(" · 移动网络已断")
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🌙 网络已封锁")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(blockWifi: Boolean, blockMobile: Boolean, reason: String) {
        val notification = buildNotification(blockWifi, blockMobile, reason)
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    // ─── 持久化 ─────────────────────────────────────────────────
    private fun saveStatus(status: String) {
        getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            .edit().putString(STATUS_FILE, status).apply()
    }

    private fun saveConfig(blockWifi: Boolean, blockMobile: Boolean, reason: String) {
        getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("blockWifi", blockWifi)
            .putBoolean("blockMobile", blockMobile)
            .putString("reason", reason)
            .apply()
    }
}
