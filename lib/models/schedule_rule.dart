/// 断网规则数据模型
class ScheduleRule {
  final int? id;
  final String name;
  final int startHour;     // 0-23
  final int startMinute;   // 0-59
  final int endHour;
  final int endMinute;
  final bool enabled;
  final bool repeatDaily;  // 每日重复
  final List<int> repeatDays; // 0=Mon ~ 6=Sun
  final bool blockWifi;
  final bool blockMobile;
  final bool strictMode;   // 严格模式：禁止手动开启

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
  });

  /// 是否覆盖午夜（结束时间在开始时间之后表示跨天）
  bool get crossesMidnight =>
      endHour < startHour || (endHour == startHour && endMinute <= startMinute);

  /// 计算开始时间的分钟数（当天）
  int get startMinutes => startHour * 60 + startMinute;
  /// 计算结束时间的分钟数（当天）
  int get endMinutes => endHour * 60 + endMinute;

  /// 检查当前时间是否在封锁时段内
  bool isActive(DateTime now) {
    if (!enabled) return false;

    // 按星期筛选
    if (!repeatDaily && repeatDays.isNotEmpty) {
      final weekday = now.weekday - 1; // DateTime.Monday=1 → 0
      if (!repeatDays.contains(weekday)) return false;
    }

    final currentMinutes = now.hour * 60 + now.minute;

    if (!crossesMidnight) {
      return currentMinutes >= startMinutes && currentMinutes < endMinutes;
    } else {
      // 跨天：如 22:00 -> 07:00
      return currentMinutes >= startMinutes || currentMinutes < endMinutes;
    }
  }

  /// 友好显示时间段
  String get timeRangeText {
    final pad = (int n) => n.toString().padLeft(2, '0');
    return '${pad(startHour)}:${pad(startMinute)} - ${pad(endHour)}:${pad(endMinute)}';
  }

  /// 到下一个封锁开始/结束的倒计时
  Duration timeUntilNextChange(DateTime now) {
    if (isActive(now)) {
      // 找结束时间
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
      // 找开始时间
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
  );
}
