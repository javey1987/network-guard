import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import 'providers/schedule_provider.dart';
import 'services/vpn_service.dart';
import 'services/notification_service.dart';
import 'screens/home_screen.dart';
import 'screens/focus_screen.dart';

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

/// 应用外壳：负责管理定时检查的生命周期 + 严格模式导航
class AppShell extends StatefulWidget {
  const AppShell({super.key});

  @override
  State<AppShell> createState() => _AppShellState();
}

class _AppShellState extends State<AppShell> with WidgetsBindingObserver {
  bool _focusScreenShown = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
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
    } else if (state == AppLifecycleState.paused) {
      provider.stopPeriodicCheck();
    }
  }

  /// 监听严格模式状态变化：激活时自动跳转专注屏
  void _checkStrictMode(ScheduleProvider provider) {
    if (provider.isStrictMode && !_focusScreenShown && mounted) {
      _focusScreenShown = true;
      Navigator.of(context).push(
        MaterialPageRoute(
          builder: (_) => const FocusScreen(),
          fullscreenDialog: true,
        ),
      ).then((_) {
        // 从专注屏返回后重置状态
        _focusScreenShown = false;
      });
    }
  }

  @override
  Widget build(BuildContext context) {
    // 严格模式监听：Consumer 会在 provider 变化时自动触发
    return Consumer<ScheduleProvider>(
      builder: (context, provider, _) {
        // 触发严格模式检查
        WidgetsBinding.instance.addPostFrameCallback((_) {
          _checkStrictMode(provider);
        });

        // 页面启动时开始定时检查
        WidgetsBinding.instance.addPostFrameCallback((_) {
          provider.startPeriodicCheck();
        });

        return const HomeScreen();
      },
    );
  }
}
