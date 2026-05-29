import 'package:flutter/services.dart';
import '../models/schedule_rule.dart';

/// 系统级定时调度服务（底层使用 Android AlarmManager）
/// 即使 App 进程被杀死，定时任务仍然会触发。
class AlarmService {
  static const _channel = MethodChannel('com.networkguard/alarm');

  /// 为一条规则注册定时闹钟
  static Future<bool> scheduleRule(ScheduleRule rule) async {
    // 计算持续分钟数
    int duration;
    if (rule.crossesMidnight) {
      duration = (24 * 60 - rule.startMinutes) + rule.endMinutes;
    } else {
      duration = rule.endMinutes - rule.startMinutes;
    }

    try {
      final result = await _channel.invokeMethod<bool>('scheduleRule', {
        'ruleId': rule.id ?? 0,
        'ruleName': rule.name,
        'startMinutes': rule.startMinutes,
        'durationMinutes': duration,
        'daysOfWeek': rule.repeatDaily ? rule.repeatDays : <int>[],
        'blockWifi': rule.blockWifi,
        'blockMobile': rule.blockMobile,
        'allowedApps': rule.allowedApps,
      });
      return result ?? false;
    } on PlatformException catch (e) {
      print('Alarm schedule error: ${e.message}');
      return false;
    }
  }

  /// 取消一条规则的所有闹钟
  static Future<bool> cancelRule(int ruleId) async {
    try {
      final result = await _channel.invokeMethod<bool>('cancelRule', {
        'ruleId': ruleId,
      });
      return result ?? false;
    } on PlatformException catch (e) {
      print('Alarm cancel error: ${e.message}');
      return false;
    }
  }

  /// 为所有规则注册闹钟（启动时调用）
  static Future<void> scheduleAll(List<ScheduleRule> rules) async {
    for (final rule in rules.where((r) => r.enabled)) {
      await scheduleRule(rule);
    }
  }

  /// 删除所有闹钟（清空所有规则时调用）
  static Future<void> cancelAll(List<ScheduleRule> rules) async {
    for (final rule in rules) {
      await cancelRule(rule.id ?? 0);
    }
  }
}
