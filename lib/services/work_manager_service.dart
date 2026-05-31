import 'package:flutter/services.dart';
import '../models/schedule_rule.dart';

/// WorkManager 保活调度服务 (底层使用 Android WorkManager)
///
/// 在 AlarmManager 之外再加一层 WorkManager 兜底：
///  - 周期性保活 Worker (15 分钟) → 确保 SchedulerService 存活
///  - 一次性工人 Worker → 替代 AlarmManager 的精确定时
///
/// 好处: WorkManager 在国产手机上兼容性优于 AlarmManager
class WorkManagerService {
  static const _channel = MethodChannel('com.networkguard/workmanager');

  /// 注册周期性保活 Worker
  static Future<bool> startPeriodicGuard() async {
    try {
      final result = await _channel.invokeMethod<bool>('startPeriodicGuard');
      return result ?? false;
    } on PlatformException catch (e) {
      print('WorkManager startPeriodicGuard error: ${e.message}');
      return false;
    }
  }

  /// 取消周期性保活 Worker
  static Future<bool> stopPeriodicGuard() async {
    try {
      final result = await _channel.invokeMethod<bool>('stopPeriodicGuard');
      return result ?? false;
    } on PlatformException catch (e) {
      print('WorkManager stopPeriodicGuard error: ${e.message}');
      return false;
    }
  }

  /// 为一条规则注册一次性 Worker (开始封锁)
  static Future<bool> scheduleBlockStart(ScheduleRule rule) async {
    final now = DateTime.now();
    final targetTime = _calcNextStart(rule, now);
    if (targetTime == null) return false;

    final triggerTimeMs = targetTime.millisecondsSinceEpoch;
    if (triggerTimeMs <= now.millisecondsSinceEpoch) return false;

    try {
      await _channel.invokeMethod<bool>('scheduleBlockWorker', {
        'ruleId': rule.id ?? 0,
        'isStart': true,
        'triggerTimeMs': triggerTimeMs,
        'ruleName': rule.name,
        'blockWifi': rule.blockWifi,
        'blockMobile': rule.blockMobile,
        'allowedApps': rule.allowedApps,
      });
      return true;
    } on PlatformException catch (e) {
      print('WorkManager scheduleBlockStart error: ${e.message}');
      return false;
    }
  }

  /// 为一条规则注册一次性 Worker (结束封锁)
  static Future<bool> scheduleBlockEnd(ScheduleRule rule) async {
    final now = DateTime.now();
    final startTime = _calcNextStart(rule, now);
    if (startTime == null) return false;

    // 计算结束时间
    int durationMinutes;
    if (rule.crossesMidnight) {
      durationMinutes = (24 * 60 - rule.startMinutes) + rule.endMinutes;
    } else {
      durationMinutes = rule.endMinutes - rule.startMinutes;
    }
    final endTime = startTime.add(Duration(minutes: durationMinutes));
    final triggerTimeMs = endTime.millisecondsSinceEpoch;
    if (triggerTimeMs <= now.millisecondsSinceEpoch) return false;

    try {
      await _channel.invokeMethod<bool>('scheduleBlockWorker', {
        'ruleId': rule.id ?? 0,
        'isStart': false,
        'triggerTimeMs': triggerTimeMs,
        'ruleName': rule.name,
        'blockWifi': false,
        'blockMobile': false,
        'allowedApps': <String>[],
      });
      return true;
    } on PlatformException catch (e) {
      print('WorkManager scheduleBlockEnd error: ${e.message}');
      return false;
    }
  }

  /// 取消一条规则的所有 Worker
  static Future<bool> cancelRule(int ruleId) async {
    try {
      final result =
          await _channel.invokeMethod<bool>('cancelBlockWorker', {
        'ruleId': ruleId,
      });
      return result ?? false;
    } on PlatformException catch (e) {
      print('WorkManager cancelRule error: ${e.message}');
      return false;
    }
  }

  /// 为所有规则注册 Worker (启动时调用)
  static Future<void> scheduleAll(List<ScheduleRule> rules) async {
    for (final rule in rules.where((r) => r.enabled)) {
      await scheduleBlockStart(rule);
      await scheduleBlockEnd(rule);
    }
  }

  /// 计算规则下一次开始时间
  static DateTime? _calcNextStart(ScheduleRule rule, DateTime now) {
    DateTime candidate = DateTime(
      now.year,
      now.month,
      now.day,
      rule.startHour,
      rule.startMinute,
    );

    // 如果今天的已过，或不需要重复
    if (candidate.isBefore(now) || candidate.isAtSameMomentAs(now)) {
      candidate = candidate.add(const Duration(days: 1));
    }

    // 非每日重复：找下一个匹配的星期
    if (!rule.repeatDaily && rule.repeatDays.isNotEmpty) {
      int maxLoop = 14; // 上限2周
      while (maxLoop > 0) {
        final wd = candidate.weekday - 1; // Dart: Mon=1..Sun=7 → 0..6
        if (rule.repeatDays.contains(wd)) {
          return candidate;
        }
        candidate = candidate.add(const Duration(days: 1));
        maxLoop--;
      }
      return null; // 找不到匹配日期
    }

    return candidate;
  }
}
