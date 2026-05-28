import 'package:flutter/services.dart';

/// PIN 管理服务。
/// PIN 存储在 Native SharedPreferences 中（应用私有存储，其他 App 不可读）。
class PinService {
  static const _channel = MethodChannel('com.networkguard/prefs');

  /// 设置管理员 PIN
  static Future<bool> setPin(String pin) async {
    if (pin.length < 4) return false;
    try {
      await _channel.invokeMethod('setAdminPin', pin);
      return true;
    } catch (_) {
      return false;
    }
  }

  /// 验证 PIN
  static Future<bool> verifyPin(String pin) async {
    try {
      final ok = await _channel.invokeMethod<bool>('verifyAdminPin', pin);
      return ok ?? false;
    } catch (_) {
      return false;
    }
  }

  /// 是否已设置 PIN
  static Future<bool> hasPin() async {
    try {
      final ok = await _channel.invokeMethod<bool>('hasAdminPin');
      return ok ?? false;
    } catch (_) {
      return false;
    }
  }

  /// 清除 PIN
  static Future<void> clearPin() async {
    try {
      await _channel.invokeMethod('clearAdminPin');
    } catch (_) {}
  }
}
