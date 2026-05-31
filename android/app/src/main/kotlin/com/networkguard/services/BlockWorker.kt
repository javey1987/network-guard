package com.networkguard.services

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 一次性定时 Worker（替代 AlarmReceiver）。
 *
 * 两种模式：
 *  - isStart=true  → 到点启动封锁（启动 NetworkGuardVpnService）
 *  - isStart=false → 到点恢复网络（停止 NetworkGuardVpnService）
 *
 * 使用 WorkManager 调度，比 AlarmManager 在国产手机上兼容性更好。
 */
class BlockWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ruleId = inputData.getInt("ruleId", -1)
        val ruleName = inputData.getString("ruleName") ?: "定时断网"
        val isStart = inputData.getBoolean("isStart", true)
        val blockWifi = inputData.getBoolean("blockWifi", true)
        val blockMobile = inputData.getBoolean("blockMobile", true)
        val allowedApps = inputData.getStringArray("allowedApps") ?: emptyArray()

        Log.i(TAG, "Worker 触发: ${if (isStart) "封锁" else "恢复"} $ruleName (ruleId=$ruleId)")

        return try {
            if (isStart) {
                // 到点封锁
                val vpnIntent = Intent(applicationContext, NetworkGuardVpnService::class.java).apply {
                    action = NetworkGuardVpnService.ACTION_START
                    putExtra("blockWifi", blockWifi)
                    putExtra("blockMobile", blockMobile)
                    putExtra("reason", ruleName)
                    putStringArrayListExtra("allowedApps", ArrayList(allowedApps.toList()))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(vpnIntent)
                } else {
                    applicationContext.startService(vpnIntent)
                }
            } else {
                // 到点恢复
                val stopIntent = Intent(applicationContext, NetworkGuardVpnService::class.java).apply {
                    action = NetworkGuardVpnService.ACTION_STOP
                }
                applicationContext.startService(stopIntent)
            }
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Worker 执行失败: ${e.message}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "BlockWorker"
    }
}
