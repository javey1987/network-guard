package com.networkguard.services

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.networkguard.receivers.AlarmReceiver
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 系统级定时调度器。
 * 使用 Android AlarmManager 注册精确定时任务，即使 App 进程被杀死也能触发。
 * 支持：规则创建、取消、开机恢复、每日重复自动调度下一轮。
 */
class AlarmScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val schedulePrefs = context.getSharedPreferences("alarm_schedule", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "AlarmScheduler"
        private const val BASE_REQUEST_CODE = 30000
        private const val STORE_PREFIX = "rule_"
    }

    /**
     * 为一条规则注册定时开始/结束闹钟。
     */
    fun scheduleRule(
        ruleId: Int,
        ruleName: String,
        startMinutes: Int,
        durationMinutes: Int,
        daysOfWeek: List<Int>,
        blockWifi: Boolean,
        blockMobile: Boolean,
        allowedApps: List<String> = emptyList()
    ) {
        cancelRule(ruleId)

        val now = System.currentTimeMillis()
        val startTime = calcNextTime(startMinutes, daysOfWeek, now)
        val endTime = startTime + durationMinutes * 60_000L

        scheduleAlarm(
            ruleId = ruleId,
            action = AlarmReceiver.ACTION_START_BLOCK,
            triggerTime = startTime,
            ruleName = ruleName,
            blockWifi = blockWifi,
            blockMobile = blockMobile,
            allowedApps = allowedApps
        )

        if (endTime > now) {
            scheduleAlarm(
                ruleId = ruleId + 10000,
                action = AlarmReceiver.ACTION_STOP_BLOCK,
                triggerTime = endTime,
                ruleName = ruleName,
                blockWifi = false,
                blockMobile = false,
                endRuleId = ruleId  // 传原 ruleId 用于结束时调度下一次
            )
        }

        saveAlarmInfo(ruleId, ruleName, startMinutes, durationMinutes,
            daysOfWeek, startTime, endTime, blockWifi, blockMobile, allowedApps)
    }

    /**
     * 取消一条规则的所有闹钟
     */
    fun cancelRule(ruleId: Int) {
        cancelAlarm(ruleId)
        cancelAlarm(ruleId + 10000)
        removeAlarmInfo(ruleId)
    }

    /**
     * 开机后恢复所有持久化的闹钟
     */
    fun rescheduleAllOnBoot() {
        Log.i(TAG, "rescheduleAllOnBoot: 开机恢复闹钟")
        val allKeys = schedulePrefs.all.keys.filter { it.startsWith(STORE_PREFIX) }
        for (key in allKeys) {
            val data = loadAlarmInfo(key) ?: continue
            val now = System.currentTimeMillis()

            if (data.startTime > now) {
                scheduleAlarm(
                    ruleId = data.ruleId,
                    action = AlarmReceiver.ACTION_START_BLOCK,
                    triggerTime = data.startTime,
                    ruleName = data.ruleName,
                    blockWifi = data.blockWifi,
                    blockMobile = data.blockMobile,
                    allowedApps = data.allowedApps
                )
            }
            if (data.endTime > now) {
                scheduleAlarm(
                    ruleId = data.ruleId + 10000,
                    action = AlarmReceiver.ACTION_STOP_BLOCK,
                    triggerTime = data.endTime,
                    ruleName = data.ruleName,
                    blockWifi = false,
                    blockMobile = false,
                    endRuleId = data.ruleId
                )
            }
        }
    }

    /**
     * 规则结束后调度下一次（支持每日/每周重复）
     */
    fun rescheduleNext(ruleId: Int) {
        val targetKey = findRuleKey(ruleId) ?: return
        val data = loadAlarmInfo(targetKey) ?: return
        val now = System.currentTimeMillis()

        Log.d(TAG, "rescheduleNext: ruleId=$ruleId startMin=${data.startMinutes} dur=${data.durationMinutes}")
        val nextStart = calcNextTime(data.startMinutes, data.daysOfWeek, now + 60000)
        val nextEnd = nextStart + data.durationMinutes * 60_000L

        cancelAlarm(data.ruleId)
        cancelAlarm(data.ruleId + 10000)

        scheduleAlarm(
            ruleId = data.ruleId,
            action = AlarmReceiver.ACTION_START_BLOCK,
            triggerTime = nextStart,
            ruleName = data.ruleName,
            blockWifi = data.blockWifi,
            blockMobile = data.blockMobile,
            allowedApps = data.allowedApps
        )
        scheduleAlarm(
            ruleId = data.ruleId + 10000,
            action = AlarmReceiver.ACTION_STOP_BLOCK,
            triggerTime = nextEnd,
            ruleName = data.ruleName,
            blockWifi = false,
            blockMobile = false,
            endRuleId = data.ruleId
        )

        // 更新持久化的时间
        schedulePrefs.edit()
            .putLong("${targetKey}_startTime", nextStart)
            .putLong("${targetKey}_endTime", nextEnd)
            .apply()
    }

    // ─── 私有方法 ───────────────────────────────────────────────

    private fun scheduleAlarm(
        ruleId: Int,
        action: String,
        triggerTime: Long,
        ruleName: String,
        blockWifi: Boolean,
        blockMobile: Boolean,
        allowedApps: List<String> = emptyList(),
        endRuleId: Int = -1  // 仅 STOP 闹钟需要
    ) {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            this.action = action
            putExtra("ruleName", ruleName)
            putExtra("blockWifi", blockWifi)
            putExtra("blockMobile", blockMobile)
            putStringArrayListExtra("allowedApps", ArrayList(allowedApps))
            if (endRuleId != -1) putExtra("ruleId", endRuleId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            BASE_REQUEST_CODE + ruleId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        } else {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerTime, pendingIntent)
        }
    }

    private fun cancelAlarm(ruleId: Int) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            BASE_REQUEST_CODE + ruleId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    /**
     * 计算下一次触发时间
     */
    private fun calcNextTime(startMinutes: Int, daysOfWeek: List<Int>, nowMs: Long): Long {
        if (daysOfWeek.isEmpty()) {
            // 每日规则
            val today = Calendar.getInstance().also { it.timeInMillis = nowMs }
            today.set(Calendar.HOUR_OF_DAY, startMinutes / 60)
            today.set(Calendar.MINUTE, startMinutes % 60)
            today.set(Calendar.SECOND, 0)
            today.set(Calendar.MILLISECOND, 0)
            if (today.timeInMillis > nowMs) return today.timeInMillis
            today.add(Calendar.DAY_OF_YEAR, 1)
            return today.timeInMillis
        }

        // 每周规则：找到最近的匹配日
        val now = Calendar.getInstance().also { it.timeInMillis = nowMs }
        val currentDow = if (now.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7
                         else now.get(Calendar.DAY_OF_WEEK) - 1

        // 检查今天
        if (currentDow in daysOfWeek) {
            val today = Calendar.getInstance().also { it.timeInMillis = nowMs }
            today.set(Calendar.HOUR_OF_DAY, startMinutes / 60)
            today.set(Calendar.MINUTE, startMinutes % 60)
            today.set(Calendar.SECOND, 0)
            today.set(Calendar.MILLISECOND, 0)
            if (today.timeInMillis > nowMs) return today.timeInMillis
        }

        // 找下一个匹配日
        for (daysLater in 1..7) {
            val next = Calendar.getInstance().also { it.timeInMillis = nowMs }
            next.add(Calendar.DAY_OF_YEAR, daysLater)
            val nextDow = if (next.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) 7
                          else next.get(Calendar.DAY_OF_WEEK) - 1
            if (nextDow in daysOfWeek) {
                next.set(Calendar.HOUR_OF_DAY, startMinutes / 60)
                next.set(Calendar.MINUTE, startMinutes % 60)
                next.set(Calendar.SECOND, 0)
                next.set(Calendar.MILLISECOND, 0)
                return next.timeInMillis
            }
        }

        val fallback = Calendar.getInstance().also { it.timeInMillis = nowMs }
        fallback.add(Calendar.DAY_OF_YEAR, 7)
        fallback.set(Calendar.HOUR_OF_DAY, startMinutes / 60)
        fallback.set(Calendar.MINUTE, startMinutes % 60)
        fallback.set(Calendar.SECOND, 0)
        fallback.set(Calendar.MILLISECOND, 0)
        return fallback.timeInMillis
    }

    // ─── 持久化 ─────────────────────────────────────────────────

    private data class AlarmInfo(
        val ruleId: Int,
        val ruleName: String,
        val startMinutes: Int,
        val durationMinutes: Int,
        val daysOfWeek: List<Int>,
        val startTime: Long,
        val endTime: Long,
        val blockWifi: Boolean,
        val blockMobile: Boolean,
        val allowedApps: List<String>
    )

    private fun findRuleKey(ruleId: Int): String? {
        return schedulePrefs.all.keys.firstOrNull { key ->
            key.startsWith(STORE_PREFIX) && key.removePrefix(STORE_PREFIX)
                .split("_")[0].toIntOrNull() == ruleId
        }
    }

    private fun saveAlarmInfo(
        ruleId: Int, ruleName: String,
        startMinutes: Int, durationMinutes: Int,
        daysOfWeek: List<Int>, startTime: Long, endTime: Long,
        blockWifi: Boolean, blockMobile: Boolean,
        allowedApps: List<String>
    ) {
        val key = "${STORE_PREFIX}${ruleId}_${ruleName}"
        schedulePrefs.edit()
            .putInt("${key}_ruleId", ruleId)
            .putString("${key}_ruleName", ruleName)
            .putInt("${key}_startMinutes", startMinutes)
            .putInt("${key}_durationMinutes", durationMinutes)
            .putString("${key}_daysOfWeek", daysOfWeek.joinToString(","))
            .putLong("${key}_startTime", startTime)
            .putLong("${key}_endTime", endTime)
            .putBoolean("${key}_blockWifi", blockWifi)
            .putBoolean("${key}_blockMobile", blockMobile)
            .putString("${key}_allowedApps", allowedApps.joinToString(","))
            .apply()
    }

    private fun loadAlarmInfo(key: String): AlarmInfo? {
        val ruleId = schedulePrefs.getInt("${key}_ruleId", -1)
        if (ruleId == -1) return null
        val ruleName = schedulePrefs.getString("${key}_ruleName", "") ?: return null
        val daysStr = schedulePrefs.getString("${key}_daysOfWeek", "") ?: ""
        return AlarmInfo(
            ruleId = ruleId,
            ruleName = ruleName,
            startMinutes = schedulePrefs.getInt("${key}_startMinutes", 0),
            durationMinutes = schedulePrefs.getInt("${key}_durationMinutes", 0),
            daysOfWeek = if (daysStr.isEmpty()) emptyList()
                         else daysStr.split(",").mapNotNull { it.toIntOrNull() },
            startTime = schedulePrefs.getLong("${key}_startTime", 0),
            endTime = schedulePrefs.getLong("${key}_endTime", 0),
            blockWifi = schedulePrefs.getBoolean("${key}_blockWifi", true),
            blockMobile = schedulePrefs.getBoolean("${key}_blockMobile", true),
            allowedApps = (schedulePrefs.getString("${key}_allowedApps", "") ?: "")
                .split(",").filter { it.isNotEmpty() }
        )
    }

    private fun removeAlarmInfo(ruleId: Int) {
        val key = findRuleKey(ruleId) ?: return
        val keysToRemove = schedulePrefs.all.keys.filter { it.startsWith(key) }
        schedulePrefs.edit().apply {
            for (k in keysToRemove) remove(k)
            apply()
        }
    }
}
