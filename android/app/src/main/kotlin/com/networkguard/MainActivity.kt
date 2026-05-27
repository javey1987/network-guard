package com.networkguard

import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import com.networkguard.services.NetworkGuardVpnService

class MainActivity : FlutterActivity() {

    private val CHANNEL = "com.networkguard/vpn"

    override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "startVpn" -> {
                        val blockWifi = call.argument<Boolean>("blockWifi") ?: true
                        val blockMobile = call.argument<Boolean>("blockMobile") ?: true
                        val reason = call.argument<String>("reason") ?: "定时断网"

                        // 检查 VPN 是否已获得用户授权
                        val prepareIntent = VpnService.prepare(this)
                        if (prepareIntent != null) {
                            // 未授权 → 弹出系统授权对话框
                            // 用户授权后需要再次触发 startVpn
                            try {
                                startActivity(prepareIntent.apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                })
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                            result.success(false)
                        } else {
                            // 已授权 → 启动 VPN 服务
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
