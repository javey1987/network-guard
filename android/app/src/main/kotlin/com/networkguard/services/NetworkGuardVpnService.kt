package com.networkguard.services

import android.app.Notification
import android.app.NotificationChannel
import java.util.HashMap
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.net.*
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.networkguard.MainActivity
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 本地 VPN 服务。
 * 通过建立虚拟网卡拦截所有流量，在指定时段直接丢弃数据包实现「断网」效果。
 *
 * 家长版特性：支持应用白名单，通过 addDisallowedApplication() 让白名单应用
 * 绕过 VPN 直接上网，其他应用则被 VPN 封锁。
 */
class NetworkGuardVpnService : android.net.VpnService() {

    companion object {
        const val ACTION_START = "com.networkguard.START_VPN"
        const val ACTION_STOP = "com.networkguard.STOP_VPN"

        const val STATUS_FILE = "vpn_status"
        const val CONFIG_FILE = "vpn_config"
    }

    private var isRunning = false
    private var tunnelThread: Thread? = null

    @Volatile
    private var cachedBlockWifi: Boolean = true
    @Volatile
    private var cachedBlockMobile: Boolean = true

    private var vpnInterface: ParcelFileDescriptor? = null
    private var vpnInput: FileInputStream? = null
    private var vpnOutput: FileOutputStream? = null

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
                val allowedApps = intent.getStringArrayListExtra("allowedApps") ?: arrayListOf()
                startVpnInternal(blockWifi, blockMobile, reason, allowedApps)
            }
            ACTION_STOP -> {
                stopVpnInternal()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopVpnInternal()
        super.onDestroy()
    }

    override fun onRevoke() {
        // 防篡改：如果 VPN 被系统撤销（孩子手动关闭），尝试立即重启
        stopVpnInternal()
        // 检查之前是否在运行状态，如果是则自动重连
        val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
        val statusBefore = prefs.getString(STATUS_FILE, "stopped")
        if (statusBefore == "running") {
            val blockWifi = prefs.getBoolean("blockWifi", true)
            val blockMobile = prefs.getBoolean("blockMobile", true)
            val reason = prefs.getString("reason", "定时断网") ?: "定时断网"
            val allowedApps = prefs.getString("allowedApps", "")?.split(",")
                ?.filter { it.isNotEmpty() } ?: emptyList()

            // 延迟 2 秒后自动重连
            Thread {
                try { Thread.sleep(2000) } catch (_: InterruptedException) {}
                startVpnInternal(blockWifi, blockMobile, "防篡改重连-$reason", allowedApps)
            }.start()
        }
        super.onRevoke()
    }

    // ─── 启动 VPN ───────────────────────────────────────────────
    private fun startVpnInternal(
        blockWifi: Boolean,
        blockMobile: Boolean,
        reason: String,
        allowedApps: List<String> = emptyList()
    ) {
        if (isRunning) return

        val builder = Builder()
            .setSession("定时断网助手")
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

        builder.addAddress("10.0.0.2", 32)
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("1.1.1.1")

        builder.addRoute("0.0.0.0", 1)
        builder.addRoute("128.0.0.0", 1)

        builder.addAddress("fd00:1:2:3::2", 126)
        builder.addRoute("::", 0)

        builder.setMtu(1500)

        // ★ 家长版：应用白名单
        // 通过 addDisallowedApplication() 让白名单应用绕过 VPN -> 可以直接上网
        // 其他应用走 VPN -> 数据包被丢弃 -> 被封锁
        if (allowedApps.isNotEmpty()) {
            for (pkg in allowedApps) {
                try {
                    builder.addDisallowedApplication(pkg)
                } catch (_: Exception) {
                    // 包名不合法时跳过
                }
            }
        }

        val notification = buildNotification(blockWifi, blockMobile, reason, allowedApps)
        startForeground(NOTIFICATION_ID, notification)

        try {
            val iface = builder.establish()
            if (iface == null) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                return
            }

            vpnInterface = iface
            vpnInput = FileInputStream(iface.fileDescriptor)
            vpnOutput = FileOutputStream(iface.fileDescriptor)

            isRunning = true
            cachedBlockWifi = blockWifi
            cachedBlockMobile = blockMobile
            saveStatus("running")
            saveConfig(blockWifi, blockMobile, reason, allowedApps)

            tunnelThread = Thread {
                processPackets(iface)
            }.apply {
                isDaemon = true
                start()
            }

        } catch (e: Exception) {
            e.printStackTrace()
            isRunning = false
            saveStatus("error:${e.message}")
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    // ─── 数据包处理 ─────────────────────────────────────────────
    private fun processPackets(vpnInterface: ParcelFileDescriptor) {
        val input = FileInputStream(vpnInterface.fileDescriptor)
        val output = FileOutputStream(vpnInterface.fileDescriptor)
        val packet = ByteBuffer.allocate(32767)

        val ipHeader = ByteArray(20)

        while (isRunning) {
            try {
                packet.clear()
                val length = input.channel.read(packet)
                if (length <= 0) continue
                packet.flip()
                if (packet.remaining() < 20) continue
                packet.get(ipHeader)
                val ipVersion = (ipHeader[0].toInt() shr 4) and 0x0F

                if (ipVersion == 4) {
                    val totalLen = ((ipHeader[2].toInt() and 0xFF) shl 8) or (ipHeader[3].toInt() and 0xFF)
                    packet.rewind()
                    val fullPacket = ByteArray(totalLen.coerceAtMost(packet.remaining()))
                    packet.get(fullPacket)
                    handleIPv4Packet(fullPacket, output)
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

    private fun handleIPv4Packet(packet: ByteArray, output: FileOutputStream) {
        if (cachedBlockWifi || cachedBlockMobile) {
            return
        }
        try {
            output.write(packet)
            output.flush()
        } catch (_: Exception) {}
    }

    // ─── 停止 VPN ───────────────────────────────────────────────
    private fun stopVpnInternal() {
        if (!isRunning && vpnInterface == null) return

        isRunning = false
        tunnelThread?.interrupt()
        tunnelThread = null

        try { vpnInput?.close() } catch (_: Exception) {}
        vpnInput = null
        try { vpnOutput?.close() } catch (_: Exception) {}
        vpnOutput = null
        try { vpnInterface?.close() } catch (_: Exception) {}
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
                CHANNEL_ID, "定时断网", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "定时断网 VPN 服务通知"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        blockWifi: Boolean,
        blockMobile: Boolean,
        reason: String,
        allowedApps: List<String> = emptyList()
    ): Notification {
        val text = buildString {
            append("「$reason」")
            if (blockWifi) append(" · WiFi 已断")
            if (blockMobile) append(" · 移动网络已断")
            if (allowedApps.isNotEmpty()) append(" · ${allowedApps.size}个应用可用")
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

    // ─── 状态持久化 ─────────────────────────────────────────────
    private fun saveStatus(status: String) {
        getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            .edit().putString(STATUS_FILE, status).apply()
    }

    private fun saveConfig(blockWifi: Boolean, blockMobile: Boolean, reason: String, allowedApps: List<String>) {
        getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("blockWifi", blockWifi)
            .putBoolean("blockMobile", blockMobile)
            .putString("reason", reason)
            .putString("allowedApps", allowedApps.joinToString(","))
            .apply()
    }

    fun isVpnActive(): Boolean = isRunning
}
