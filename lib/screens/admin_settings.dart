import 'package:flutter/material.dart';
import '../services/pin_service.dart';
import '../services/lock_task_service.dart';
import '../services/stats_service.dart';
import 'pin_screen.dart';
import 'reports_screen.dart';

/// 管理员设置页
class AdminSettingsScreen extends StatefulWidget {
  const AdminSettingsScreen({super.key});

  @override
  State<AdminSettingsScreen> createState() => _AdminSettingsScreenState();
}

class _AdminSettingsScreenState extends State<AdminSettingsScreen> {
  bool _hasPin = false;
  bool _isDeviceAdmin = false;

  @override
  void initState() {
    super.initState();
    _loadStatus();
  }

  Future<void> _loadStatus() async {
    final hasPin = await PinService.hasPin();
    final isAdmin = await LockTaskService.isDeviceAdmin();
    if (!mounted) return;
    setState(() {
      _hasPin = hasPin;
      _isDeviceAdmin = isAdmin;
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('管理员设置')),
      body: ListView(
        children: [
          const SizedBox(height: 8),

          // ── 安全设置 ──
          _SectionTitle('安全设置'),
          ListTile(
            leading: const CircleAvatar(child: Icon(Icons.lock_outline)),
            title: const Text('管理员 PIN'),
            subtitle: Text(_hasPin ? '已设置' : '未设置'),
            trailing: _hasPin
                ? Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      TextButton(
                        onPressed: () async {
                          final ok = await Navigator.push<bool>(
                            context,
                            MaterialPageRoute(
                              builder: (_) => const PinScreen(mode: PinMode.verify),
                            ),
                          );
                          if (ok == true && mounted) {
                            await PinService.clearPin();
                            _loadStatus();
                          }
                        },
                        child: const Text('清除', style: TextStyle(color: Colors.red)),
                      ),
                      TextButton(
                        onPressed: () async {
                          final ok = await Navigator.push<bool>(
                            context,
                            MaterialPageRoute(
                              builder: (_) => const PinScreen(mode: PinMode.change),
                            ),
                          );
                          if (ok == true && mounted) _loadStatus();
                        },
                        child: const Text('修改'),
                      ),
                    ],
                  )
                : TextButton(
                    onPressed: () async {
                      final ok = await Navigator.push<bool>(
                        context,
                        MaterialPageRoute(
                          builder: (_) => const PinScreen(mode: PinMode.set),
                        ),
                      );
                      if (ok == true && mounted) _loadStatus();
                    },
                    child: const Text('设置'),
                  ),
          ),
          const Divider(),

          // ── 设备管理员 ──
          ListTile(
            leading: const CircleAvatar(child: Icon(Icons.admin_panel_settings)),
            title: const Text('设备管理员'),
            subtitle: Text(_isDeviceAdmin ? '已授权' : '未授权（可选）'),
            trailing: _isDeviceAdmin
                ? const Chip(label: Text('已开启', style: TextStyle(fontSize: 11)))
                : TextButton(
                    onPressed: () async {
                      await LockTaskService.requestDeviceAdmin();
                      await Future.delayed(const Duration(seconds: 1));
                      _loadStatus();
                    },
                    child: const Text('授权'),
                  ),
            onTap: () {
              showDialog(
                context: context,
                builder: (_) => AlertDialog(
                  title: const Text('设备管理员有什么用？'),
                  content: const Text(
                    '授权后，屏幕固定（严格模式）更难退出——\n'
                    '退出时需要输入锁屏密码。\n\n'
                    '如无需此功能可跳过。',
                  ),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.pop(context),
                      child: const Text('知道了'),
                    ),
                  ],
                ),
              );
            },
          ),
          const Divider(),

          // ── 使用统计 ──
          _SectionTitle('监控'),
          ListTile(
            leading: const CircleAvatar(child: Icon(Icons.bar_chart)),
            title: const Text('屏幕使用统计'),
            subtitle: const Text('查看各 App 使用时长'),
            trailing: const Icon(Icons.chevron_right),
            onTap: () {
              Navigator.push(
                context,
                MaterialPageRoute(builder: (_) => const ReportsScreen()),
              );
            },
          ),
          const Divider(),

          // ── 关于 ──
          _SectionTitle('关于'),
          const ListTile(
            leading: CircleAvatar(child: Icon(Icons.info_outline)),
            title: Text('版本'),
            subtitle: Text('v1.0.0'),
          ),
        ],
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  final String title;
  const _SectionTitle(this.title);

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 4),
      child: Text(
        title,
        style: TextStyle(
          fontSize: 12,
          fontWeight: FontWeight.w600,
          color: Theme.of(context).colorScheme.primary,
          letterSpacing: 1,
        ),
      ),
    );
  }
}
