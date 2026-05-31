package com.networkguard.services

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log

/**
 * JobScheduler 保活/定时调度器（Android 原生，无外部依赖）。
 *
 * 职责：
 *  - 周期性保活 Job（15 分钟）→ 重启 SchedulerService
 *  - 一次性 Job（到点触发）→ 启动/停止 NetworkGuardVpnService
 *
 * 优势：Android 原生 API，不依赖第三方库，兼容性优于 AlarmManager。
 */
object JobSchedulerScheduler {

    private const val TAG = "JobSchedulerScheduler"
    private const val PERIODIC_JOB_ID = 10001
    private const val BLOCK_JOB_BASE = 20000

    /** 注册周期性保活 Job（每 15 分钟） */
    fun startPeriodicGuard(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        // 先取消旧的再注册，防止重复
        scheduler.cancel(PERIODIC_JOB_ID)

        val component = ComponentName(context, PeriodicGuardJobService::class.java)
        val job = JobInfo.Builder(PERIODIC_JOB_ID, component)
            .setPeriodic(15 * 60 * 1000L)  // 15 分钟
            .setPersisted(true)             // 设备重启后保留
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
            .build()

        val result = scheduler.schedule(job)
        Log.i(TAG, "周期性保活 Job 注册: ${if (result == JobScheduler.RESULT_SUCCESS) "成功" else "失败"}")
    }

    /** 取消周期性保活 Job */
    fun stopPeriodicGuard(context: Context) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.cancel(PERIODIC_JOB_ID)
        Log.i(TAG, "周期性保活 Job 已取消")
    }

    /** 注册一次性封锁/恢复 Job */
    fun scheduleJob(
        context: Context,
        ruleId: Int,
        isStart: Boolean,
        triggerTimeMs: Long,
        ruleName: String,
        blockWifi: Boolean,
        blockMobile: Boolean,
        allowedApps: List<String> = emptyList()
    ) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val jobId = BLOCK_JOB_BASE + ruleId * 2 + (if (isStart) 0 else 1)
        val now = System.currentTimeMillis()
        val delayMs = triggerTimeMs - now
        if (delayMs <= 0) return

        // 先取消旧任务
        scheduler.cancel(jobId)

        val extras = PersistableBundle().apply {
            putInt("ruleId", ruleId)
            putBoolean("isStart", isStart)
            putString("ruleName", ruleName)
            putBoolean("blockWifi", blockWifi)
            putBoolean("blockMobile", blockMobile)
            putStringArray("allowedApps", allowedApps.toTypedArray())
        }

        val component = ComponentName(context, BlockJobService::class.java)
        val job = JobInfo.Builder(jobId, component)
            .setMinimumLatency(delayMs)
            .setOverrideDeadline(delayMs + 60_000)  // 最多延迟 1 分钟
            .setPersisted(true)
            .setExtras(extras)
            .build()

        val result = scheduler.schedule(job)
        Log.i(TAG, "${if (isStart) "封锁" else "恢复"} Job 注册: id=$jobId delay=${delayMs/1000}s ${if (result == JobScheduler.RESULT_SUCCESS) "成功" else "失败"}")
    }

    /** 取消一条规则的所有 Job */
    fun cancelJob(context: Context, ruleId: Int) {
        val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        scheduler.cancel(BLOCK_JOB_BASE + ruleId * 2)
        scheduler.cancel(BLOCK_JOB_BASE + ruleId * 2 + 1)
        Log.i(TAG, "Job 已取消: ruleId=$ruleId")
    }
}

/** 周期性保活 JobService */
class PeriodicGuardJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG_P, "保活 Job 触发")

        try {
            val intent = Intent(this, SchedulerService::class.java).apply {
                action = SchedulerService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG_P, "startForegroundService 失败: ${e.message}")
            try { startService(Intent(this, SchedulerService::class.java).apply { action = SchedulerService.ACTION_START }) }
            catch (_: Exception) {}
        }

        jobFinished(params, false)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.w(TAG_P, "保活 Job 被系统停止")
        return true  // 重试
    }

    companion object {
        private const val TAG_P = "PeriodicGuardJob"
    }
}

/** 一次性封锁/恢复 JobService */
class BlockJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val extras = params?.extras ?: run { jobFinished(params, false); return true }
        val ruleName = extras.getString("ruleName") ?: "定时断网"
        val isStart = extras.getBoolean("isStart", true)
        val blockWifi = extras.getBoolean("blockWifi", true)
        val blockMobile = extras.getBoolean("blockMobile", true)
        val allowedApps = extras.getStringArray("allowedApps") ?: emptyArray()

        Log.i(TAG_B, "Job 触发: ${if (isStart) "封锁" else "恢复"} $ruleName")

        try {
            if (isStart) {
                val vpnIntent = Intent(this, NetworkGuardVpnService::class.java).apply {
                    action = NetworkGuardVpnService.ACTION_START
                    putExtra("blockWifi", blockWifi)
                    putExtra("blockMobile", blockMobile)
                    putExtra("reason", ruleName)
                    putStringArrayListExtra("allowedApps", ArrayList(allowedApps.toList()))
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(vpnIntent)
                } else {
                    startService(vpnIntent)
                }
            } else {
                val stopIntent = Intent(this, NetworkGuardVpnService::class.java).apply {
                    action = NetworkGuardVpnService.ACTION_STOP
                }
                startService(stopIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG_B, "Job 执行失败: ${e.message}")
        }

        jobFinished(params, false)
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        Log.w(TAG_B, "Job 被系统停止")
        return true  // 重试
    }

    companion object {
        private const val TAG_B = "BlockJobService"
    }
}
