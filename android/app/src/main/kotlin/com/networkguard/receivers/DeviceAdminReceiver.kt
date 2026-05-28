package com.networkguard.receivers

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent

/**
 * 设备管理员广播接收器。
 *
 * 当用户通过系统设置授权本 App 为设备管理员后，
 * DevicePolicyManager 的一些高级功能（如增强屏幕固定）才可用。
 *
 * 非必须——屏幕固定不需要设备管理员授权也能用，
 * 只是授权后更难退出（解锁时需要 PIN/密码）。
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        // 设备管理员授权成功
    }

    override fun onDisabled(context: Context, intent: Intent) {
        // 设备管理员被撤销
    }
}
