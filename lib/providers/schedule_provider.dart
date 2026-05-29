import 'dart:async';
import 'package:flutter/material.dart';
import '../models/schedule_rule.dart';
import '../services/database_service.dart';
import '../services/vpn_service.dart';
import '../services/notification_service.dart';
import '../services/lock_task_service.dart';
import '../services/alarm_service.dart';

class ScheduleProvider extends ChangeNotifier {
  List<ScheduleRule> _rules = [];
  bool _isNetworkBlocked = false;
  bool _isStrictMode = false;
  bool _vpnMonitorActive = false;
  String _activeRule = '';
  Timer? _checkTimer;
  /// 首次检查标记：加载后第一次 timer 触发时不执行断网，仅记录时间
  bool _firstCheckDone = false;

  List<ScheduleRule> get rules => List.unmodifiable(_rules);
  bool get isNetworkBlocked => _isNetworkBlocked;
  bool get isStrictMode => _isStrictMode;
  bool get vpnMonitorActive => _vpnMonitorActive;
  String get activeRuleName => _activeRule;

  ScheduleProvider() {
    VpnService.setOnVpnAuthorizedCallback(() {
      // VPN 授权成功后刷新状态
      _vpnMonitorActive = true;
      notifyListeners();
    });
    LockTaskService.setOnDeviceAdminChangedCallback(() {
      notifyListeners();
    });
  }

  Future<void> loadRules() async {
    _rules = await DatabaseService.getAll();
    // 加载时同步系统闹钟
    AlarmService.scheduleAll(_rules);
    // 首次检查标记置 false，下次 timer 触发时只记录时间不执行断网
    _firstCheckDone = false;
    notifyListeners();
  }

  Future<void> addRule(ScheduleRule rule) async {
    final id = await DatabaseService.insert(rule);
    final savedRule = rule.copyWith(id: id);
    _rules.add(savedRule);
    _rules.sort((a, b) => a.startMinutes.compareTo(b.startMinutes));
    // 同步到系统鬧钟（即使进程被杀死也能触发）
    if (savedRule.enabled) {
      AlarmService.scheduleRule(savedRule);
    }
    _firstCheckDone = true;
    _checkAndApply();
    notifyListeners();
  }

  Future<void> updateRule(ScheduleRule rule) async {
    await DatabaseService.update(rule);
    final idx = _rules.indexWhere((r) => r.id == rule.id);
    if (idx >= 0) _rules[idx] = rule;
    // 同步到系统闹钟
    AlarmService.cancelRule(rule.id ?? 0);
    if (rule.enabled) {
      AlarmService.scheduleRule(rule);
    }
    _firstCheckDone = true;
    _checkAndApply();
    notifyListeners();
  }

  Future<void> toggleEnabled(ScheduleRule rule) async {
    final updated = rule.copyWith(enabled: !rule.enabled);
    // toggleEnabled 会调 updateRule，后者已经处理了闹钟同步
    await updateRule(updated);
  }

  Future<void> deleteRule(int id) async {
    await DatabaseService.delete(id);
    _rules.removeWhere((r) => r.id == id);
    // 删除系统闹钟
    AlarmService.cancelRule(id);
    _firstCheckDone = true;
    _checkAndApply();
    notifyListeners();
  }

  void startPeriodicCheck() {
    _checkTimer?.cancel();
    _checkTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      _checkAndApply();
    });
    // 定时器首次触发要等 30 秒，主动立即执行一次
    _checkAndApply();
  }

  void stopPeriodicCheck() {
    _checkTimer?.cancel();
    _checkTimer = null;
  }

  Future<void> _checkAndApply() async {
    final now = DateTime.now();
    ScheduleRule? activeRule;

    // 首次检查：仅记录当前状态，不触发断网
    if (!_firstCheckDone) {
      _firstCheckDone = true;
      // 但如果当前有规则正在阻断中（如已经由 AlarmReceiver 触发），保持阻断状态
      // 否则什么都不做
      return;
    }

    for (final rule in _rules) {
      if (rule.isActive(now)) {
        activeRule = rule;
        break;
      }
    }

    if (activeRule != null && !_isNetworkBlocked) {
      _isNetworkBlocked = true;
      _isStrictMode = activeRule.strictMode;
      _activeRule = activeRule.name;
      notifyListeners();
      // 监控模式下只需更新标记，VPN 服务保持运行
      await VpnService.updateBlockState(
        blockWifi: activeRule.blockWifi,
        blockMobile: activeRule.blockMobile,
        reason: activeRule.name,
        allowedApps: activeRule.allowedApps,
      );
      await NotificationService.showBlockStarted(activeRule.name);
    } else if (activeRule == null && _isNetworkBlocked) {
      _isNetworkBlocked = false;
      _isStrictMode = false;
      _activeRule = '';
      notifyListeners();
      // 恢复监控模式（不拦截流量，但保持前台服务保活）
      await VpnService.updateBlockState(
        blockWifi: false,
        blockMobile: false,
        reason: '后台监控',
      );
      await NotificationService.showBlockEnded(_activeRule);
    } else if (activeRule != null && _isNetworkBlocked && activeRule.name != _activeRule) {
      _activeRule = activeRule.name;
      _isStrictMode = activeRule.strictMode;
      notifyListeners();
    }
  }

  Future<bool?> manualToggle() async {
    if (_isNetworkBlocked) {
      await VpnService.updateBlockState(
        blockWifi: false,
        blockMobile: false,
        reason: '后台监控',
      );
      _isNetworkBlocked = false;
      _isStrictMode = false;
      _activeRule = '';
      notifyListeners();
      return false;
    } else {
      await VpnService.updateBlockState(
        blockWifi: true,
        blockMobile: true,
        reason: '手动断网',
      );
      _isNetworkBlocked = true;
      _activeRule = '手动';
      notifyListeners();
      return true;
    }
  }

  Future<void> exitStrictMode() async {
    _isStrictMode = false;
    if (_isNetworkBlocked) {
      _isNetworkBlocked = false;
      _activeRule = '';
      notifyListeners();
      await VpnService.updateBlockState(
        blockWifi: false,
        blockMobile: false,
        reason: '后台监控',
      );
      await NotificationService.showBlockEnded(_activeRule);
    } else {
      notifyListeners();
    }
  }

  /// 检查并更新 VPN 监控状态
  Future<void> checkMonitorStatus() async {
    _vpnMonitorActive = await VpnService.isVpnRunning();
    notifyListeners();
  }

  /// 总断网时长统计（今日累计）
  int _todayBlockedMinutes = 0;
  int get todayBlockedMinutes => _todayBlockedMinutes;

  void trackBlockTime() {
    // 简单跟踪：每次检查时如果被封锁则累加 0.5 分钟
    if (_isNetworkBlocked) {
      _todayBlockedMinutes += 1; // 每 30 秒加 1 分钟（粗略）
    }
  }

  ScheduleRule? get nextActiveRule {
    final now = DateTime.now();
    ScheduleRule? nearest;
    int nearestMinutes = 24 * 60;
    for (final rule in _rules.where((r) => r.enabled)) {
      if (rule.isActive(now)) return rule;
      final until = rule.timeUntilNextChange(now);
      final totalMin = until.inMinutes;
      if (totalMin < nearestMinutes) {
        nearestMinutes = totalMin;
        nearest = rule;
      }
    }
    return nearest;
  }

  @override
  void dispose() {
    _checkTimer?.cancel();
    super.dispose();
  }
}
