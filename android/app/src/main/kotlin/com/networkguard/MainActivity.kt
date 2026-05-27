package com.networkguard

import android.content.Intent
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

                        val intent = Intent(this, NetworkGuardVpnService::class.java).apply {
                            action = NetworkGuardVpnService.ACTION_START
                            putExtra("blockWifi", blockWifi)
                            putExtra("blockMobile", blockMobile)
                            putExtra("reason", reason)
                        }
                        // 检查 VPN 授权状态
                        val prepareIntent = VpnService.prepare(this)
                        if (prepareIntent != null) {
                            // 用户未授权 VPN → 返回 false
                            result.success(false)
                        } else {
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
