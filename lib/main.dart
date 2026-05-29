import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import 'providers/schedule_provider.dart';
import 'services/vpn_service.dart';
import 'services/notification_service.dart';
import 'services/activation_service.dart';
import 'screens/home_screen.dart';
import 'screens/activation_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  // 初始化通知
  await NotificationService.init();

  // 初始化 VPN 原生回调监听
  VpnService.init();

  // 强制竖屏
  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
  ]);

  runApp(const NetworkGuardApp());
}

class NetworkGuardApp extends StatelessWidget {
  const NetworkGuardApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider(
      create: (_) => ScheduleProvider()..loadRules(),
      child: MaterialApp(
        title: '定时断网助手',
        debugShowCheckedModeBanner: false,
        theme: _buildTheme(),
        home: const AppShell(),
      ),
    );
  }

  ThemeData _buildTheme() {
    const primaryColor = Color(0xFF4ECDC4);
    final colorScheme = ColorScheme.fromSeed(
      seedColor: primaryColor,
      brightness: Brightness.light,
    );

    return ThemeData(
      useMaterial3: true,
      colorScheme: colorScheme,
      appBarTheme: AppBarTheme(
        backgroundColor: colorScheme.surface,
        foregroundColor: colorScheme.onSurface,
        elevation: 0,
        centerTitle: true,
      ),
      floatingActionButtonTheme: FloatingActionButtonThemeData(
        backgroundColor: colorScheme.primary,
        foregroundColor: colorScheme.onPrimary,
        elevation: 4,
      ),
      cardTheme: CardThemeData(
        elevation: 1,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(12),
        ),
      ),
    );
  }
}

/// 应用外壳：负责管理定时检查的生命周期
class AppShell extends StatefulWidget {
  const AppShell({super.key});

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> with WidgetsBindingObserver {
  bool _activated = false;
  bool _checking = true;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _checkActivation();
  }

  Future<void> _checkActivation() async {
    final ok = await ActivationService.isActivated();
    if (!mounted) return;
    setState(() {
      _activated = ok;
      _checking = false;
    });
    if (ok) {
      context.read<ScheduleProvider>().startPeriodicCheck();
      context.read<ScheduleProvider>().checkMonitorStatus();
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    final provider = context.read<ScheduleProvider>();
    if (state == AppLifecycleState.resumed) {
      provider.startPeriodicCheck();
      provider.checkMonitorStatus();
    } else if (state == AppLifecycleState.paused) {
      provider.stopPeriodicCheck();
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_checking) {
      return const Scaffold(
        body: Center(
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              CircularProgressIndicator(),
              SizedBox(height: 16),
              Text('正在验证...'),
            ],
          ),
        ),
      );
    }
    if (!_activated) {
      return ActivationScreen(
        onActivated: () async {
          // 激活后启动后台监控 VPN（不拦截流量，仅保活）
          await VpnService.startMonitor();
          setState(() => _activated = true);
          final provider = context.read<ScheduleProvider>();
          provider.startPeriodicCheck();
          // 延迟检查监控状态（等待 VPN 初始化）
          Future.delayed(const Duration(seconds: 3), () {
            provider.checkMonitorStatus();
          });
        },
      );
    }
    return const HomeScreen();
  }
}

/// 确保监控 VPN 正在运行
Future<bool> ensureMonitorRunning() async {
  try {
    final running = await VpnService.isVpnRunning();
    if (!running) {
      return await VpnService.startMonitor();
    }
    return true;
  } catch (_) {
    return false;
  }
}
