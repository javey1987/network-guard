import 'package:flutter/services.dart';

/// 屏幕固定（Screen Pinning）服务封装。
///
/// 调用 Android 原生 startLockTask() / stopLockTask() 将 App 锁定在前台，
/// 防止用户通过 Home / Recent 按键退出专注模式。
///
/// 用户需先在系统设置中开启「屏幕固定」功能（设置 → 安全 → 屏幕固定），
/// 此服务会检查状态并给出引导。
class LockTaskService {
  static const _channel = MethodChannel('com.networkguard/locktask');

  static VoidCallback? _onDeviceAdminChangedCallback;

  /// 初始化，监听 Native 端回调
  static void init() {
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onDeviceAdminChanged':
          _onDeviceAdminChangedCallback?.call();
          break;
      }
    });
  }

  /// 设置设备管理员状态变化的回调
  static void setOnDeviceAdminChangedCallback(VoidCallback callback) {
    _onDeviceAdminChangedCallback = callback;
  }

  /// 固定当前 App 到屏幕（进入严格模式）
  static Future<bool> lock() async {
    try {
      final result = await _channel.invokeMethod<bool>('startLockTask');
      return result ?? false;
    } on PlatformException catch (e) {
      print('LockTask start error: ${e.message}');
      return false;
    }
  }

  /// 解除屏幕固定
  static Future<bool> unlock() async {
    try {
      final result = await _channel.invokeMethod<bool>('stopLockTask');
      return result ?? false;
    } on PlatformException catch (e) {
      print('LockTask stop error: ${e.message}');
      return false;
    }
  }

  /// 检查屏幕固定是否已启用
  static Future<bool> isLocked() async {
    try {
      final result = await _channel.invokeMethod<bool>('isLockTaskActive');
      return result ?? false;
    } on PlatformException {
      return false;
    }
  }

  /// 请求设备管理员授权
  static Future<bool> requestDeviceAdmin() async {
    try {
      final result = await _channel.invokeMethod<bool>('requestDeviceAdmin');
      return result ?? false;
    } on PlatformException {
      return false;
    }
  }

  /// 检查是否已获得设备管理员授权
  static Future<bool> isDeviceAdmin() async {
    try {
      final result = await _channel.invokeMethod<bool>('isDeviceAdmin');
      return result ?? false;
    } on PlatformException {
      return false;
    }
  }

  /// 取消设备管理员授权
  static Future<bool> deactivateDeviceAdmin() async {
    try {
      final result = await _channel.invokeMethod<bool>('deactivateDeviceAdmin');
      return result ?? false;
    } on PlatformException {
      return false;
    }
  }
}
