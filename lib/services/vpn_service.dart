import 'package:flutter/services.dart';

/// 与原生端通信的 VPN 服务封装
class VpnService {
  static const _channel = MethodChannel('com.networkguard/vpn');

  static VoidCallback? _onVpnAuthorizedCallback;

  /// 初始化回调监听（接收 Native → Flutter 的异步通知）
  static void init() {
    _channel.setMethodCallHandler((call) async {
      switch (call.method) {
        case 'onVpnAuthorized':
          _onVpnAuthorizedCallback?.call();
          break;
      }
    });
  }

  /// 设置 VPN 授权成功的回调
  static void setOnVpnAuthorizedCallback(VoidCallback callback) {
    _onVpnAuthorizedCallback = callback;
  }

  /// 启动 VPN（封锁网络）
  static Future<bool> startVpn({
    required bool blockWifi,
    required bool blockMobile,
    required String reason,
    List<String> allowedApps = const [],
  }) async {
    try {
      final result = await _channel.invokeMethod<bool>('startVpn', {
        'blockWifi': blockWifi,
        'blockMobile': blockMobile,
        'reason': reason,
        'allowedApps': allowedApps,
      });
      return result ?? false;
    } on PlatformException catch (e) {
      print('VPN start error: ${e.message}');
      return false;
    }
  }

  /// 停止 VPN（完全关闭，销毁虚拟网卡，恢复网络）
  static Future<bool> stopVpn() async {
    try {
      final result = await _channel.invokeMethod<bool>('stopVpn');
      return result ?? false;
    } on PlatformException catch (e) {
      print('VPN stop error: ${e.message}');
      return false;
    }
  }

  /// 检查 VPN 是否正在运行
  static Future<bool> isVpnRunning() async {
    try {
      final result = await _channel.invokeMethod<bool>('isVpnRunning');
      return result ?? false;
    } on PlatformException {
      return false;
    }
  }

  /// 获取 VpnService 状态
  static Future<String> getStatus() async {
    try {
      final result = await _channel.invokeMethod<String>('getStatus');
      return result ?? 'unknown';
    } on PlatformException {
      return 'unknown';
    }
  }
}
