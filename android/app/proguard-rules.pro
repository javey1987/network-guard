# 定时断网助手 ProGuard 规则

# Flutter
-keep class io.flutter.app.** { *; }
-keep class io.flutter.plugin.**  { *; }
-keep class io.flutter.util.**  { *; }
-keep class io.flutter.view.**  { *; }
-keep class io.flutter.**  { *; }
-keep class io.flutter.plugins.**  { *; }

# 网络断网服务
-keep class com.networkguard.** { *; }

# 通知
-keep class com.dexterous.flutterlocalnotifications.** { *; }
