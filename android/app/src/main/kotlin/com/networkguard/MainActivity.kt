package com.networkguard

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.net.VpnService
import android.os.Build
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import com.networkguard.services.NetworkGuardVpnService
import com.networkguard.receivers.DeviceAdminReceiver

/**
 * Flutter 主 Activity。
 *
 * MethodChannel：
 *  - com.networkguard/vpn      → VPN 启停
 *  - com.networkguard/locktask → 屏幕固定（严格模式）
 */
class MainActivity : FlutterActivity() {

    companion object {
        private const val VPN_CHANNEL = "com.networkguard/vpn"
        private const val LOCK_CHANNEL = "com.networkguard/locktask"
        private const val VPN_REQUEST_CODE = 9001
        private const val ADMIN_REQUEST_CODE = 9002
    }

    private lateinit var vpnChannel: MethodChannel

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // ── VPN 通道 ────────────────────────────────────────
        vpnChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, VPN_CHANNEL)
        vpnChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startVpn" -> {
                    val blockWifi = call.argument<Boolean>("blockWifi") ?: true
                    val blockMobile = call.argument<Boolean>("blockMobile") ?: true
                    val reason = call.argument<String>("reason") ?: "定时断网"

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
        }

        // ── 屏幕固定通道（严格模式） ────────────────────────
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, LOCK_CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startLockTask" -> {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                startLockTask()
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.success(false)
                        }
                    }
                    "stopLockTask" -> {
                        try {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                                stopLockTask()
                            }
                            result.success(true)
                        } catch (e: Exception) {
                            result.success(false)
                        }
                    }
                    "isLockTaskActive" -> {
                        try {
                            val pm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                            result.success(pm.isLockTaskPermitted(packageName))
                        } catch (_: Exception) {
                            result.success(false)
                        }
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
                        try {
                            val dpm = getSystemService(DEVICE_POLICY_SERVICE) as DevicePolicyManager
                            val comp = ComponentName(this, DeviceAdminReceiver::class.java)
                            result.success(dpm.isAdminActive(comp))
                        } catch (_: Exception) {
                            result.success(false)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
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
                    try {
                        vpnChannel.invokeMethod("onVpnAuthorized", null)
                    } catch (_: Exception) {}
                }
            }
        } else if (requestCode == ADMIN_REQUEST_CODE) {
            if (::vpnChannel.isInitialized) {
                try {
                    vpnChannel.invokeMethod("onDeviceAdminChanged", null)
                } catch (_: Exception) {}
            }
        }
    }
}
