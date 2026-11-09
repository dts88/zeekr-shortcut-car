/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) suyunkai and the EVCam contributors - https://github.com/suyunkai/EVCam
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * 保活广播接收器：系统有动静时确认一次前台服务在不在（该不该在由相机管家判，{@link #ensureServicesRunning}）。
 *
 * <p>清单里登记的只留安卓 8 以后还送得到的（2026-11-10，项目所有者；平台笔记 §3.7）：蓝牙设备连上 / 断开、
 * U 盘挂上 / 卸下 / 移除 / 弹出、USB 设备插拔、时区 / 时间 / 语言变化、本应用更新，加自己发的保活检查
 * （{@link #ACTION_KEEP_ALIVE}）。动态注册的：TIME_TICK（每分钟，{@link #registerTimeTick}）。
 * 亮屏 / 熄屏、电源、电量、网络、耳机、日期、飞行模式、装应用这些系统不会送给清单里的接收器，2026-11-10 删掉。</p>
 *
 * <p>接收器对外开放（要收蓝牙进程发的广播），别的应用也能发保活检查：它只请相机管家确认前台服务，
 * 管家照自己的规矩判，发了也无害。</p>
 */
public class KeepAliveReceiver extends BroadcastReceiver {
    private static final String TAG = "KeepAliveReceiver";
    
    // 自定义广播 Action，用于手动触发保活检查
    public static final String ACTION_KEEP_ALIVE = "com.kooo.evcam.ACTION_KEEP_ALIVE";
    
    private static KeepAliveReceiver timeTickReceiver;
    private static boolean isTimeTickRegistered = false;
    
    // 上次触发时间，用于防止短时间内重复触发
    private static long lastTriggerTime = 0;
    private static final long MIN_TRIGGER_INTERVAL = 3000; // 最小触发间隔 3 秒

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        
        String action = intent.getAction();

        // 是哪个广播把进程拉起来的，记在进程启动那一行里。每种广播各来了几次，
        // 睡眠唤醒那一轮研究完（平台笔记 §3.6）就不再计数
        com.kooo.evcam.blackbox.BlackBox.attach(context, "Receiver:" + action);
        if (UserExit.blocks(context, "KeepAliveReceiver")) {
            return;
        }
        // 亮屏把因熄屏退下去的主界面接回来，是「回到用户设定的状态」（规格 §0），不归保活管：
        // 那是 ScreenState → Recovery.bringBackUiAfterScreenOn 的事，这里只把亮屏当保活的触发
        if (!new AppConfig(context).isAutoStartOnBoot()) {
            // 保活关着（规格 §3）：不拉
            return;
        }

        switch (action) {
            // ========== 蓝牙设备（车机启动会自动连手机蓝牙） ==========
            case "android.bluetooth.device.action.ACL_CONNECTED":
                AppLog.d(TAG, "【蓝牙】蓝牙设备已连接");
                ensureServicesRunning(context, "蓝牙设备连接");
                break;

            case "android.bluetooth.device.action.ACL_DISCONNECTED":
                AppLog.d(TAG, "【蓝牙】蓝牙设备已断开");
                ensureServicesRunning(context, "蓝牙设备断开");
                break;

            // ========== U 盘 / USB（插 U 盘触发） ==========
            case Intent.ACTION_MEDIA_MOUNTED:
                AppLog.d(TAG, "【存储】存储已挂载（U盘/SD卡插入）");
                ensureServicesRunning(context, "存储挂载");
                break;

            case Intent.ACTION_MEDIA_UNMOUNTED:
                AppLog.d(TAG, "【存储】存储已卸载");
                ensureServicesRunning(context, "存储卸载");
                break;

            case Intent.ACTION_MEDIA_REMOVED:
                AppLog.d(TAG, "【存储】存储已移除");
                ensureServicesRunning(context, "存储移除");
                break;

            case Intent.ACTION_MEDIA_EJECT:
                AppLog.d(TAG, "【存储】存储弹出请求");
                ensureServicesRunning(context, "存储弹出");
                break;

            case "android.hardware.usb.action.USB_DEVICE_ATTACHED":
                AppLog.d(TAG, "【USB】USB设备已连接");
                ensureServicesRunning(context, "USB连接");
                break;

            case "android.hardware.usb.action.USB_DEVICE_DETACHED":
                AppLog.d(TAG, "【USB】USB设备已断开");
                ensureServicesRunning(context, "USB断开");
                break;

            // ========== 时间、时区、语言 ==========
            case Intent.ACTION_TIMEZONE_CHANGED:
                AppLog.d(TAG, "【时间】时区变化");
                ensureServicesRunning(context, "时区变化");
                break;

            case Intent.ACTION_TIME_CHANGED:
                AppLog.d(TAG, "【时间】时间设置变化");
                ensureServicesRunning(context, "时间变化");
                break;

            case Intent.ACTION_LOCALE_CHANGED:
                AppLog.d(TAG, "【系统】语言/区域变化");
                ensureServicesRunning(context, "语言变化");
                break;

            // ========== 本应用更新 ==========
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                AppLog.d(TAG, "【应用】应用已更新，重新激活服务");
                ensureServicesRunning(context, "应用更新");
                // 应用更新后重新注册 TIME_TICK
                registerTimeTick(context);
                com.kooo.evcam.recovery.Recovery.restore(context, "package-replaced");
                break;

            // ========== 每分钟定时（动态注册） ==========
            case Intent.ACTION_TIME_TICK:
                onTimeTick(context);
                break;

            // ========== 自己发的保活检查 ==========
            case ACTION_KEEP_ALIVE:
                AppLog.d(TAG, "【保活】收到手动保活检查请求");
                ensureServicesRunning(context, "手动保活");
                break;

            default:
                // 其他未知广播也触发保活检查
                AppLog.d(TAG, "【其他】收到广播: " + action);
                ensureServicesRunningQuiet(context);
                break;
        }
    }

    /** TIME_TICK 处理（每分钟调用）：前台服务不在就拉起来。无障碍服务在跑的话它自己有心跳，这里不重复。 */
    private void onTimeTick(Context context) {
        if (KeepAliveAccessibilityService.isRunning()) {
            return;
        }
        ensureServicesRunning(context, "定时检查");
    }
    
    /**
     * 确保所有保活服务正在运行（带触发原因）
     * @param context 上下文
     * @param reason 触发原因：只写进日志。通知栏上给人看的是「在后台运行」，「屏幕亮起」「电量正常」这类原因是排查用的
     */
    private void ensureServicesRunning(Context context, String reason) {
        // 前台服务在就什么都不做：以前每分钟都去 start 一次，服务每分钟重跑 onStartCommand、重发通知
        if (CameraForegroundService.isRunning()) {
            return;
        }
        // 防止短时间内重复触发
        long now = System.currentTimeMillis();
        if (now - lastTriggerTime < MIN_TRIGGER_INTERVAL) {
            return;
        }
        lastTriggerTime = now;

        try {
            // 启动前台服务（该不该开着相机管家判）
            com.kooo.evcam.camera.CameraKeeper.get(context).ensureForegroundService("keep-alive " + reason);
            AppLog.d(TAG, "已请求启动前台服务 (触发: " + reason + ")");
        } catch (Exception e) {
            AppLog.e(TAG, "启动服务失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 静默确保服务运行（不输出日志，用于频繁触发的广播）
     * @param context 上下文
     */
    private void ensureServicesRunningQuiet(Context context) {
        if (CameraForegroundService.isRunning()) {
            return;
        }
        // 防止短时间内重复触发
        long now = System.currentTimeMillis();
        if (now - lastTriggerTime < MIN_TRIGGER_INTERVAL) {
            return;
        }
        lastTriggerTime = now;
        
        try {
            // 启动前台服务（该不该开着相机管家判）
            com.kooo.evcam.camera.CameraKeeper.get(context).ensureForegroundService("keep-alive");
        } catch (Exception e) {
            // 静默失败，不输出日志
        }
    }
    
    /**
     * 动态注册 TIME_TICK 广播接收器
     * TIME_TICK 在 Android 8.0+ 只能动态注册
     * 
     * @param context 上下文
     */
    public static synchronized void registerTimeTick(Context context) {
        if (isTimeTickRegistered) {
            AppLog.d(TAG, "TIME_TICK 接收器已注册，跳过");
            return;
        }
        
        try {
            timeTickReceiver = new KeepAliveReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_TICK);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getApplicationContext().registerReceiver(
                        timeTickReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.getApplicationContext().registerReceiver(timeTickReceiver, filter);
            }
            
            isTimeTickRegistered = true;
            AppLog.d(TAG, "TIME_TICK 接收器已动态注册（每分钟触发）");
        } catch (Exception e) {
            AppLog.e(TAG, "注册 TIME_TICK 接收器失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 注销 TIME_TICK 广播接收器
     * 
     * @param context 上下文
     */
    public static synchronized void unregisterTimeTick(Context context) {
        if (!isTimeTickRegistered || timeTickReceiver == null) {
            return;
        }
        
        try {
            context.getApplicationContext().unregisterReceiver(timeTickReceiver);
            timeTickReceiver = null;
            isTimeTickRegistered = false;
            AppLog.d(TAG, "TIME_TICK 接收器已注销");
        } catch (Exception e) {
            AppLog.e(TAG, "注销 TIME_TICK 接收器失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 检查 TIME_TICK 是否已注册
     */
    public static boolean isTimeTickRegistered() {
        return isTimeTickRegistered;
    }
    
    /**
     * 发送保活检查广播
     * 可以在任何地方调用此方法触发保活检查
     * 
     * @param context 上下文
     */
    public static void sendKeepAliveCheck(Context context) {
        try {
            Intent intent = new Intent(ACTION_KEEP_ALIVE);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
        } catch (Exception e) {
            AppLog.e(TAG, "发送保活检查广播失败: " + e.getMessage(), e);
        }
    }
}
