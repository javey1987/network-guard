import 'dart:async';
import 'package:flutter/material.dart';
import '../models/schedule_rule.dart';
import '../services/database_service.dart';
import '../services/vpn_service.dart';
import '../services/notification_service.dart';

class ScheduleProvider extends ChangeNotifier {
  List<ScheduleRule> _rules = [];
  bool _isNetworkBlocked = false;
  String _activeRule = '';
  Timer? _checkTimer;

  List<ScheduleRule> get rules => List.unmodifiable(_rules);
  bool get isNetworkBlocked => _isNetworkBlocked;
  String get activeRuleName => _activeRule;

  /// 加载所有规则
  Future<void> loadRules() async {
    _rules = await DatabaseService.getAll();
    _checkAndApply();
    notifyListeners();
  }

  /// 添加规则
  Future<void> addRule(ScheduleRule rule) async {
    final id = await DatabaseService.insert(rule);
    _rules.add(rule.copyWith(id: id));
    _rules.sort((a, b) => a.startMinutes.compareTo(b.startMinutes));
    _checkAndApply();
    notifyListeners();
  }

  /// 更新规则
  Future<void> updateRule(ScheduleRule rule) async {
    await DatabaseService.update(rule);
    final idx = _rules.indexWhere((r) => r.id == rule.id);
    if (idx >= 0) _rules[idx] = rule;
    _checkAndApply();
    notifyListeners();
  }

  /// 切换启用状态
  Future<void> toggleEnabled(ScheduleRule rule) async {
    final updated = rule.copyWith(enabled: !rule.enabled);
    await updateRule(updated);
  }

  /// 删除规则
  Future<void> deleteRule(int id) async {
    await DatabaseService.delete(id);
    _rules.removeWhere((r) => r.id == id);
    _checkAndApply();
    notifyListeners();
  }

  /// 启动定时检查（每秒一次）
  void startPeriodicCheck() {
    _checkTimer?.cancel();
    _checkTimer = Timer.periodic(const Duration(seconds: 30), (_) {
      _checkAndApply();
    });
  }

  /// 停止定时检查
  void stopPeriodicCheck() {
    _checkTimer?.cancel();
    _checkTimer = null;
  }

  /// 检查并应用规则
  Future<void> _checkAndApply() async {
    final now = DateTime.now();

    // 如果显示已封锁，先检查 VPN 是否真的在运行
    if (_isNetworkBlocked) {
      final stillRunning = await VpnService.isVpnRunning();
      if (!stillRunning) {
        _isNetworkBlocked = false;
        _activeRule = '';
        notifyListeners();
      }
    }

    // 找当前激活的规则
    ScheduleRule? activeRule;
    for (final rule in _rules) {
      if (rule.isActive(now)) {
        activeRule = rule;
        break;
      }
    }

    if (activeRule != null && !_isNetworkBlocked) {
      // 需要封锁 — 启动 VPN（启动后自动拦截所有流量）
      await VpnService.startVpn(
        blockWifi: activeRule.blockWifi,
        blockMobile: activeRule.blockMobile,
        reason: activeRule.name,
      );
      _isNetworkBlocked = true;
      _activeRule = activeRule.name;
      notifyListeners();
      await NotificationService.showBlockStarted(activeRule.name);
    } else if (activeRule == null && _isNetworkBlocked) {
      // 需要解锁 — 关闭 VPN 即可恢复网络
      await VpnService.stopVpn();
      _isNetworkBlocked = false;
      _activeRule = '';
      notifyListeners();
      await NotificationService.showBlockEnded(_activeRule);
    }
  }

  /// 手动切换封锁状态（测试用）
  Future<void> manualToggle() async {
    if (_isNetworkBlocked) {
      await VpnService.stopVpn();
      _isNetworkBlocked = false;
      _activeRule = '';
    } else {
      await VpnService.startVpn(
        blockWifi: true,
        blockMobile: true,
        reason: '手动断网',
      );
      _isNetworkBlocked = true;
      _activeRule = '手动';
    }
    notifyListeners();
  }

  /// 获取下一个活跃规则
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
