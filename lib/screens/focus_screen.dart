import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../providers/schedule_provider.dart';
import '../services/lock_task_service.dart';

/// 严格模式下的全屏专注界面。
///
/// 当用户开启严格模式后，此界面覆盖整个 App：
/// - 显示倒计时
/// - 提供「冷静退出」按钮（需长按或等待）
/// - 自动调用屏幕固定锁定 App
class FocusScreen extends StatefulWidget {
  const FocusScreen({super.key});

  @override
  State<FocusScreen> createState() => _FocusScreenState();
}

class _FocusScreenState extends State<FocusScreen>
    with TickerProviderStateMixin {
  Timer? _countdownTimer;
  Duration _timeRemaining = Duration.zero;
  bool _autoExiting = false;
  bool _showExitChallenge = false;
  int _exitCountdown = 0;
  Timer? _exitTimer;

  late AnimationController _pulseAnim;
  late Animation<double> _pulseCurve;

  @override
  void initState() {
    super.initState();
    _pulseAnim = AnimationController(
      vsync: this,
      duration: const Duration(seconds: 2),
    )..repeat(reverse: true);
    _pulseCurve = CurvedAnimation(parent: _pulseAnim, curve: Curves.easeInOut);

    _startCountdown();
    _tryLockScreen();
  }

  @override
  void dispose() {
    _countdownTimer?.cancel();
    _exitTimer?.cancel();
    _pulseAnim.dispose();
    super.dispose();
  }

  /// 定时刷新倒计时 + 检测规则失效自动退出
  void _startCountdown() {
    _updateRemaining();
    _countdownTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      _updateRemaining();
    });
  }

  void _updateRemaining() {
    final provider = context.read<ScheduleProvider>();

    // ★ 关键：检测严格模式是否已结束（规则失效 / 用户从外部解除了）
    if (!provider.isNetworkBlocked || !provider.isStrictMode) {
      _autoExit();
      return;
    }

    final now = DateTime.now();
    // 找当前激活的规则
    bool hasActiveRule = false;
    for (final rule in provider.rules) {
      if (rule.isActive(now)) {
        hasActiveRule = true;
        _timeRemaining = rule.timeUntilNextChange(now);
        break;
      }
    }

    // 没有活跃规则但网络被封锁 → 手动断网模式，计算锁定的剩余时间（最多3小时）
    if (!hasActiveRule) {
      // 规则已失效但网络仍被封锁 → 自动退出
      _autoExit();
      return;
    }

    if (mounted) setState(() {});
  }

  /// 规则结束或网络恢复时，自动退出专注屏
  Future<void> _autoExit() async {
    if (_autoExiting) return;
    _autoExiting = true;

    // 停止所有计时器
    _countdownTimer?.cancel();
    _exitTimer?.cancel();

    // 先确保 VPN 停止 + 严格模式关闭
    final provider = context.read<ScheduleProvider>();
    if (provider.isNetworkBlocked || provider.isStrictMode) {
      await provider.exitStrictMode();
    }

    // 解锁屏幕固定
    await Future.delayed(const Duration(milliseconds: 300));
    await LockTaskService.unlock();

    if (mounted) {
      Navigator.of(context).pop();
    }
  }

  /// 尝试锁定屏幕
  Future<void> _tryLockScreen() async {
    await LockTaskService.lock();
  }

  /// 用户点击「我要退出」
  void _onRequestExit() {
    setState(() {
      _showExitChallenge = true;
      _exitCountdown = 30;
    });

    _exitTimer?.cancel();
    _exitTimer = Timer.periodic(const Duration(seconds: 1), (timer) {
      setState(() {
        _exitCountdown--;
      });
      if (_exitCountdown <= 0) {
        timer.cancel();
        _exitFocus();
      }
    });
  }

  /// 用户通过冷静期后，主动退出严格模式
  Future<void> _exitFocus() async {
    await _autoExit();
  }

  @override
  Widget build(BuildContext context) {
    final h = _timeRemaining.inHours;
    final m = _timeRemaining.inMinutes.remainder(60);
    final s = _timeRemaining.inSeconds.remainder(60);

    return Scaffold(
      backgroundColor: const Color(0xFF1A1A2E),
      body: Stack(
        children: [
          // 背景装饰
          Positioned.fill(
            child: CustomPaint(
              painter: _FocusBackgroundPainter(),
            ),
          ),

          // 主内容
          SafeArea(
            child: Center(
              child: Column(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  const Spacer(flex: 2),

                  // 图标
                  AnimatedBuilder(
                    animation: _pulseCurve,
                    builder: (_, child) => Transform.scale(
                      scale: 1.0 + _pulseCurve.value * 0.08,
                      child: child,
                    ),
                    child: Container(
                      width: 100,
                      height: 100,
                      decoration: BoxDecoration(
                        shape: BoxShape.circle,
                        gradient: const LinearGradient(
                          colors: [Color(0xFFFF6B6B), Color(0xFFFF8E53)],
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                        ),
                        boxShadow: [
                          BoxShadow(
                            color: const Color(0xFFFF6B6B).withOpacity(0.4),
                            blurRadius: 30,
                            spreadRadius: 5,
                          ),
                        ],
                      ),
                      child: const Icon(
                        Icons.lock_outline,
                        size: 48,
                        color: Colors.white,
                      ),
                    ),
                  ),
                  const SizedBox(height: 32),

                  // 标题
                  const Text(
                    '专注模式',
                    style: TextStyle(
                      fontSize: 28,
                      fontWeight: FontWeight.bold,
                      color: Colors.white,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Consumer<ScheduleProvider>(
                    builder: (_, provider, __) => Text(
                      provider.activeRuleName.isNotEmpty
                          ? '「${provider.activeRuleName}」进行中'
                          : '网络已封锁',
                      style: TextStyle(
                        fontSize: 15,
                        color: Colors.white.withOpacity(0.7),
                      ),
                    ),
                  ),
                  const SizedBox(height: 40),

                  // 倒计时
                  Container(
                    padding: const EdgeInsets.symmetric(
                        horizontal: 40, vertical: 24),
                    decoration: BoxDecoration(
                      color: Colors.white.withOpacity(0.08),
                      borderRadius: BorderRadius.circular(20),
                      border: Border.all(
                        color: Colors.white.withOpacity(0.1),
                      ),
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        _TimeUnit(value: h, label: '时'),
                        _TimeUnit(value: m, label: '分'),
                        _TimeUnit(value: s, label: '秒'),
                      ],
                    ),
                  ),
                  const SizedBox(height: 16),

                  // 倒计时说明
                  if (!_autoExiting)
                    Text(
                      _timeRemaining.inSeconds > 0
                          ? '倒计时结束后自动恢复网络'
                          : '专注即将结束…',
                      style: TextStyle(
                        fontSize: 13,
                        color: Colors.white.withOpacity(0.4),
                      ),
                    )
                  else
                    const SizedBox(
                      width: 24, height: 24,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        valueColor: AlwaysStoppedAnimation<Color>(Colors.white54),
                      ),
                    ),

                  const Spacer(flex: 2),

                  // 退出按钮区域
                  if (!_showExitChallenge) ...[
                    TextButton.icon(
                      onPressed: _onRequestExit,
                      icon: const Icon(Icons.logout, size: 18),
                      label: const Text('提前退出'),
                      style: TextButton.styleFrom(
                        foregroundColor: Colors.white.withOpacity(0.5),
                      ),
                    ),
                    const SizedBox(height: 8),
                  ],

                  if (_showExitChallenge) ...[
                    Container(
                      margin: const EdgeInsets.symmetric(horizontal: 32),
                      padding: const EdgeInsets.all(24),
                      decoration: BoxDecoration(
                        color: Colors.white.withOpacity(0.08),
                        borderRadius: BorderRadius.circular(16),
                        border: Border.all(
                          color: const Color(0xFFFF6B6B).withOpacity(0.3),
                        ),
                      ),
                      child: Column(
                        children: [
                          const Icon(Icons.warning_amber_rounded,
                              color: Color(0xFFFF8E53), size: 32),
                          const SizedBox(height: 12),
                          Text(
                            '请冷静 $_exitCountdown 秒',
                            style: const TextStyle(
                              fontSize: 20,
                              fontWeight: FontWeight.bold,
                              color: Colors.white,
                            ),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            '坚持专注，不要放弃！\n冷静后将自动退出严格模式',
                            textAlign: TextAlign.center,
                            style: TextStyle(
                              fontSize: 13,
                              color: Colors.white.withOpacity(0.6),
                            ),
                          ),
                          const SizedBox(height: 16),
                          // 进度条
                          ClipRRect(
                            borderRadius: BorderRadius.circular(4),
                            child: LinearProgressIndicator(
                              value: _exitCountdown / 30.0,
                              backgroundColor:
                                  Colors.white.withOpacity(0.1),
                              valueColor:
                                  const AlwaysStoppedAnimation<Color>(
                                      Color(0xFFFF6B6B)),
                              minHeight: 6,
                            ),
                          ),
                          const SizedBox(height: 12),
                          TextButton(
                            onPressed: () {
                              _exitTimer?.cancel();
                              setState(() {
                                _showExitChallenge = false;
                                _exitCountdown = 0;
                              });
                            },
                            child: Text(
                              '取消，继续专注',
                              style: TextStyle(
                                color: Colors.white.withOpacity(0.5),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ),
                  ],

                  const Spacer(flex: 1),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// 倒计时数字块
class _TimeUnit extends StatelessWidget {
  final int value;
  final String label;

  const _TimeUnit({required this.value, required this.label});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 8),
      child: Column(
        children: [
          Text(
            value.toString().padLeft(2, '0'),
            style: const TextStyle(
              fontSize: 48,
              fontWeight: FontWeight.w300,
              color: Colors.white,
              fontFeatures: [FontFeature.tabularFigures()],
            ),
          ),
          const SizedBox(height: 4),
          Text(
            label,
            style: TextStyle(
              fontSize: 13,
              color: Colors.white.withOpacity(0.5),
            ),
          ),
        ],
      ),
    );
  }
}

/// 背景装饰画板 — 噪点 + 渐变 + 圈
class _FocusBackgroundPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    // 径向渐变
    final center = Offset(size.width / 2, size.height / 2);
    final radius = math.sqrt(size.width * size.width + size.height * size.height);
    final gradient = RadialGradient(
      colors: [
        const Color(0xFF16213E).withOpacity(0.8),
        const Color(0xFF1A1A2E),
      ],
      stops: const [0.3, 1.0],
    );
    final paint = Paint()
      ..shader = gradient.createShader(Rect.fromCircle(center: center, radius: radius));
    canvas.drawRect(Rect.fromLTWH(0, 0, size.width, size.height), paint);

    // 装饰性半透明圈
    final circlePaint = Paint()
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1;
    for (int i = 0; i < 3; i++) {
      circlePaint.color = Colors.white.withOpacity(0.03 * (i + 1));
      final r = size.width * 0.4 * (i + 1) + (i * 30);
      canvas.drawCircle(center, r, circlePaint);
    }

    // 随机噪点（每次重绘不同，作为视觉趣味）
    final noisePaint = Paint()..color = Colors.white.withOpacity(0.02);
    final rng = math.Random(42);
    for (int i = 0; i < 50; i++) {
      final x = rng.nextDouble() * size.width;
      final y = rng.nextDouble() * size.height;
      canvas.drawCircle(Offset(x, y), rng.nextDouble() * 2 + 1, noisePaint);
    }
  }

  @override
  bool shouldRepaint(covariant CustomPainter oldDelegate) => true;
}
