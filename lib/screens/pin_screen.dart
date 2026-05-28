import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import '../services/pin_service.dart';

/// PIN 输入验证界面
class PinScreen extends StatefulWidget {
  final PinMode mode;
  const PinScreen({super.key, this.mode = PinMode.verify});

  @override
  State<PinScreen> createState() => _PinScreenState();
}

enum PinMode { set, verify, change }

class _PinScreenState extends State<PinScreen> {
  final _pinController = TextEditingController();
  final _confirmController = TextEditingController();
  final _formKey = GlobalKey<FormState>();
  bool _obscurePin = true;
  String? _errorMsg;

  bool get _isSetMode => widget.mode == PinMode.set || widget.mode == PinMode.change;

  @override
  void dispose() {
    _pinController.dispose();
    _confirmController.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_formKey.currentState!.validate()) return;
    HapticFeedback.mediumImpact();

    if (_isSetMode) {
      // 设置/修改 PIN
      final pin = _pinController.text;
      if (pin.length < 4) {
        setState(() => _errorMsg = 'PIN 至少 4 位数字');
        return;
      }
      if (pin != _confirmController.text) {
        setState(() => _errorMsg = '两次输入不一致');
        return;
      }
      final ok = await PinService.setPin(pin);
      if (!mounted) return;
      if (ok) {
        Navigator.of(context).pop(true);
      } else {
        setState(() => _errorMsg = '保存失败，请重试');
      }
    } else {
      // 验证 PIN
      final ok = await PinService.verifyPin(_pinController.text);
      if (!mounted) return;
      if (ok) {
        Navigator.of(context).pop(true);
      } else {
        setState(() => _errorMsg = 'PIN 错误');
        _pinController.clear();
        HapticFeedback.heavyImpact();
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final title = switch (widget.mode) {
      PinMode.set => '设置管理员 PIN',
      PinMode.change => '修改管理员 PIN',
      PinMode.verify => '验证管理员 PIN',
    };

    return Scaffold(
      appBar: AppBar(title: Text(title)),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Form(
          key: _formKey,
          child: Column(
            children: [
              const SizedBox(height: 40),
              Icon(
                _isSetMode ? Icons.lock_outline : Icons.lock,
                size: 64,
                color: Theme.of(context).colorScheme.primary,
              ),
              const SizedBox(height: 16),
              Text(
                _isSetMode ? '设置 4-6 位数字 PIN' : '请输入管理员 PIN',
                style: Theme.of(context).textTheme.titleMedium,
              ),
              const SizedBox(height: 32),

              TextFormField(
                controller: _pinController,
                obscureText: _obscurePin,
                keyboardType: TextInputType.number,
                maxLength: 6,
                decoration: InputDecoration(
                  labelText: _isSetMode ? '新 PIN' : 'PIN',
                  prefixIcon: const Icon(Icons.pin),
                  suffixIcon: IconButton(
                    icon: Icon(_obscurePin ? Icons.visibility_off : Icons.visibility),
                    onPressed: () => setState(() => _obscurePin = !_obscurePin),
                  ),
                  border: const OutlineInputBorder(),
                ),
                validator: (v) {
                  if (v == null || v.isEmpty) return '请输入 PIN';
                  if (_isSetMode && v.length < 4) return '至少 4 位';
                  return null;
                },
              ),

              if (_isSetMode) ...[
                const SizedBox(height: 16),
                TextFormField(
                  controller: _confirmController,
                  obscureText: _obscurePin,
                  keyboardType: TextInputType.number,
                  maxLength: 6,
                  decoration: const InputDecoration(
                    labelText: '确认 PIN',
                    prefixIcon: Icon(Icons.pin),
                    border: OutlineInputBorder(),
                  ),
                  validator: (v) {
                    if (v == null || v.isEmpty) return '请再次输入 PIN';
                    if (v != _pinController.text) return '两次输入不一致';
                    return null;
                  },
                ),
              ],

              if (_errorMsg != null) ...[
                const SizedBox(height: 16),
                Text(_errorMsg!, style: const TextStyle(color: Colors.red)),
              ],

              const SizedBox(height: 32),
              SizedBox(
                width: double.infinity,
                height: 50,
                child: FilledButton.icon(
                  onPressed: _submit,
                  icon: Icon(_isSetMode ? Icons.save : Icons.check),
                  label: Text(_isSetMode ? '保存' : '验证'),
                ),
              ),

              const Spacer(),
              if (!_isSetMode)
                TextButton(
                  onPressed: () => Navigator.of(context).pop(false),
                  child: const Text('取消'),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
