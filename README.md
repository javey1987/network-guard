# 🚫 定时断网助手 (Network Guard)

一款 Flutter 移动端 App，**到点自动封锁无线网和移动网络**，帮你远离手机，专注当下。

---

## 工作原理

采用 **本地 VPN** 方案（无需 ROOT）：

1. App 创建一个本地虚拟网卡
2. 在封锁时段内，所有网络数据包**被 VPN 服务主动丢弃**
3. 在非封锁时段，数据包正常通过
4. 整个过程中数据不出设备，**不会上传你的任何流量**

> 类似「Forest 专注森林」的深度专注模式实现方式。

## 功能

- ✅ **定时规则**：设置开始/结束时间，支持跨天（如 22:00 → 07:00）
- ✅ **WiFi + 移动网络**：任意选择封锁哪些网络类型
- ✅ **每周重复**：每天、工作日、周末或自定义星期
- ✅ **多条规则**：可同时添加多条，按时间顺序匹配
- ✅ **严格模式**：封锁期间无法手动重新打开网络
- ✅ **开机自启**：关机重启后自动恢复封锁状态
- ✅ **通知提醒**：断网/恢复网络/即将断网均有通知
- ✅ **倒计时**：主页显示距离下次断网/恢复的剩余时间

## 快速上手指南

### 环境要求

- Flutter SDK ≥ 3.0
- Android SDK 26+
- Android 设备（真机或模拟器）

### 编译安装

```bash
# 1. 克隆项目
cd network_guard_app

# 2. 安装依赖
flutter pub get

# 3. 编译并安装到设备
flutter run

# 4. 生成 Release 包
flutter build apk --release
```

### 首次使用

1. 打开 App → 点击「添加规则」
2. 设置规则名称（如「晚间静修」）
3. 选择开始时间（如 22:00）和结束时间（如 07:00）
4. 选择重复方式（每天/工作日）
5. 勾选需要封锁的网络类型（WiFi / 移动网络）
6. 点击「创建规则」
7. App 会请求 VPN 权限，点击「确定」
8. ✅ 设置完成！到点自动断网

## 项目结构

```
network_guard_app/
├── lib/
│   ├── main.dart                          # 入口
│   ├── models/
│   │   └── schedule_rule.dart             # 规则数据模型
│   ├── providers/
│   │   └── schedule_provider.dart         # 状态管理 + 定时检查
│   ├── screens/
│   │   ├── home_screen.dart               # 主页（状态 + 规则列表）
│   │   └── add_schedule_screen.dart       # 添加/编辑规则页
│   ├── services/
│   │   ├── database_service.dart          # SQLite 存储
│   │   ├── notification_service.dart      # 本地通知
│   │   └── vpn_service.dart               # Flutter ↔ Native 通道
│   └── widgets/                           # 公共组件
├── android/
│   └── app/src/main/kotlin/com/networkguard/
│       ├── MainActivity.kt                # Flutter 入口 + 方法通道
│       ├── receivers/
│       │   └── BootReceiver.kt            # 开机恢复接收器
│       └── services/
│           └── NetworkGuardVpnService.kt  # 核心 VPN 引擎
└── pubspec.yaml
```

## 技术要点

| 模块 | 技术 |
|------|------|
| UI | Flutter + Material 3 + Provider |
| 存储 | SQLite (sqflite) |
| 断网引擎 | Android VpnService API |
| 通知 | flutter_local_notifications |
| 状态管理 | Provider (ChangeNotifier) |
| 后台任务 | Android Service + Foreground Service |

## 权限说明

- `BIND_VPN_SERVICE` — 创建 VPN 连接（必选）
- `POST_NOTIFICATIONS` — 发送断网通知（Android 13+ 需手动授权）
- `RECEIVE_BOOT_COMPLETED` — 开机恢复封锁
- `FOREGROUND_SERVICE` — 后台持续运行（Android 8+ 要求）

## 常见问题

**需要 root 吗？**
不需要。使用系统提供的 VpnService API，原理类似所有 VPN App。

**耗电吗？**
极低。VPN 虚拟网卡仅在 Android 内核层面工作，不做加密/解密。

**数据安全吗？**
数据不出设备。VPN 完全在本地运行，所有流量在设备内被丢弃。

**iOS 支持吗？**
当前版本仅支持 Android。iOS 可用 NEPacketTunnelProvider 实现，底层原理相同。
