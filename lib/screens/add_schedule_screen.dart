import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../models/schedule_rule.dart';
import '../providers/schedule_provider.dart';
import '../services/pin_service.dart';
import 'pin_screen.dart';
import 'app_picker_screen.dart';

class AddScheduleScreen extends StatefulWidget {
  final ScheduleRule? editRule;
  const AddScheduleScreen({super.key, this.editRule});

  @override
  State<AddScheduleScreen> createState() => _AddScheduleScreenState();
}

class _AddScheduleScreenState extends State<AddScheduleScreen> {
  final _formKey = GlobalKey<FormState>();
  late String _name;
  late TimeOfDay _startTime;
  late TimeOfDay _endTime;
  late bool _repeatDaily;
  late Set<int> _repeatDays;
  late bool _blockWifi;
  late bool _blockMobile;
  late bool _strictMode;
  late bool _enabled;
  late List<String> _allowedApps;

  bool get _isEditing => widget.editRule != null;

  @override
  void initState() {
    super.initState();
    final r = widget.editRule;
    _name = r?.name ?? '';
    _startTime = TimeOfDay(hour: r?.startHour ?? 22, minute: r?.startMinute ?? 0);
    _endTime = TimeOfDay(hour: r?.endHour ?? 7, minute: r?.endMinute ?? 0);
    _repeatDaily = r?.repeatDaily ?? true;
    _repeatDays = r?.repeatDays.toSet() ?? {};
    _blockWifi = r?.blockWifi ?? true;
    _blockMobile = r?.blockMobile ?? true;
    _strictMode = r?.strictMode ?? false;
    _enabled = r?.enabled ?? true;
    _allowedApps = r?.allowedApps.toList() ?? [];
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(_isEditing ? '编辑规则' : '添加规则'),
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(20),
        child: Form(
          key: _formKey,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // 规则名称
              TextFormField(
                initialValue: _name,
                decoration: const InputDecoration(
                  labelText: '规则名称',
                  hintText: '如：晚间静修、工作专注',
                  border: OutlineInputBorder(),
                  prefixIcon: Icon(Icons.label_outline),
                ),
                validator: (v) => (v == null || v.trim().isEmpty) ? '请输入名称' : null,
                onSaved: (v) => _name = v!.trim(),
              ),
              const SizedBox(height: 24),

              // 时间段选择
              const Text('时间段', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
              const SizedBox(height: 12),
              Row(
                children: [
                  Expanded(
                    child: _TimePickerTile(
                      label: '开始时间',
                      time: _startTime,
                      icon: Icons.play_arrow,
                      onTap: () => _pickTime(true),
                    ),
                  ),
                  const Padding(
                    padding: EdgeInsets.symmetric(horizontal: 8),
                    child: Icon(Icons.arrow_forward, color: Colors.grey),
                  ),
                  Expanded(
                    child: _TimePickerTile(
                      label: '结束时间',
                      time: _endTime,
                      icon: Icons.stop,
                      onTap: () => _pickTime(false),
                    ),
                  ),
                ],
              ),
              const SizedBox(height: 8),
              if (_startTime.hour * 60 + _startTime.minute >=
                  _endTime.hour * 60 + _endTime.minute)
                const Text(
                  '⚠ 跨天设置：将在次日结束时间恢复网络',
                  style: TextStyle(color: Colors.orange, fontSize: 12),
                ),
              const SizedBox(height: 24),

              // 重复方式
              const Text('重复方式', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              SwitchListTile(
                title: const Text('每天重复'),
                subtitle: Text(_repeatDaily ? '每天固定时段生效' : '选择特定星期'),
                value: _repeatDaily,
                onChanged: (v) => setState(() => _repeatDaily = v),
                contentPadding: EdgeInsets.zero,
              ),
              if (!_repeatDaily) ...[
                const SizedBox(height: 4),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: List.generate(7, (i) {
                    final days = ['一', '二', '三', '四', '五', '六', '日'];
                    final selected = _repeatDays.contains(i);
                    return FilterChip(
                      label: Text(days[i]),
                      selected: selected,
                      onSelected: (v) {
                        setState(() {
                          v ? _repeatDays.add(i) : _repeatDays.remove(i);
                        });
                      },
                    );
                  }),
                ),
              ],
              const SizedBox(height: 24),

              // 封锁类型
              const Text('封锁类型', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
              const SizedBox(height: 8),
              CheckboxListTile(
                title: const Text('WiFi'),
                subtitle: const Text('关闭无线网络连接'),
                value: _blockWifi,
                onChanged: (v) => setState(() => _blockWifi = v ?? true),
                contentPadding: EdgeInsets.zero,
              ),
              CheckboxListTile(
                title: const Text('移动网络'),
                subtitle: const Text('关闭蜂窝数据连接'),
                value: _blockMobile,
                onChanged: (v) => setState(() => _blockMobile = v ?? true),
                contentPadding: EdgeInsets.zero,
              ),
              if (!_blockWifi && !_blockMobile)
                const Padding(
                  padding: EdgeInsets.only(left: 16),
                  child: Text('至少选择一种网络类型', style: TextStyle(color: Colors.red, fontSize: 12)),
                ),
              const SizedBox(height: 16),

              // ★ 应用白名单（家长版）
              const Text('白名单', style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
              const SizedBox(height: 4),
              const Text(
                '断网期间仍可使用的应用',
                style: TextStyle(fontSize: 12, color: Colors.grey),
              ),
              const SizedBox(height: 8),
              InkWell(
                onTap: () async {
                  final result = await Navigator.push<List<String>>(
                    context,
                    MaterialPageRoute(
                      builder: (_) => AppPickerScreen(selectedPackages: _allowedApps),
                    ),
                  );
                  if (result != null) {
                    setState(() => _allowedApps = result);
                  }
                },
                borderRadius: BorderRadius.circular(12),
                child: Container(
                  width: double.infinity,
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: Colors.grey[50],
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(color: Colors.grey[300]!),
                  ),
                  child: Row(
                    children: [
                      const Icon(Icons.phone_android, color: Colors.grey),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(
                              _allowedApps.isEmpty
                                  ? '未选择（全部封锁）'
                                  : '已选择 ${_allowedApps.length} 个应用',
                              style: TextStyle(
                                fontSize: 14,
                                color: _allowedApps.isEmpty ? Colors.grey[500] : null,
                              ),
                            ),
                            if (_allowedApps.isNotEmpty) ...[
                              const SizedBox(height: 4),
                              Text(
                                _allowedApps.take(3).join(', '),
                                overflow: TextOverflow.ellipsis,
                                style: TextStyle(fontSize: 11, color: Colors.grey[500]),
                              ),
                            ],
                          ],
                        ),
                      ),
                      const Icon(Icons.chevron_right, color: Colors.grey),
                    ],
                  ),
                ),
              ),
              const SizedBox(height: 24),

              // 严格模式
              SwitchListTile(
                title: const Text('严格模式'),
                subtitle: const Text(
                  '开启后无法手动恢复网络（需通过验证或等待定时结束）',
                ),
                value: _strictMode,
                onChanged: (v) => setState(() => _strictMode = v),
                contentPadding: EdgeInsets.zero,
              ),
              const SizedBox(height: 32),

              // 提交按钮
              SizedBox(
                width: double.infinity,
                height: 50,
                child: ElevatedButton.icon(
                  onPressed: _submit,
                  icon: Icon(_isEditing ? Icons.save : Icons.add),
                  label: Text(_isEditing ? '保存修改' : '创建规则'),
                  style: ElevatedButton.styleFrom(
                    backgroundColor: Theme.of(context).colorScheme.primary,
                    foregroundColor: Colors.white,
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Future<void> _pickTime(bool isStart) async {
    final initial = isStart ? _startTime : _endTime;
    final picked = await showTimePicker(
      context: context,
      initialTime: initial,
      builder: (context, child) => MediaQuery(
        data: MediaQuery.of(context).copyWith(alwaysUse24HourFormat: true),
        child: child!,
      ),
    );
    if (picked != null) {
      setState(() {
        if (isStart) {
          _startTime = picked;
        } else {
          _endTime = picked;
        }
      });
    }
  }

  Future<void> _submit() async {
    // 如果已设置 PIN，提交前验证
    if (await PinService.hasPin()) {
      if (!mounted) return;
      final ok = await Navigator.push<bool>(
        context,
        MaterialPageRoute(builder: (_) => const PinScreen()),
      );
      if (ok != true) return;
    }

    if (!_formKey.currentState!.validate()) return;
    _formKey.currentState!.save();

    if (!_blockWifi && !_blockMobile) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('至少选择一种网络类型')),
      );
      return;
    }
    if (!_repeatDaily && _repeatDays.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(content: Text('请选择重复的星期')),
      );
      return;
    }

    final rule = ScheduleRule(
      id: widget.editRule?.id,
      name: _name,
      startHour: _startTime.hour,
      startMinute: _startTime.minute,
      endHour: _endTime.hour,
      endMinute: _endTime.minute,
      enabled: _enabled,
      repeatDaily: _repeatDaily,
      repeatDays: _repeatDays.toList(),
      blockWifi: _blockWifi,
      blockMobile: _blockMobile,
      strictMode: _strictMode,
      allowedApps: _allowedApps,
    );

    final provider = context.read<ScheduleProvider>();
    if (_isEditing) {
      provider.updateRule(rule);
    } else {
      provider.addRule(rule);
    }

    if (mounted) Navigator.pop(context);
  }
}

class _TimePickerTile extends StatelessWidget {
  final String label;
  final TimeOfDay time;
  final IconData icon;
  final VoidCallback onTap;

  const _TimePickerTile({
    required this.label,
    required this.time,
    required this.icon,
    required this.onTap,
  });

  @override
  Widget build(BuildContext context) {
    final pad = (int n) => n.toString().padLeft(2, '0');
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(12),
      child: Container(
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: Colors.grey[50],
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: Colors.grey[300]!),
        ),
        child: Column(
          children: [
            Text(label, style: const TextStyle(fontSize: 12, color: Colors.grey)),
            const SizedBox(height: 8),
            Icon(icon, size: 20, color: Theme.of(context).colorScheme.primary),
            const SizedBox(height: 8),
            Text(
              '${pad(time.hour)}:${pad(time.minute)}',
              style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold),
            ),
          ],
        ),
      ),
    );
  }
}
