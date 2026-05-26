package com.networkguard.services

import android.app.Notification
import android.app.NotificationChannel
import java.util.HashMap
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
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
 * 无需 root，工作原理类似所有「防火墙/VPN」类应用。
 */
class NetworkGuardVpnService : android.net.VpnService() {

    companion object {
        const val ACTION_START = "com.networkguard.START_VPN"
        const val ACTION_STOP = "com.networkguard.STOP_VPN"

        // 用本地文件记录状态，避免跨进程 IPC
        const val STATUS_FILE = "vpn_status"
        const val CONFIG_FILE = "vpn_config"
    }

    private var isRunning = false
    private var tunnelThread: Thread? = null

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
        stopVpnInternal()
        super.onRevoke()
    }

    // ─── 启动 VPN ───────────────────────────────────────────────
    private fun startVpnInternal(blockWifi: Boolean, blockMobile: Boolean, reason: String) {
        if (isRunning) return

        // 构建 VPN 接口配置
        val builder = Builder()
            .setSession("定时断网助手")
            .setConfigureIntent(
                PendingIntent.getActivity(
                    this, 0, Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )

        // 分配虚拟 IP（同设备内使用，无冲突风险）
        builder.addAddress("10.0.0.2", 32)

        // 添加 DNS（仅用于虚拟接口，实际不进行 DNS 解析）
        builder.addDnsServer("8.8.8.8")
        builder.addDnsServer("1.1.1.1")

        // 路由所有流量到虚拟接口（0.0.0.0/0）
        builder.addRoute("0.0.0.0", 1)
        builder.addRoute("128.0.0.0", 1)

        // 设置最大传输单元
        builder.setMtu(1500)

        // 如果开启严格模式，阻止用户在系统设置中手动打开网络
        // 这里通过持续运行前台服务来实现
        val notification = buildNotification(blockWifi, blockMobile, reason)
        startForeground(NOTIFICATION_ID, notification)

        try {
            val vpnInterface = builder.establish()
            if (vpnInterface == null) {
                // 用户取消了 VPN 授权
                stopForeground(STOP_FOREGROUND_REMOVE)
                return
            }

            isRunning = true
            saveStatus("running")
            saveConfig(blockWifi, blockMobile, reason)

            // 在后台线程中处理 VPN 数据包——全部丢弃
            tunnelThread = Thread {
                processPackets(vpnInterface)
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
        val config = loadConfig()

        while (isRunning) {
            try {
                packet.clear()
                val length = input.channel.read(packet)
                if (length <= 0) continue

                packet.flip()

                // 读取 IP 头部判断协议类型
                if (packet.remaining() < 20) continue
                packet.get(ipHeader)

                // 简单 IP 头解析（无扩展头的情况下）
                val ipVersion = (ipHeader[0].toInt() shr 4) and 0x0F

                if (ipVersion == 4) {
                    val totalLen = ((ipHeader[2].toInt() and 0xFF) shl 8) or (ipHeader[3].toInt() and 0xFF)
                    // 读取更多数据
                    packet.rewind()
                    val fullPacket = ByteArray(totalLen.coerceAtMost(packet.remaining()))
                    packet.get(fullPacket)
                    handleIPv4Packet(fullPacket, output)
                } else {
                    // IPv6 — 放行（防止 IPv6 绕过封锁）
                    // 实际严格模式下也应丢弃
                    packet.rewind()
                    // output.write(packet.array(), packet.arrayOffset(), packet.remaining())
                    // 丢弃 IPv6 流量（如需支持 IPv6 可取消注释上面一行）
                }

            } catch (e: Exception) {
                if (isRunning) {
                    e.printStackTrace()
                    // 出错后短暂休眠避免忙循环
                    try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                }
            }
        }

        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
    }

    /**
     * 处理 IPv4 数据包。
     * 在封锁模式下丢弃所有数据包（即不写入 output）。
     */
    private fun handleIPv4Packet(packet: ByteArray, output: FileOutputStream) {
        // 从 VPN 配置读取是否应该丢弃
        val config = loadConfig()
        val shouldDrop = config["blockWifi"] == "true" || config["blockMobile"] == "true"

        if (shouldDrop) {
            // 丢弃——数据包不被转发，实现断网
            // 不写入 output
            return
        }

        // 未封锁——正常转发
        try {
            output.write(packet)
            output.flush()
        } catch (_: Exception) {}
    }

    // ─── 停止 VPN ───────────────────────────────────────────────
    private fun stopVpnInternal() {
        isRunning = false
        tunnelThread?.interrupt()
        tunnelThread = null
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
                CHANNEL_ID,
                "定时断网",
                NotificationManager.IMPORTANCE_LOW
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
        reason: String
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

    // ─── 状态持久化 ─────────────────────────────────────────────
    private fun saveStatus(status: String) {
        getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            .edit()
            .putString(STATUS_FILE, status)
            .apply()
    }

    private fun saveConfig(blockWifi: Boolean, blockMobile: Boolean, reason: String) {
        getSharedPreferences("vpn_prefs", MODE_PRIVATE)
            .edit()
            .putBoolean("blockWifi", blockWifi)
            .putBoolean("blockMobile", blockMobile)
            .putString("reason", reason)
            .apply()
    }

    private fun loadConfig(): Map<String, String> {
        val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
        val result = HashMap<String, String>()
        result["blockWifi"] = prefs.getBoolean("blockWifi", true).toString()
        result["blockMobile"] = prefs.getBoolean("blockMobile", true).toString()
        result["reason"] = prefs.getString("reason", "定时断网") ?: "定时断网"
        return result
    }

    // ─── 静态方法供 Flutter 调用 ──────────────────────────────
    fun isVpnActive(): Boolean = isRunning
}
