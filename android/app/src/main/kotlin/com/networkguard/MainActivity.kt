package com.networkguard

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.app.usage.UsageStatsManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.provider.Settings
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import com.networkguard.services.NetworkGuardVpnService
import com.networkguard.receivers.DeviceAdminReceiver
import java.security.MessageDigest

/**
 * Flutter 主 Activity。
 *
 * MethodChannel：
 *  - com.networkguard/vpn       → VPN 启停
 *  - com.networkguard/locktask  → 屏幕固定
 *  - com.networkguard/prefs     → PIN 管理
 *  - com.networkguard/stats     → 使用统计 & 应用列表
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val VPN_CHANNEL = "com.networkguard/vpn"
        private const val LOCK_CHANNEL = "com.networkguard/locktask"
        private const val PREFS_CHANNEL = "com.networkguard/prefs"
        private const val STATS_CHANNEL = "com.networkguard/stats"
        private const val VPN_REQUEST_CODE = 9001
        private const val ADMIN_REQUEST_CODE = 9002
        private const val USAGE_REQUEST_CODE = 9003
    }

    private lateinit var vpnChannel: MethodChannel

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // ── VPN 通道 ────────────────────────────────────────
        vpnChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, VPN_CHANNEL)
        vpnChannel.setMethodCallHandler { call, result ->
            try {
                when (call.method) {
                    "startVpn" -> {
                        val blockWifi = call.argument<Boolean>("blockWifi") ?: true
                        val blockMobile = call.argument<Boolean>("blockMobile") ?: true
                        val reason = call.argument<String>("reason") ?: "定时断网"
                        val allowedApps = call.argument<List<String>>("allowedApps") ?: emptyList()

                        val prepareIntent = VpnService.prepare(this)
                        if (prepareIntent != null) {
                            startActivityForResult(prepareIntent, VPN_REQUEST_CODE)
                            result.success(false)
                        } else {
                            val intent = Intent(this, NetworkGuardVpnService::class.java).apply {
                                action = NetworkGuardVpnService.ACTION_START
                                putExtra("blockWifi", blockWifi)
                                putExtra("blockMobile", blockMobile)
                                putExtra("reason", reason)
                                putStringArrayListExtra("allowedApps", ArrayList(allowedApps))
                            }
                            startService(intent)
                            result.success(true)
                        }
                    }
                    "stopVpn" -> {
                        val intent = Intent(this, NetworkGuardVpnService::class.java).apply {
                            action = NetworkGuardVpnService.ACTION_STOP
                        }
                        startService(intent)
                        result.success(true)
                    }
                    "isVpnRunning" -> {
                        val running = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
                            .getString(NetworkGuardVpnService.STATUS_FILE, "stopped") == "running"
                        result.success(running)
                    }
                    "getStatus" -> {
                        val status = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
                            .getString(NetworkGuardVpnService.STATUS_FILE, "stopped") ?: "stopped"
                        result.success(status)
                    }
                    else -> result.notImplemented()
                }
            } catch (e: Exception) {
                result.error("VPN_ERROR", e.message, null)
            }
        }

        // ── 屏幕固定通道 ────────────────────────────────────
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, LOCK_CHANNEL)
            .setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "startLockTask" -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) startLockTask()
                            result.success(true)
                        }
                        "stopLockTask" -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) stopLockTask()
                            result.success(true)
                        }
                        "isLockTaskActive" -> {
                            val pm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                            result.success(pm.isLockTaskPermitted(packageName))
                        }
                        "requestDeviceAdmin" -> {
                            val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                    ComponentName(this, DeviceAdminReceiver::class.java))
                                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                    "开启设备管理员后，严格模式下更难退出专注")
                            startActivityForResult(intent, ADMIN_REQUEST_CODE)
                            result.success(true)
                        }
                        "isDeviceAdmin" -> {
                            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                            val comp = ComponentName(this, DeviceAdminReceiver::class.java)
                            result.success(dpm.isAdminActive(comp))
                        }
                        "deactivateDeviceAdmin" -> {
                            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                            val comp = ComponentName(this, DeviceAdminReceiver::class.java)
                            dpm.removeActiveAdmin(comp)
                            result.success(true)
                        }
                        else -> result.notImplemented()
                    }
                } catch (e: Exception) {
                    result.error("LOCK_ERROR", e.message, null)
                }
            }

        // ── PIN 管理通道（家长版） ──────────────────────────
        val prefs = getSharedPreferences("vpn_prefs", MODE_PRIVATE)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, PREFS_CHANNEL)
            .setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "setAdminPin" -> {
                            val pin = call.arguments as String
                            val hash = hashPin(pin)
                            prefs.edit().putString("admin_pin_hash", hash).apply()
                            result.success(true)
                        }
                        "verifyAdminPin" -> {
                            val pin = call.arguments as String
                            val stored = prefs.getString("admin_pin_hash", "")
                            result.success(stored == hashPin(pin))
                        }
                        "hasAdminPin" -> {
                            val stored = prefs.getString("admin_pin_hash", "")
                            result.success(stored != null && stored.isNotEmpty())
                        }
                        "clearAdminPin" -> {
                            prefs.edit().putString("admin_pin_hash", "").apply()
                            result.success(true)
                        }
                        else -> result.notImplemented()
                    }
                } catch (e: Exception) {
                    result.error("PIN_ERROR", e.message, null)
                }
            }

        // ── 统计 & 应用列表通道（家长版） ────────────────────
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, STATS_CHANNEL)
            .setMethodCallHandler { call, result ->
                try {
                    when (call.method) {
                        "queryUsageStats" -> {
                            val args = call.arguments as? Map<*, *>
                            val startTime = (args?.get("startTime") as? Number)?.toLong() ?: 0L
                            val endTime = (args?.get("endTime") as? Number)?.toLong() ?: 0L

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                                val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
                                val stats = usm.queryUsageStats(
                                    UsageStatsManager.INTERVAL_DAILY, startTime, endTime)
                                val pm = packageManager
                                val list = stats?.mapNotNull { stat ->
                                    try {
                                        val appInfo = pm.getApplicationInfo(stat.packageName, 0)
                                        val appName = pm.getApplicationLabel(appInfo).toString()
                                        mapOf(
                                            "packageName" to stat.packageName,
                                            "appName" to appName,
                                            "totalTimeInForeground" to stat.totalTimeInForeground
                                        )
                                    } catch (_: Exception) { null }
                                }?.filter { (it["totalTimeInForeground"] as? Long ?: 0) > 5000 }
                                 ?.sortedByDescending { it["totalTimeInForeground"] as Long }
                                 ?.take(30)

                                result.success(list ?: emptyList<Map<String, Any>>())
                            } else {
                                result.success(emptyList<Map<String, Any>>())
                            }
                        }
                        "hasUsageStatsPermission" -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                                val usm = getSystemService(USAGE_STATS_SERVICE) as UsageStatsManager
                                val stats = usm.queryUsageStats(
                                    UsageStatsManager.INTERVAL_DAILY,
                                    System.currentTimeMillis() - 86400000,
                                    System.currentTimeMillis())
                                result.success(stats != null && stats.any { it.totalTimeInForeground > 0 })
                            } else {
                                result.success(false)
                            }
                        }
                        "requestUsageStatsPermission" -> {
                            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
                            startActivityForResult(intent, USAGE_REQUEST_CODE)
                            result.success(true)
                        }
                        "getInstalledApps" -> {
                            try {
                                val pm = packageManager
                                // 方式一：getInstalledPackages — 用反射兼容新旧 API
                                // compileSdk 35 移除了 getInstalledPackages(int)
                                val installed = pm.getInstalledPackages(
                                    PackageManager.PackageInfoFlags.of(0)
                                )
                                val list = mutableListOf<Map<String, String>>()
                                for (pkg in installed) {
                                    try {
                                        val appName = pm.getApplicationLabel(pkg.applicationInfo).toString()
                                        if (appName.isNotBlank() && pkg.packageName != packageName) {
                                            list.add(mapOf(
                                                "packageName" to pkg.packageName,
                                                "appName" to appName
                                            ))
                                        }
                                    } catch (_: Exception) { /* 跳过不可读的应用 */ }
                                }
                                result.success(list.sortedBy { it["appName"] })
                            } catch (e: Exception) {
                                // 方式二：用 queryIntentActivities 仅获取有启动器的应用
                                try {
                                    val pm = packageManager
                                    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                                    val apps = pm.queryIntentActivities(intent, 0)
                                    val built = apps.mapNotNull { ri ->
                                        try {
                                            val ai = ri.activityInfo
                                            if (ai.packageName != packageName) {
                                                mapOf(
                                                    "packageName" to ai.packageName,
                                                    "appName" to ai.loadLabel(pm).toString()
                                                )
                                            } else null
                                        } catch (_: Exception) { null }
                                    }.sortedBy { it["appName"] as String }
                                    result.success(built)
                                } catch (e2: Exception) {
                                    result.error("GET_APPS_ERROR", e2.message, null)
                                }
                            }
                        }
                        else -> result.notImplemented()
                    }
                } catch (e: Exception) {
                    result.error("STATS_ERROR", e.message, null)
                }
            }
    }

    private fun hashPin(pin: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(("network_guard_admin_salt_2024$pin").toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                val intent = Intent(this, NetworkGuardVpnService::class.java).apply {
                    action = NetworkGuardVpnService.ACTION_START
                    putExtra("blockWifi", true)
                    putExtra("blockMobile", true)
                    putExtra("reason", "手动断网")
                }
                startService(intent)
                if (::vpnChannel.isInitialized) {
                    try { vpnChannel.invokeMethod("onVpnAuthorized", null) } catch (_: Exception) {}
                }
            }
        } else if (requestCode == ADMIN_REQUEST_CODE) {
            if (::vpnChannel.isInitialized) {
                try { vpnChannel.invokeMethod("onDeviceAdminChanged", null) } catch (_: Exception) {}
            }
        }
    }
}
