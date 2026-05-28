/// 断网规则数据模型
class ScheduleRule {
  final int? id;
  final String name;
  final int startHour;
  final int startMinute;
  final int endHour;
  final int endMinute;
  final bool enabled;
  final bool repeatDaily;
  final List<int> repeatDays;
  final bool blockWifi;
  final bool blockMobile;
  final bool strictMode;
  final List<String> allowedApps; // 白名单：断网期间仍可用的应用包名

  ScheduleRule({
    this.id,
    required this.name,
    required this.startHour,
    required this.startMinute,
    required this.endHour,
    required this.endMinute,
    this.enabled = true,
    this.repeatDaily = true,
    this.repeatDays = const [],
    this.blockWifi = true,
    this.blockMobile = true,
    this.strictMode = false,
    this.allowedApps = const [],
  });

  bool get hasAllowedApps => allowedApps.isNotEmpty;

  /// 是否覆盖午夜
  bool get crossesMidnight =>
      endHour < startHour || (endHour == startHour && endMinute <= startMinute);

  int get startMinutes => startHour * 60 + startMinute;
  int get endMinutes => endHour * 60 + endMinute;

  /// 检查当前时间是否在封锁时段内
  bool isActive(DateTime now) {
    if (!enabled) return false;
    if (!repeatDaily && repeatDays.isNotEmpty) {
      final weekday = now.weekday - 1;
      if (!repeatDays.contains(weekday)) return false;
    }
    final currentMinutes = now.hour * 60 + now.minute;
    if (!crossesMidnight) {
      return currentMinutes >= startMinutes && currentMinutes < endMinutes;
    } else {
      return currentMinutes >= startMinutes || currentMinutes < endMinutes;
    }
  }

  String get timeRangeText {
    final pad = (int n) => n.toString().padLeft(2, '0');
    return '${pad(startHour)}:${pad(startMinute)} - ${pad(endHour)}:${pad(endMinute)}';
  }

  Duration timeUntilNextChange(DateTime now) {
    if (isActive(now)) {
      DateTime end = DateTime(now.year, now.month, now.day, endHour, endMinute);
      if (crossesMidnight) {
        if (end.isBefore(now) || end.isAtSameMomentAs(now)) {
          end = end.add(const Duration(days: 1));
        }
      } else {
        if (end.isBefore(now) || end.isAtSameMomentAs(now)) {
          end = end.add(const Duration(days: 1));
        }
      }
      return end.difference(now);
    } else {
      DateTime start = DateTime(now.year, now.month, now.day, startHour, startMinute);
      if (start.isBefore(now) || start.isAtSameMomentAs(now)) {
        start = start.add(const Duration(days: 1));
      }
      return start.difference(now);
    }
  }

  Map<String, dynamic> toMap() => {
    'id': id,
    'name': name,
    'startHour': startHour,
    'startMinute': startMinute,
    'endHour': endHour,
    'endMinute': endMinute,
    'enabled': enabled ? 1 : 0,
    'repeatDaily': repeatDaily ? 1 : 0,
    'repeatDays': repeatDays.join(','),
    'blockWifi': blockWifi ? 1 : 0,
    'blockMobile': blockMobile ? 1 : 0,
    'strictMode': strictMode ? 1 : 0,
    'allowedApps': allowedApps.join(','),
  };

  factory ScheduleRule.fromMap(Map<String, dynamic> map) => ScheduleRule(
    id: map['id'] as int?,
    name: map['name'] as String,
    startHour: map['startHour'] as int,
    startMinute: map['startMinute'] as int,
    endHour: map['endHour'] as int,
    endMinute: map['endMinute'] as int,
    enabled: (map['enabled'] as int) == 1,
    repeatDaily: (map['repeatDaily'] as int) == 1,
    repeatDays: (map['repeatDays'] as String).split(',').where((s) => s.isNotEmpty).map(int.parse).toList(),
    blockWifi: (map['blockWifi'] as int) == 1,
    blockMobile: (map['blockMobile'] as int) == 1,
    strictMode: (map['strictMode'] as int) == 1,
    allowedApps: (map['allowedApps'] as String?)?.split(',').where((s) => s.isNotEmpty).toList() ?? [],
  );

  ScheduleRule copyWith({
    int? id,
    String? name,
    int? startHour,
    int? startMinute,
    int? endHour,
    int? endMinute,
    bool? enabled,
    bool? repeatDaily,
    List<int>? repeatDays,
    bool? blockWifi,
    bool? blockMobile,
    bool? strictMode,
    List<String>? allowedApps,
  }) => ScheduleRule(
    id: id ?? this.id,
    name: name ?? this.name,
    startHour: startHour ?? this.startHour,
    startMinute: startMinute ?? this.startMinute,
    endHour: endHour ?? this.endHour,
    endMinute: endMinute ?? this.endMinute,
    enabled: enabled ?? this.enabled,
    repeatDaily: repeatDaily ?? this.repeatDaily,
    repeatDays: repeatDays ?? this.repeatDays,
    blockWifi: blockWifi ?? this.blockWifi,
    blockMobile: blockMobile ?? this.blockMobile,
    strictMode: strictMode ?? this.strictMode,
    allowedApps: allowedApps ?? this.allowedApps,
  );
}
