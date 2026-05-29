import 'dart:convert';
import 'package:flutter/services.dart';
import '../models/schedule_rule.dart';

/// 常驻调度服务 (底层使用 Native 前台服务)
/// 在华为/小米等国产手机上，即使滑掉 App 进程，前台服务也尽量保持运行。
/// 通过 Handler 定时检查规则时间，不再完全依赖 AlarmManager。
class SchedulerService {
  static const _channel = MethodChannel('com.networkguard/scheduler');

  /// 将当前所有规则保存到 Native 侧 (SharedPreferences)
  /// Native 侧的 SchedulerService 读取此数据自主调度 VPN 启停
  static Future<bool> saveRules(List<ScheduleRule> rules) async {
    try {
      final jsonList = rules.where((r) => r.enabled).map((rule) {
        int duration;
        if (rule.crossesMidnight) {
          duration = (24 * 60 - rule.startMinutes) + rule.endMinutes;
        } else {
          duration = rule.endMinutes - rule.startMinutes;
        }

        return {
          'id': rule.id ?? 0,
          'name': rule.name,
          'startMinutes': rule.startMinutes,
          'durationMs': duration * 60 * 1000, // 毫秒
          'enabled': rule.enabled,
          'blockWifi': rule.blockWifi,
          'blockMobile': rule.blockMobile,
          'allowedApps': rule.allowedApps,
        };
      }).toList();

      final result = await _channel.invokeMethod<bool>('saveRules', {
        'rulesJson': jsonEncode(jsonList),
      });
      return result ?? false;
    } on PlatformException catch (e) {
      print('Scheduler saveRules error: ${e.message}');
      return false;
    }
  }

  /// 启动常驻调度前台服务
  static Future<bool> start() async {
    try {
      final result = await _channel.invokeMethod<bool>('startScheduler');
      return result ?? false;
    } on PlatformException catch (e) {
      print('Scheduler start error: ${e.message}');
      return false;
    }
  }

  /// 停止调度服务
  static Future<bool> stop() async {
    try {
      final result = await _channel.invokeMethod<bool>('stopScheduler');
      return result ?? false;
    } on PlatformException catch (e) {
      print('Scheduler stop error: ${e.message}');
      return false;
    }
  }

  /// 同步所有规则并确保调度服务运行
  static Future<void> syncAndStart(List<ScheduleRule> rules) async {
    await saveRules(rules);
    await start();
  }
}
