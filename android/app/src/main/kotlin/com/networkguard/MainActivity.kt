package com.networkguard

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import com.networkguard.services.NetworkGuardVpnService

class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.networkguard/vpn"
        private const val VPN_REQUEST_CODE = 9001
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

                        // 关键：检查 VPN 授权状态
                        val prepareIntent = VpnService.prepare(this)
                        if (prepareIntent != null) {
                            // 未授权 → 弹出系统 VPN 授权框
                            startActivityForResult(prepareIntent, VPN_REQUEST_CODE)
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

    // 处理 VPN 授权结果
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                // 用户同意了 VPN 授权 → 自动启动 VPN
                val intent = Intent(this, NetworkGuardVpnService::class.java).apply {
                    action = NetworkGuardVpnService.ACTION_START
                    putExtra("blockWifi", true)
                    putExtra("blockMobile", true)
                    putExtra("reason", "手动断网")
                }
                startService(intent)
            }
        }
    }
}
