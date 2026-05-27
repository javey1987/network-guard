package com.networkguard.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.*
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.networkguard.MainActivity
import java.io.FileInputStream
import java.nio.ByteBuffer

/**
 * 本地 VPN 服务。
 *
 * 策略很简单：
 * - 启动 VPN → 建立虚拟网卡，丢弃所有经过的数据包 → 断网
 * - 停止 VPN → 关闭虚拟网卡，网络恢复正常
 * - 没有"一边运行一边放行"的模式
 */
class NetworkGuardVpnService : android.net.VpnService() {

    companion object {
        const val ACTION_START = "com.networkguard.START_VPN"
        const val ACTION_STOP = "com.networkguard.STOP_VPN"
        const val STATUS_FILE = "vpn_status"
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "network_guard_vpn"
    }

    @Volatile
    private var isRunning = false
    private var tunnelThread: Thread? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val reason = intent.getStringExtra("reason") ?: "定时断网"
                startVpnInternal(reason)
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
        saveStatus("revoked")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onRevoke()
    }

    private fun startVpnInternal(reason: String) {
        if (isRunning) return

        val builder = Builder()
            .setSession("定时断网助手")
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

        // 分配虚拟 IP
        builder.addAddress("10.0.0.2", 32)

        // 路由 ALL IPv4 流量到虚拟接口
        builder.addRoute("0.0.0.0", 1)
        builder.addRoute("128.0.0.0", 1)

        // 路由 ALL IPv6 流量到虚拟接口
        builder.addAddress("fd00:1:2:3::2", 126)
        builder.addRoute("::", 0)

        // DNS 指向不可达地址
        builder.addDnsServer("10.0.0.1")

        builder.setMtu(1500)

        try {
            val tunnel = builder.establish()
            if (tunnel == null) {
                saveStatus("cancelled")
                return
            }

            isRunning = true
            saveStatus("running")

            // 前台通知
            val notification = buildNotification(reason)
            startForeground(NOTIFICATION_ID, notification)

            // 启动后台线程：读取并丢弃所有数据包
            tunnelThread = Thread {
                readAndDrop(tunnel)
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

    /**
     * 读取 VPN 虚拟网卡的数据包，然后丢弃（不写入 output）。
     * 读是为了清空内核缓冲区，不转发就是断网。
     */
    private fun readAndDrop(tunnel: ParcelFileDescriptor) {
        val input = FileInputStream(tunnel.fileDescriptor)
        val buf = ByteBuffer.allocate(65535)

        while (isRunning && tunnel.fileDescriptor.valid()) {
            try {
                buf.clear()
                val bytesRead = input.channel.read(buf)
                if (bytesRead <= 0) continue
                // 读到了数据包 → 不转发 → 丢弃 → 断网
            } catch (e: Exception) {
                if (isRunning) {
                    try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                }
            }
        }

        try { input.close() } catch (_: Exception) {}
    }

    private fun stopVpnInternal() {
        isRunning = false
        tunnelThread?.interrupt()
        tunnelThread = null
        saveStatus("stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ─── 通知 ────────────────────────────────────────────────────
    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "定时断网", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "定时断网 VPN 服务通知"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(reason: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("🌙 网络已封锁")
            .setContentText("「$reason」· WiFi和移动网络已断开")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .build()
    }

    // ─── 持久化 ─────────────────────────────────────────────────
    private fun saveStatus(status: String) {
        getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            .edit().putString(STATUS_FILE, status).apply()
    }
}
