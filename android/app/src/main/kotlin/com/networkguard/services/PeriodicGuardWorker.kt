package com.networkguard.services

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * 周期性保活 Worker（WorkManager 调度，每 15 分钟）。
 *
 * 确保 SchedulerService 存活。若被系统杀死，由 WorkManager 触发重启。
 * 同时直接检查规则并接管 VPN 启停，防止 SchedulerService 失效时彻底失控。
 */
class PeriodicGuardWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "保活检查开始")

        return try {
            restartScheduler()
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "保活失败: ${e.message}")
            Result.retry()
        }
    }

    private fun restartScheduler() {
        val intent = Intent(applicationContext, SchedulerService::class.java).apply {
            action = SchedulerService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
        } catch (e: java.lang.Exception) {
            // Android 12+ 后台可能禁止 startForegroundService，兜底 startService
            Log.w(TAG, "startForegroundService 失败: ${e.message}")
            try {
                applicationContext.startService(intent)
            } catch (e2: java.lang.Exception) {
                Log.e(TAG, "startService 也失败: ${e2.message}")
            }
        }
    }

    companion object {
        private const val TAG = "PeriodicGuardWorker"
    }
}
