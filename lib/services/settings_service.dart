import 'package:flutter/services.dart';

/// 系统设置跳转服务
/// 打开应用详情页，引导用户设置自启动/白名单
class SettingsService {
  static const _channel = MethodChannel('com.networkguard/settings');

  /// 打开系统应用详情页
  /// 用户可在其中找到「自启动」「受保护应用」「电池优化」等设置
  static Future<bool> openAppSettings() async {
    try {
      final result = await _channel.invokeMethod<bool>('openAppSettings');
      return result ?? false;
    } on PlatformException catch (e) {
      print('SettingsService openAppSettings error: ${e.message}');
      return false;
    }
  }
}
