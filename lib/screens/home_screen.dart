import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:intl/intl.dart';
import '../providers/schedule_provider.dart';
import '../models/schedule_rule.dart';
import 'add_schedule_screen.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('定时断网助手'),
        centerTitle: true,
        actions: [
          // 手动开关（测试用）
          Consumer<ScheduleProvider>(
            builder: (_, provider, __) => IconButton(
              icon: Icon(
                provider.isNetworkBlocked ? Icons.wifi_off : Icons.wifi,
                color: provider.isNetworkBlocked ? Colors.orange : null,
              ),
              tooltip: provider.isNetworkBlocked ? '恢复网络' : '手动断网',
              onPressed: () async {
                final result = await provider.manualToggle();
                if (result == null) {
                  // VPN 需要授权，系统应该弹出了授权框
                  if (context.mounted) {
                    ScaffoldMessenger.of(context).showSnackBar(
                      const SnackBar(
                        content: Text('请在系统弹窗中允许 VPN 连接'),
                        duration: Duration(seconds: 3),
                      ),
                    );
                  }
                }
              },
            ),
          ),
        ],
      ),
      body: Consumer<ScheduleProvider>(
        builder: (context, provider, _) {
          return Column(
            children: [
              _StatusBanner(provider: provider),
              Expanded(
                child: provider.rules.isEmpty
                    ? _EmptyState()
                    : _RuleList(provider: provider),
              ),
            ],
          );
        },
      ),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _navigateToAdd(context),
        icon: const Icon(Icons.add),
        label: const Text('添加规则'),
      ),
    );
  }

  void _navigateToAdd(BuildContext context) {
    Navigator.push(
      context,
      MaterialPageRoute(builder: (_) => const AddScheduleScreen()),
    );
  }
}

/// 顶部状态横幅
class _StatusBanner extends StatelessWidget {
  final ScheduleProvider provider;
  const _StatusBanner({required this.provider});

  @override
  Widget build(BuildContext context) {
    final isBlocked = provider.isNetworkBlocked;
    final next = provider.nextActiveRule;
    final now = DateTime.now();

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 20, horizontal: 24),
      decoration: BoxDecoration(
        gradient: LinearGradient(
          colors: isBlocked
              ? [const Color(0xFFFF6B6B), const Color(0xFFFF8E53)]
              : [const Color(0xFF4ECDC4), const Color(0xFF44B09E)],
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
        ),
      ),
      child: Column(
        children: [
          Icon(
            isBlocked ? Icons.wifi_off_rounded : Icons.wifi_rounded,
            size: 48,
            color: Colors.white,
          ),
          const SizedBox(height: 8),
          Text(
            isBlocked ? '🚫 网络已封锁' : '✅ 网络正常',
            style: const TextStyle(
              fontSize: 22,
              fontWeight: FontWeight.bold,
              color: Colors.white,
            ),
          ),
          if (isBlocked) ...[
            const SizedBox(height: 4),
            Text(
              '规则：${provider.activeRuleName}',
              style: const TextStyle(fontSize: 14, color: Colors.white70),
            ),
          ],
          if (!isBlocked && next != null) ...[
            const SizedBox(height: 8),
            Text(
              '下次断网：${next.name}（${_formatDuration(next.timeUntilNextChange(now))}后）',
              style: const TextStyle(fontSize: 13, color: Colors.white70),
            ),
          ],
        ],
      ),
    );
  }

  String _formatDuration(Duration d) {
    final h = d.inHours;
    final m = d.inMinutes.remainder(60);
    if (h > 0) return '${h}小时${m}分钟';
    return '${m}分钟';
  }
}

/// 空状态
class _EmptyState extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Center(
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(Icons.schedule, size: 80, color: Colors.grey[300]),
          const SizedBox(height: 16),
          Text(
            '还没有定时断网规则',
            style: TextStyle(fontSize: 18, color: Colors.grey[500]),
          ),
          const SizedBox(height: 8),
          Text(
            '点击下方按钮添加你的第一条规则',
            style: TextStyle(fontSize: 14, color: Colors.grey[400]),
          ),
        ],
      ),
    );
  }
}

/// 规则列表
class _RuleList extends StatelessWidget {
  final ScheduleProvider provider;
  const _RuleList({required this.provider});

  @override
  Widget build(BuildContext context) {
    return ListView.builder(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 80),
      itemCount: provider.rules.length,
      itemBuilder: (context, index) {
        final rule = provider.rules[index];
        return _RuleCard(
          rule: rule,
          isActive: rule.isActive(DateTime.now()),
          provider: provider,
        );
      },
    );
  }
}

class _RuleCard extends StatelessWidget {
  final ScheduleRule rule;
  final bool isActive;
  final ScheduleProvider provider;

  const _RuleCard({
    required this.rule,
    required this.isActive,
    required this.provider,
  });

  @override
  Widget build(BuildContext context) {
    return Card(
      margin: const EdgeInsets.only(bottom: 12),
      elevation: isActive ? 3 : 1,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(12),
        side: isActive
            ? const BorderSide(color: Colors.orange, width: 2)
            : BorderSide.none,
      ),
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    rule.name,
                    style: TextStyle(
                      fontSize: 16,
                      fontWeight: FontWeight.w600,
                      color: isActive ? Colors.orange : null,
                    ),
                  ),
                ),
                Switch(
                  value: rule.enabled,
                  onChanged: (_) => provider.toggleEnabled(rule),
                ),
                PopupMenuButton<String>(
                  itemBuilder: (_) => [
                    const PopupMenuItem(value: 'edit', child: Text('编辑')),
                    const PopupMenuItem(value: 'delete', child: Text('删除', style: TextStyle(color: Colors.red))),
                  ],
                  onSelected: (value) {
                    if (value == 'edit') {
                      Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (_) => AddScheduleScreen(editRule: rule),
                        ),
                      );
                    } else if (value == 'delete') {
                      provider.deleteRule(rule.id!);
                    }
                  },
                ),
              ],
            ),
            const SizedBox(height: 8),
            Row(
              children: [
                Icon(Icons.access_time, size: 16, color: Colors.grey[500]),
                const SizedBox(width: 4),
                Text(
                  rule.timeRangeText,
                  style: TextStyle(fontSize: 14, color: Colors.grey[700]),
                ),
                if (isActive) ...[
                  const SizedBox(width: 8),
                  const Chip(
                    label: Text('进行中', style: TextStyle(fontSize: 11)),
                    backgroundColor: Colors.orangeAccent,
                    padding: EdgeInsets.zero,
                    materialTapTargetSize: MaterialTapTargetSize.shrinkWrap,
                  ),
                ],
              ],
            ),
            if (rule.repeatDays.isNotEmpty && !rule.repeatDaily) ...[
              const SizedBox(height: 4),
              Row(
                children: [
                  Icon(Icons.date_range, size: 16, color: Colors.grey[500]),
                  const SizedBox(width: 4),
                  Text(
                    _weekdayText(rule.repeatDays),
                    style: TextStyle(fontSize: 12, color: Colors.grey[500]),
                  ),
                ],
              ),
            ],
            const SizedBox(height: 4),
            Row(
              children: [
                if (rule.blockWifi)
                  _Tag(label: 'WiFi', color: Colors.blue),
                if (rule.blockMobile)
                  _Tag(label: '移动网络', color: Colors.green),
                if (rule.strictMode)
                  _Tag(label: '严格模式', color: Colors.red),
              ],
            ),
          ],
        ),
      ),
    );
  }

  String _weekdayText(List<int> days) {
    const names = ['周一', '周二', '周三', '周四', '周五', '周六', '周日'];
    if (days.length == 7) return '每天';
    if (days.length == 5 && days.every((d) => d < 5)) return '工作日';
    if (days.length == 2 && days.contains(5) && days.contains(6)) return '周末';
    return days.map((d) => names[d]).join('、');
  }
}

class _Tag extends StatelessWidget {
  final String label;
  final Color color;
  const _Tag({required this.label, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(right: 6),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
      decoration: BoxDecoration(
        color: color.withOpacity(0.12),
        borderRadius: BorderRadius.circular(4),
        border: Border.all(color: color.withOpacity(0.3)),
      ),
      child: Text(label, style: TextStyle(fontSize: 11, color: color)),
    );
  }
}
