package com.networkguard.services

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * WorkManager 调度器。
 * 作为 AlarmManager 的互补方案，在国产手机上兼容性更好。
 *
 * 职责：
 *  - 15 分钟周期保活工作 → 确保 SchedulerService 运行
 *  - 一次性精确定时工作 → 替代 AlarmManager 的精确闹钟（到点启动/停止封锁）
 */
class WorkManagerScheduler(private val context: Context) {

    companion object {
        private const val TAG = "WorkManagerScheduler"
        private const val PERIODIC_TAG = "periodic_guard"
        private const val BLOCK_TAG_PREFIX = "block_"

        /** 注册周期性保活 Worker */
        fun startPeriodicGuard(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.NOT_REQUIRED) // 不依赖网络
                .build()

            val request = PeriodicWorkRequestBuilder<PeriodicGuardWorker>(
                15, TimeUnit.MINUTES    // 最短间隔 15 分钟
            ).setConstraints(constraints)
             .addTag(PERIODIC_TAG)
             .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_TAG,
                ExistingPeriodicWorkPolicy.KEEP, // 不重复注册
                request
            )
            Log.i(TAG, "周期性保活 Worker 已注册")
        }

        /** 取消周期性保活 */
        fun stopPeriodicGuard(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_TAG)
            Log.i(TAG, "周期性保活 Worker 已取消")
        }

        /**
         * 为一条规则注册一次性开始/结束 Worker。
         * 每个规则两个 Worker：startBlock（到点封锁）、stopBlock（到点恢复）
         */
        fun scheduleBlockWorker(
            context: Context,
            ruleId: Int,
            isStart: Boolean,  // true = 封锁 Worker, false = 恢复 Worker
            triggerTimeMs: Long,
            ruleName: String,
            blockWifi: Boolean,
            blockMobile: Boolean,
            allowedApps: List<String> = emptyList()
        ) {
            val tag = "${BLOCK_TAG_PREFIX}${ruleId}_${if (isStart) "start" else "stop"}"
            val now = System.currentTimeMillis()
            val delayMs = triggerTimeMs - now
            if (delayMs <= 0) return  // 已过期，跳过

            val inputData = androidx.work.Data.Builder()
                .putInt("ruleId", ruleId)
                .putString("ruleName", ruleName)
                .putBoolean("isStart", isStart)
                .putBoolean("blockWifi", blockWifi)
                .putBoolean("blockMobile", blockMobile)
                .putStringArray("allowedApps", allowedApps.toTypedArray())
                .build()

            val request = OneTimeWorkRequestBuilder<BlockWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .addTag(tag)
                .setInputData(inputData)
                .build()

            WorkManager.getInstance(context)
                .enqueueUniqueWork(tag, ExistingWorkPolicy.REPLACE, request)

            Log.i(TAG, "Worker 已注册: ${if (isStart) "封锁" else "恢复"} ruleId=$ruleId 延迟=${delayMs / 1000}s")
        }

        /** 取消一条规则的所有 Worker（开始+结束） */
        fun cancelBlockWorker(context: Context, ruleId: Int) {
            WorkManager.getInstance(context).cancelUniqueWork("${BLOCK_TAG_PREFIX}${ruleId}_start")
            WorkManager.getInstance(context).cancelUniqueWork("${BLOCK_TAG_PREFIX}${ruleId}_stop")
            Log.i(TAG, "Worker 已取消: ruleId=$ruleId")
        }
    }
}
