package com.networkguard

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import com.networkguard.services.NetworkGuardVpnService

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.networkguard/vpn"

    // VPN 授权结果回调
    private var pendingVpnResult: MethodChannel.Result? = null

    private val vpnAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            // 用户同意了 VPN 授权 → 启动 VPN
            pendingVpnResult?.success(true)
            startVpnService()
        } else {
            // 用户拒绝了 VPN 授权
            pendingVpnResult?.success(false)
        }
        pendingVpnResult = null
    }

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startVpn" -> {
                        val blockWifi = call.argument<Boolean>("blockWifi") ?: true
                        val blockMobile = call.argument<Boolean>("blockMobile") ?: true
                        val reason = call.argument<String>("reason") ?: "定时断网"

                        // 保存参数供后续使用
                        pendingWifi = blockWifi
                        pendingMobile = blockMobile
                        pendingReason = reason

                        // 检查 VPN 是否已授权
                        val prepareIntent = VpnService.prepare(this)
                        if (prepareIntent != null) {
                            // 需要先获取 VPN 授权
                            pendingVpnResult = result
                            vpnAuthLauncher.launch(prepareIntent)
                        } else {
                            // 已授权，直接启动
                            startVpnService()
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

    // 存储 pending 参数
    private var pendingWifi = true
    private var pendingMobile = true
    private var pendingReason = "定时断网"

    private fun startVpnService() {
        val intent = Intent(this, NetworkGuardVpnService::class.java).apply {
            action = NetworkGuardVpnService.ACTION_START
            putExtra("blockWifi", pendingWifi)
            putExtra("blockMobile", pendingMobile)
            putExtra("reason", pendingReason)
        }
        startService(intent)
    }
}
