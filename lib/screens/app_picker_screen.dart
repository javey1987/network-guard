import 'package:flutter/material.dart';
import '../services/stats_service.dart';

/// 应用选择器 — 家长选择允许在上网期间使用的 App
class AppPickerScreen extends StatefulWidget {
  final List<String> selectedPackages;
  const AppPickerScreen({super.key, this.selectedPackages = const []});

  @override
  State<AppPickerScreen> createState() => _AppPickerScreenState();
}

class _AppPickerScreenState extends State<AppPickerScreen> {
  List<Map<String, dynamic>> _apps = [];
  Set<String> _selected = {};
  bool _loading = true;
  String _search = '';

  @override
  void initState() {
    super.initState();
    _selected = widget.selectedPackages.toSet();
    _loadApps();
  }

  Future<void> _loadApps() async {
    final apps = await StatsService.getInstalledApps();
    if (!mounted) return;
    setState(() {
      _apps = apps;
      _loading = false;
    });
  }

  List<Map<String, dynamic>> get _filteredApps {
    if (_search.isEmpty) return _apps;
    final q = _search.toLowerCase();
    return _apps.where((a) {
      final name = (a['appName'] as String?)?.toLowerCase() ?? '';
      final pkg = (a['packageName'] as String?)?.toLowerCase() ?? '';
      return name.contains(q) || pkg.contains(q);
    }).toList();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('选择允许的应用'),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.of(context).pop(_selected.toList());
            },
            child: Text('完成 (${_selected.length})'),
          ),
        ],
      ),
      body: Column(
        children: [
          // 搜索框
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 8, 16, 8),
            child: TextField(
              decoration: InputDecoration(
                hintText: '搜索应用…',
                prefixIcon: const Icon(Icons.search),
                border: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(12),
                ),
                contentPadding: const EdgeInsets.symmetric(vertical: 0),
                filled: true,
                fillColor: Colors.grey[50],
              ),
              onChanged: (v) => setState(() => _search = v),
            ),
          ),

          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : _filteredApps.isEmpty
                    ? Center(
                        child: Text(
                          _search.isEmpty ? '未获取到应用列表' : '无匹配结果',
                          style: TextStyle(color: Colors.grey[500]),
                        ),
                      )
                    : ListView.builder(
                        itemCount: _filteredApps.length,
                        itemBuilder: (_, i) {
                          final app = _filteredApps[i];
                          final pkg = app['packageName'] as String? ?? '';
                          final name = app['appName'] as String? ?? pkg;
                          final selected = _selected.contains(pkg);

                          return CheckboxListTile(
                            title: Text(name),
                            subtitle: Text(pkg, style: TextStyle(fontSize: 11, color: Colors.grey[500])),
                            value: selected,
                            secondary: CircleAvatar(
                              backgroundColor: Colors.grey[200],
                              child: Text(
                                name.isNotEmpty ? name[0].toUpperCase() : '?',
                                style: TextStyle(
                                  color: selected ? Theme.of(context).colorScheme.primary : Colors.grey[600],
                                  fontWeight: FontWeight.bold,
                                ),
                              ),
                            ),
                            onChanged: (v) {
                              setState(() {
                                if (v == true) {
                                  _selected.add(pkg);
                                } else {
                                  _selected.remove(pkg);
                                }
                              });
                            },
                          );
                        },
                      ),
          ),
        ],
      ),
    );
  }
}
