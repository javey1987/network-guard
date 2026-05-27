package com.networkguard

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import com.networkguard.services.NetworkGuardVpnService

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.networkguard/vpn"
    }

    // 用 ActivityResultLauncher 替代已废弃的 startActivityForResult
    private lateinit var vpnAuthLauncher: androidx.activity.result.ActivityResultLauncher<Intent>
    private lateinit var methodChannel: MethodChannel

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // 注册 Activity Result Launcher — 必须在 configureFlutterEngine（即 onCreate）期间完成
        vpnAuthLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                // 用户同意了 VPN 授权 → 自动启动 VPN
                val intent = Intent(this, NetworkGuardVpnService::class.java).apply {
                    action = NetworkGuardVpnService.ACTION_START
                    putExtra("blockWifi", true)
                    putExtra("blockMobile", true)
                    putExtra("reason", "手动断网")
                }
                startService(intent)

                // 通知 Flutter 端 VPN 已获得授权并启动
                if (::methodChannel.isInitialized) {
                    methodChannel.invokeMethod("onVpnAuthorized", null)
                }
            }
        }

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel.setMethodCallHandler { call, result ->
            when (call.method) {
                "startVpn" -> {
                    val blockWifi = call.argument<Boolean>("blockWifi") ?: true
                    val blockMobile = call.argument<Boolean>("blockMobile") ?: true
                    val reason = call.argument<String>("reason") ?: "定时断网"

                    // 检查 VPN 授权状态
                    val prepareIntent = VpnService.prepare(this)
                    if (prepareIntent != null) {
                        // 未授权 → 弹出系统 VPN 授权框（使用 ActivityResultLauncher）
                        vpnAuthLauncher.launch(prepareIntent)
                        result.success(false)
                    } else {
                        // 已授权 → 直接启动服务
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
    }
}
