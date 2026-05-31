package com.networkguard.services

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import org.json.JSONArray

/**
 * 周期性保活 Worker（WorkManager 调度，每 15 分钟）。
 *
 * 双层保活：
 *  1️⃣ 确保 SchedulerService 存活（前台调度服务）
 *  2️⃣ 加载规则进行时间检查，若 SchedulerService 已死则直接接管 VPN 启停
 *
 * 注意：Android 12+ 后台启动前台服务受限，若 startForegroundService 失败，
 * 则改用 startService 兜底（SchedulerService 自身的 onStartCommand 会处理 foreground）。
 */
class PeriodicGuardWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "保活检查: 确保调度服务运行")

        return try {
            // 1. 尝试重启 SchedulerService（若已死则系统会重新拉起来）
            restartScheduler()

            // 2. 直接检查规则（兜底：即使 SchedulerService 挂了，这里也能接管）
            checkAndApplyRules()

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
        } catch (e: Exception) {
            // Android 12+ 后台可能禁止 startForegroundService
            // 兜底：用普通 startService
            Log.w(TAG, "startForegroundService 失败，回退到 startService: ${e.message}")
            try {
                applicationContext.startService(intent)
            } catch (e2: Exception) {
                Log.e(TAG, "startService 也失败: ${e2.message}")
            }
        }
    }

    private fun checkAndApplyRules() {
        val rulesJson = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(RULES_KEY, null) ?: return

        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().also { it.timeInMillis = now }
        val currentMinutes = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 +
                cal.get(java.util.Calendar.MINUTE)

        val arr = JSONArray(rulesJson)
        var activeRuleName: String? = null
        var activeBlockWifi = false
        var activeBlockMobile = false
        val activeAllowedApps = mutableListOf<String>()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (!obj.optBoolean("enabled", true)) continue
            val startMinutes = obj.getInt("startMinutes")
            val durationMs = obj.getLong("durationMs")
            val endMinutes = (startMinutes + durationMs / 60_000).toInt() % (24 * 60)
            val crossesMidnight = endMinutes < startMinutes
            val isActive = if (crossesMidnight) {
                currentMinutes >= startMinutes || currentMinutes < endMinutes
            } else {
                currentMinutes >= startMinutes && currentMinutes < endMinutes
            }
            if (isActive) {
                activeRuleName = obj.optString("name", "定时断网")
                activeBlockWifi = obj.optBoolean("blockWifi", true)
                activeBlockMobile = obj.optBoolean("blockMobile", true)
                val appsArr = obj.optJSONArray("allowedApps")
                if (appsArr != null) {
                    for (j in 0 until appsArr.length()) {
                        activeAllowedApps.add(appsArr.getString(j))
                    }
                }
                break
            }
        }

        if (activeRuleName != null) {
            Log.i(TAG, "Guard Worker 发现规则激活: $activeRuleName，确保 VPN 运行")
            val vpnIntent = Intent(applicationContext, NetworkGuardVpnService::class.java).apply {
                action = NetworkGuardVpnService.ACTION_START
                putExtra("blockWifi", activeBlockWifi)
                putExtra("blockMobile", activeBlockMobile)
                putExtra("reason", activeRuleName)
                putStringArrayListExtra("allowedApps", ArrayList(activeAllowedApps))
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    applicationContext.startForegroundService(vpnIntent)
                } else {
                    applicationContext.startService(vpnIntent)
                }
            } catch (_: Exception) {}
        }
    }

    companion object {
        private const val TAG = "PeriodicGuardWorker"
        private const val PREFS_NAME = "scheduler_rules"
        private const val RULES_KEY = "rules_json"
    }
}
