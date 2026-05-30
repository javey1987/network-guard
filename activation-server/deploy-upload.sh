#!/bin/bash
# 上传 APK 到激活码服务的 upload-apk 接口
# 调用方式: ./deploy-upload.sh <apk-path>

API_URL="https://lilihaha.com/api/upload-apk"
ADMIN_KEY="$1"
APK_PATH="$2"

if [ -z "$ADMIN_KEY" ] || [ -z "$APK_PATH" ]; then
  echo "用法: $0 <admin-key> <apk-path>"
  echo "示例: $0 my-secret-key build/app/outputs/flutter-apk/app-arm64-v8a-debug.apk"
  exit 1
fi

if [ ! -f "$APK_PATH" ]; then
  echo "❌ 文件不存在: $APK_PATH"
  exit 1
fi

echo "📤 上传 APK..."
curl -s -X POST "$API_URL" \
  -H "x-admin-key: $ADMIN_KEY" \
  -F "apk=@$APK_PATH" | jq .
# Trigger redeploy
# bump
