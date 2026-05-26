import 'package:flutter_local_notifications/flutter_local_notifications.dart';

class NotificationService {
  static final FlutterLocalNotificationsPlugin _plugin =
      FlutterLocalNotificationsPlugin();

  /// 初始化通知渠道
  static Future<void> init() async {
    const androidSettings = AndroidInitializationSettings('@mipmap/ic_launcher');
    const iosSettings = DarwinInitializationSettings(
      requestAlertPermission: true,
      requestBadgePermission: true,
      requestSoundPermission: true,
    );
    const initSettings = InitializationSettings(
      android: androidSettings,
      iOS: iosSettings,
    );
    await _plugin.initialize(initSettings);
  }

  /// 通知：正在断网（常驻通知，不可清除）
  static Future<void> showBlockStarted(String ruleName) async {
    const androidDetails = AndroidNotificationDetails(
      'network_block',
      '断网通知',
      channelDescription: '定时断网提醒',
      importance: Importance.high,
      priority: Priority.high,
      ongoing: true,
      autoCancel: false,
    );
    const details = NotificationDetails(
      android: androidDetails,
      iOS: DarwinNotificationDetails(presentAlert: true, presentBadge: true, presentSound: true),
    );
    await _plugin.show(
      1001,
      '🌙 网络已关闭',
      '「$ruleName」已生效，请专注当下',
      details,
    );
  }

  /// 通知：网络已恢复
  static Future<void> showBlockEnded(String ruleName) async {
    const androidDetails = AndroidNotificationDetails(
      'network_block',
      '断网通知',
      channelDescription: '定时断网提醒',
      importance: Importance.defaultImportance,
      priority: Priority.defaultPriority,
    );
    const details = NotificationDetails(
      android: androidDetails,
      iOS: DarwinNotificationDetails(presentAlert: true, presentBadge: true),
    );
    await _plugin.show(
      1002,
      '☀️ 网络已恢复',
      '「$ruleName」已结束，可以上网了',
      details,
    );
  }

  /// 通知：即将断网
  static Future<void> showUpcomingBlock(String ruleName, int minutesLeft) async {
    const androidDetails = AndroidNotificationDetails(
      'network_block_upcoming',
      '断网预告',
      channelDescription: '即将断网提醒',
      importance: Importance.defaultImportance,
      priority: Priority.defaultPriority,
    );
    const details = NotificationDetails(
      android: androidDetails,
      iOS: DarwinNotificationDetails(presentAlert: true, presentSound: true),
    );
    await _plugin.show(
      1003,
      '⚠️ 即将断网',
      '「$ruleName」将在 $minutesLeft 分钟后开始',
      details,
    );
  }
}
