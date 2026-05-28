import 'package:flutter/services.dart';

/// 使用统计服务，从 Android UsageStatsManager 查询应用使用数据。
class StatsService {
  static const _channel = MethodChannel('com.networkguard/stats');

  /// 查询指定时间范围内的应用使用统计
  /// 返回 [{packageName, appName, totalTimeInForeground, icon}]
  static Future<List<Map<String, dynamic>>> queryUsageStats({
    required int startTimeMs,
    required int endTimeMs,
  }) async {
    try {
      final result = await _channel.invokeMethod<List<dynamic>>('queryUsageStats', {
        'startTime': startTimeMs,
        'endTime': endTimeMs,
      });
      if (result == null) return [];
      return result.cast<Map<String, dynamic>>();
    } on PlatformException {
      return [];
    }
  }

  /// 检查是否有 Usage Stats 权限
  static Future<bool> hasUsageStatsPermission() async {
    try {
      final ok = await _channel.invokeMethod<bool>('hasUsageStatsPermission');
      return ok ?? false;
    } catch (_) {
      return false;
    }
  }

  /// 打开设置中的使用权限页面
  static Future<void> requestUsageStatsPermission() async {
    try {
      await _channel.invokeMethod('requestUsageStatsPermission');
    } catch (_) {}
  }

  /// 获取已安装应用列表（用于白名单选择）
  /// 返回 [{packageName, appName, icon (base64)}]
  static Future<List<Map<String, dynamic>>> getInstalledApps() async {
    try {
      final result = await _channel.invokeMethod<List<dynamic>>('getInstalledApps');
      if (result == null) return [];
      return result.cast<Map<String, dynamic>>().where((app) {
        // 过滤掉系统应用
        final pkg = (app['packageName'] as String?) ?? '';
        return !pkg.startsWith('android.') &&
               !pkg.startsWith('com.android.') &&
               !pkg.startsWith('com.google.android.apps.') &&
               pkg.isNotEmpty;
      }).toList();
    } on PlatformException {
      return [];
    }
  }
}
