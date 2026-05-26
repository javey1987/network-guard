import 'dart:async';
import 'package:sqflite/sqflite.dart';
import 'package:path/path.dart';
import '../models/schedule_rule.dart';

class DatabaseService {
  static Database? _db;
  static const String _table = 'schedule_rules';

  static Future<Database> get database async {
    if (_db != null) return _db!;
    _db = await _initDB();
    return _db!;
  }

  static Future<Database> _initDB() async {
    final dbPath = await getDatabasesPath();
    final path = join(dbPath, 'network_guard.db');
    return openDatabase(
      path,
      version: 1,
      onCreate: (db, version) async {
        await db.execute('''
          CREATE TABLE $_table (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            name TEXT NOT NULL,
            startHour INTEGER NOT NULL,
            startMinute INTEGER NOT NULL,
            endHour INTEGER NOT NULL,
            endMinute INTEGER NOT NULL,
            enabled INTEGER NOT NULL DEFAULT 1,
            repeatDaily INTEGER NOT NULL DEFAULT 1,
            repeatDays TEXT NOT NULL DEFAULT '',
            blockWifi INTEGER NOT NULL DEFAULT 1,
            blockMobile INTEGER NOT NULL DEFAULT 1,
            strictMode INTEGER NOT NULL DEFAULT 0
          )
        ''');
      },
    );
  }

  static Future<List<ScheduleRule>> getAll() async {
    final db = await database;
    final maps = await db.query(_table, orderBy: 'startHour ASC, startMinute ASC');
    return maps.map((m) => ScheduleRule.fromMap(m)).toList();
  }

  static Future<int> insert(ScheduleRule rule) async {
    final db = await database;
    return db.insert(_table, rule.toMap());
  }

  static Future<int> update(ScheduleRule rule) async {
    final db = await database;
    return db.update(_table, rule.toMap(), where: 'id = ?', whereArgs: [rule.id]);
  }

  static Future<int> delete(int id) async {
    final db = await database;
    return db.delete(_table, where: 'id = ?', whereArgs: [id]);
  }
}
