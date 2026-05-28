import 'dart:math';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/stats_service.dart';

/// 使用统计报告页
class ReportsScreen extends StatefulWidget {
  const ReportsScreen({super.key});

  @override
  State<ReportsScreen> createState() => _ReportsScreenState();
}

class _ReportsScreenState extends State<ReportsScreen>
    with SingleTickerProviderStateMixin {
  late TabController _tabCtrl;
  List<Map<String, dynamic>> _todayStats = [];
  List<Map<String, dynamic>> _weekStats = [];
  bool _loading = true;
  bool _hasPermission = true;
  int _totalTodayMinutes = 0;

  @override
  void initState() {
    super.initState();
    _tabCtrl = TabController(length: 2, vsync: this);
    _loadData();
  }

  @override
  void dispose() {
    _tabCtrl.dispose();
    super.dispose();
  }

  Future<void> _loadData() async {
    setState(() => _loading = true);

    final hasPerm = await StatsService.hasUsageStatsPermission();
    if (!hasPerm) {
      if (!mounted) return;
      setState(() {
        _loading = false;
        _hasPermission = false;
      });
      return;
    }

    final now = DateTime.now();
    final dayStart = DateTime(now.year, now.month, now.day).millisecondsSinceEpoch;
    final weekStart = now.subtract(Duration(days: now.weekday - 1));
    final weekStartMs = DateTime(weekStart.year, weekStart.month, weekStart.day).millisecondsSinceEpoch;
    final nowMs = now.millisecondsSinceEpoch;

    final today = await StatsService.queryUsageStats(startTimeMs: dayStart, endTimeMs: nowMs);
    final week = await StatsService.queryUsageStats(startTimeMs: weekStartMs, endTimeMs: nowMs);

    if (!mounted) return;
    setState(() {
      _todayStats = today;
      _weekStats = week;
      _totalTodayMinutes = today.fold<int>(0, (s, a) =>
          s + ((a['totalTimeInForeground'] as int? ?? 0) / 60000).round());
      _loading = false;
    });
  }

  String _fmtMin(int min) {
    if (min < 60) return '${min}分钟';
    return '${min ~/ 60}小时${min.remainder(60)}分钟';
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('使用统计'),
        bottom: TabBar(
          controller: _tabCtrl,
          tabs: const [
            Tab(text: '今日'),
            Tab(text: '本周'),
          ],
        ),
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _loadData,
          ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : !_hasPermission
              ? _NoPermissionView(onRequest: () async {
                  await StatsService.requestUsageStatsPermission();
                  // 用户回来后再检查
                  await Future.delayed(const Duration(seconds: 2));
                  _loadData();
                })
              : TabBarView(
                  controller: _tabCtrl,
                  children: [
                    _StatsList(stats: _todayStats, totalMinutes: _totalTodayMinutes),
                    _StatsList(stats: _weekStats, totalMinutes:
                        _weekStats.fold<int>(0, (s, a) =>
                            s + ((a['totalTimeInForeground'] as int? ?? 0) / 60000).round())),
                  ],
                ),
    );
  }
}

class _NoPermissionView extends StatelessWidget {
  final VoidCallback onRequest;
  const _NoPermissionView({required this.onRequest});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.info_outline, size: 64, color: Colors.grey[400]),
            const SizedBox(height: 16),
            const Text('需要「使用情况访问权限」'),
            const SizedBox(height: 8),
            Text(
              '请前往系统设置 → 安全 → 使用情况访问\n开启「定时断网助手」的权限',
              textAlign: TextAlign.center,
              style: TextStyle(color: Colors.grey[600], fontSize: 13),
            ),
            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: onRequest,
              icon: const Icon(Icons.settings),
              label: const Text('前往设置'),
            ),
          ],
        ),
      ),
    );
  }
}

class _StatsList extends StatelessWidget {
  final List<Map<String, dynamic>> stats;
  final int totalMinutes;
  const _StatsList({required this.stats, required this.totalMinutes});

  @override
  Widget build(BuildContext context) {
    if (stats.isEmpty) {
      return Center(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.bar_chart, size: 64, color: Colors.grey[300]),
            const SizedBox(height: 16),
            Text('暂无数据', style: TextStyle(color: Colors.grey[500])),
          ],
        ),
      );
    }

    final maxTime = stats.isNotEmpty
        ? (stats.first['totalTimeInForeground'] as int? ?? 1).toDouble()
        : 1.0;

    return Column(
      children: [
        // 总览卡片
        Container(
          width: double.infinity,
          margin: const EdgeInsets.all(16),
          padding: const EdgeInsets.symmetric(vertical: 20, horizontal: 24),
          decoration: BoxDecoration(
            gradient: const LinearGradient(
              colors: [Color(0xFF4ECDC4), Color(0xFF44B09E)],
            ),
            borderRadius: BorderRadius.circular(16),
          ),
          child: Column(
            children: [
              const Text('屏幕使用时间', style: TextStyle(color: Colors.white70, fontSize: 13)),
              const SizedBox(height: 4),
              Text(
                '${totalMinutes ~/ 60}小时${totalMinutes.remainder(60)}分钟',
                style: const TextStyle(
                  color: Colors.white, fontSize: 32, fontWeight: FontWeight.bold,
                ),
              ),
            ],
          ),
        ),

        // Top 应用列表
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16),
          child: Row(
            children: [
              Text('应用使用排行',
                  style: TextStyle(fontSize: 14, fontWeight: FontWeight.w600, color: Colors.grey[700])),
              const Spacer(),
              Text('${stats.length}个应用',
                  style: TextStyle(fontSize: 12, color: Colors.grey[500])),
            ],
          ),
        ),
        const SizedBox(height: 8),

        Expanded(
          child: ListView.separated(
            padding: const EdgeInsets.symmetric(horizontal: 16),
            itemCount: stats.length,
            separatorBuilder: (_, __) => const Divider(height: 1),
            itemBuilder: (_, i) {
              final app = stats[i];
              final name = app['appName'] as String? ?? '未知';
              final minutes = ((app['totalTimeInForeground'] as int? ?? 0) / 60000).round();
              final ratio = maxTime > 0
                  ? (app['totalTimeInForeground'] as int? ?? 0) / maxTime
                  : 0.0;

              Color barColor;
              if (i == 0) barColor = const Color(0xFFFF6B6B);
              else if (i == 1) barColor = const Color(0xFFFF8E53);
              else if (i == 2) barColor = const Color(0xFFFFB347);
              else barColor = Colors.grey[400]!;

              return Padding(
                padding: const EdgeInsets.symmetric(vertical: 8),
                child: Row(
                  children: [
                    SizedBox(
                      width: 20,
                      child: Text('${i + 1}',
                          style: TextStyle(fontSize: 11, color: Colors.grey[500], fontWeight: FontWeight.bold)),
                    ),
                    const SizedBox(width: 8),
                    CircleAvatar(
                      radius: 14,
                      backgroundColor: barColor.withOpacity(0.15),
                      child: Text(name[0].toUpperCase(),
                          style: TextStyle(fontSize: 12, color: barColor, fontWeight: FontWeight.bold)),
                    ),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(name, style: const TextStyle(fontSize: 14)),
                          const SizedBox(height: 4),
                          ClipRRect(
                            borderRadius: BorderRadius.circular(2),
                            child: LinearProgressIndicator(
                              value: ratio.clamp(0.0, 1.0),
                              backgroundColor: Colors.grey[200],
                              valueColor: AlwaysStoppedAnimation<Color>(barColor),
                              minHeight: 4,
                            ),
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(width: 12),
                    Text(
                      '${minutes}分钟',
                      style: TextStyle(fontSize: 13, color: Colors.grey[600]),
                    ),
                  ],
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}
