import 'package:flutter/services.dart';
import '../models/schedule_rule.dart';

/// JobScheduler 保活调度服务 (底层使用 Android JobScheduler)
///
/// Android 原生 API，无外部依赖，兼容性优于 AlarmManager。
/// 在 AlarmManager 和 SchedulerService 之外再加一层兜底：
///   - 周期性保活 Job (15 分钟) → 确保 SchedulerService 存活
///   - 一次性 Job → 到点启动/停止封锁
class JobSchedulerService {
  static const _channel = MethodChannel('com.networkguard/jobscheduler');

  /// 注册周期性保活 Job
  static Future<bool> startPeriodicGuard() async {
    try {
      final result = await _channel.invokeMethod<bool>('startPeriodicGuard');
      return result ?? false;
    } on PlatformException catch (e) {
      print('JobScheduler startPeriodicGuard error: ${e.message}');
      return false;
    }
  }

  /// 取消周期性保活 Job
  static Future<bool> stopPeriodicGuard() async {
    try {
      final result = await _channel.invokeMethod<bool>('stopPeriodicGuard');
      return result ?? false;
    } on PlatformException catch (e) {
      print('JobScheduler stopPeriodicGuard error: ${e.message}');
      return false;
    }
  }

  /// 为一条规则注册一次性 Job (开始封锁)
  static Future<bool> scheduleBlockStart(ScheduleRule rule) async {
    final now = DateTime.now();
    final targetTime = _calcNextStart(rule, now);
    if (targetTime == null) return false;

    final triggerTimeMs = targetTime.millisecondsSinceEpoch;
    if (triggerTimeMs <= now.millisecondsSinceEpoch) return false;

    try {
      await _channel.invokeMethod<bool>('scheduleBlockJob', {
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
      print('JobScheduler scheduleBlockStart error: ${e.message}');
      return false;
    }
  }

  /// 为一条规则注册一次性 Job (结束封锁)
  static Future<bool> scheduleBlockEnd(ScheduleRule rule) async {
    final now = DateTime.now();
    final startTime = _calcNextStart(rule, now);
    if (startTime == null) return false;

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
      await _channel.invokeMethod<bool>('scheduleBlockJob', {
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
      print('JobScheduler scheduleBlockEnd error: ${e.message}');
      return false;
    }
  }

  /// 取消一条规则的所有 Job
  static Future<bool> cancelRule(int ruleId) async {
    try {
      final result = await _channel.invokeMethod<bool>('cancelBlockJob', {
        'ruleId': ruleId,
      });
      return result ?? false;
    } on PlatformException catch (e) {
      print('JobScheduler cancelRule error: ${e.message}');
      return false;
    }
  }

  /// 为所有规则注册 Job (启动时调用)
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

    if (candidate.isBefore(now) || candidate.isAtSameMomentAs(now)) {
      candidate = candidate.add(const Duration(days: 1));
    }

    if (!rule.repeatDaily && rule.repeatDays.isNotEmpty) {
      int maxLoop = 14;
      while (maxLoop > 0) {
        final wd = candidate.weekday - 1;
        if (rule.repeatDays.contains(wd)) {
          return candidate;
        }
        candidate = candidate.add(const Duration(days: 1));
        maxLoop--;
      }
      return null;
    }

    return candidate;
  }
}
