import 'dart:io';
import 'dart:convert';
import 'package:path_provider/path_provider.dart';

/// 激活码验证服务（依赖文件存储，零额外包）
class ActivationService {
  static const _apiBase = 'https://lilihaha.com/api';

  /// 检查设备是否已激活
  static Future<bool> isActivated() async {
    try {
      final data = await _load();
      if (data['activated'] != true) return false;

      // 24小时内缓存有效，免重复网络请求
      final lastCheck = (data['lastCheck'] as num?)?.toInt() ?? 0;
      final now = DateTime.now().millisecondsSinceEpoch;
      if (now - lastCheck < 86400000) return true;

      // 超时后远程验证
      try {
        final ok = await _checkRemote(data['deviceId'] ?? '');
        if (!ok) {
          data['activated'] = false;
          await _save(data);
        }
        return ok;
      } catch (_) {
        return true; // 网络不通时信任本地
      }
    } catch (_) {
      return false;
    }
  }

  /// 激活
  static Future<ActivationResult> activate(String code) async {
    try {
      final deviceId = await _getDeviceId();
      final client = HttpClient()..connectionTimeout = const Duration(seconds: 10);

      final request = await client.postUrl(Uri.parse('$_apiBase/activate'));
      request.headers.contentType = ContentType.json;
      request.write(jsonEncode({'code': code.trim().toUpperCase(), 'deviceId': deviceId}));

      final response = await request.close();
      final body = await response.transform(utf8.decoder).join();
      client.close();

      final data = jsonDecode(body) as Map<String, dynamic>;
      if (data['ok'] == true) {
        await _save({
          'activated': true,
          'code': code.trim().toUpperCase(),
          'deviceId': deviceId,
          'lastCheck': DateTime.now().millisecondsSinceEpoch,
        });
        return ActivationResult(true, data['msg'] ?? '激活成功');
      }
      return ActivationResult(false, data['msg'] ?? '激活失败');
    } catch (e) {
      return ActivationResult(false, '网络错误，请检查网络连接');
    }
  }

  /// 远程检查激活状态
  static Future<bool> _checkRemote(String deviceId) async {
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 10);
    try {
      final request = await client.postUrl(Uri.parse('$_apiBase/check'));
      request.headers.contentType = ContentType.json;
      request.write(jsonEncode({'deviceId': deviceId}));
      final response = await request.close();
      final body = await response.transform(utf8.decoder).join();
      final data = jsonDecode(body) as Map<String, dynamic>;
      return data['activated'] == true;
    } finally {
      client.close();
    }
  }

  /// 获取/生成设备唯一 ID
  static Future<String> _getDeviceId() async {
    final data = await _load();
    if (data.containsKey('deviceId') && (data['deviceId'] as String).isNotEmpty) {
      return data['deviceId'] as String;
    }
    final id = 'DG${DateTime.now().millisecondsSinceEpoch}${_randomStr(4)}';
    data['deviceId'] = id;
    await _save(data);
    return id;
  }

  static String _randomStr(int len) {
    final r = DateTime.now().microsecondsSinceEpoch.toString();
    return r.substring(r.length - len);
  }

  /// 持久化存储（JSON 文件，无需额外依赖）
  static Future<Map<String, dynamic>> _load() async {
    try {
      final file = await _getFile();
      if (await file.exists()) {
        return jsonDecode(await file.readAsString()) as Map<String, dynamic>;
      }
    } catch (_) {}
    return {};
  }

  static Future<void> _save(Map<String, dynamic> data) async {
    final file = await _getFile();
    await file.writeAsString(jsonEncode(data));
  }

  static Future<File> _getFile() async {
    final dir = await getApplicationDocumentsDirectory();
    return File('${dir.path}/activation.json');
  }
}

class ActivationResult {
  final bool success;
  final String message;
  ActivationResult(this.success, this.message);
}
