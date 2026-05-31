package com.networkguard.services

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * 周期性保活 Worker（WorkManager 调度，每 15 分钟）。
 *
 * 使用 Worker（非 CoroutineWorker）减少依赖。
 * 确保 SchedulerService 存活。若被系统杀死，由 WorkManager 触发重启。
 */
class PeriodicGuardWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {

    override fun doWork(): Result {
        Log.i(TAG, "保活检查开始")

        try {
            val intent = Intent(applicationContext, SchedulerService::class.java).apply {
                action = SchedulerService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(intent)
            } else {
                applicationContext.startService(intent)
            }
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "保活失败: ${e.message}")
            return Result.retry()
        }
    }

    companion object {
        private const val TAG = "PeriodicGuardWorker"
    }
}
