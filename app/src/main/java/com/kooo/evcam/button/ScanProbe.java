/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.location.LocationManager;
import android.os.IBinder;
import android.os.IInterface;
import android.view.Display;

import com.kooo.evcam.camera.LogcatNoise;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 扫描起不来时看一眼为什么，只读，结果进黑匣子（2026-10-31 车主：车机唤醒后 scan failed 3，12 分钟没好，报告里看不出卡在哪）。
 *
 * <p>安卓 12L 的 {@code BluetoothLeScanner}：错误 3 只有两个来路。问系统要 GATT 服务要到 null、或者问的时候抛远程异常（不打日志）；或者要到了，
 * 注册时抛远程异常（打一行 {@code E/BluetoothLeScanner}，紧接着再报一个错误 2）。应用跑在容器里，这些调用都经过它（标签 {@code falco_32} 的跟踪）。
 * 这里分别问适配器手里那一份和现从系统拿的一份，再留下日志里蓝牙相关的几行：是系统没有、是死的、还是只有我们手里那份是旧的，一看就知道。</p>
 *
 * <p>车机醒来开始听时也记一笔当时会影响扫描的几件事（{@link #scanFacts}，2026-11-01 起）：定位、屏幕亮不亮、在不在搜索设备、连着几台。
 * 应用只读得到自己进程的日志，蓝牙进程那边看不到，以后还有没反应，从这里接着看。</p>
 */
final class ScanProbe {

    /** 从 logcat 读多少行。容器的跟踪很多，和诊断报告读得一样多。 */
    private static final int LOG_READ_LINES = 4000;
    /** 留下最后多少行。 */
    private static final int LOG_KEEP_LINES = 20;

    private ScanProbe() {
    }

    /** 两份 GATT 服务各是什么样。走隐藏接口，不让看就说不让看，不抛。 */
    static String gattFacts(BluetoothAdapter adapter) {
        return "GATT via adapter: " + viaAdapter(adapter) + "; via service manager: " + viaServiceManager();
    }

    /** 此刻会影响扫描的几件事：定位、屏幕、在不在搜索设备、配对过的连着几台。都是只读；有的走隐藏接口，不让看就说不让看，不抛。 */
    static String scanFacts(Context context, BluetoothAdapter adapter) {
        return "location " + location(context) + ", displays on " + displaysOn(context) + ", " + links(adapter);
    }

    private static String location(Context context) {
        try {
            return context.getSystemService(LocationManager.class).isLocationEnabled() ? "on" : "off";
        } catch (Throwable e) {
            return "? (" + e + ")";
        }
    }

    private static String displaysOn(Context context) {
        try {
            Display[] displays = context.getSystemService(DisplayManager.class).getDisplays();
            int on = 0;
            for (Display display : displays) {
                if (display.getState() == Display.STATE_ON) {
                    on++;
                }
            }
            return on + " of " + displays.length;
        } catch (Throwable e) {
            return "? (" + e + ")";
        }
    }

    private static String links(BluetoothAdapter adapter) {
        try {
            Set<BluetoothDevice> bonded = adapter.getBondedDevices();
            return "discovering " + adapter.isDiscovering() + ", connected " + connected(bonded, true).size() + " of " + bonded.size()
                    + " bonded";
        } catch (Throwable e) {
            return "links? (" + e + ")";
        }
    }

    /** 已配对、连着的设备里不只用低功耗的那些（手机这一类；灵动贴只用低功耗，不算）的地址；读不到是 null。 */
    static Set<String> connectedNotLe(BluetoothAdapter adapter) {
        try {
            return connected(adapter.getBondedDevices(), false);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /** 这些设备里连着的地址（走隐藏接口 isConnected）；leToo = false 时不算只用低功耗的。 */
    private static Set<String> connected(Set<BluetoothDevice> devices, boolean leToo) throws ReflectiveOperationException {
        Method isConnected = BluetoothDevice.class.getMethod("isConnected");
        Set<String> out = new TreeSet<>();
        for (BluetoothDevice device : devices) {
            if ((leToo || device.getType() != BluetoothDevice.DEVICE_TYPE_LE) && Boolean.TRUE.equals(isConnected.invoke(device))) {
                out.add(device.getAddress());
            }
        }
        return out;
    }

    private static String viaAdapter(BluetoothAdapter adapter) {
        try {
            Field field = BluetoothAdapter.class.getDeclaredField("mManagerService");
            field.setAccessible(true);
            return describe(field.get(adapter));
        } catch (Throwable e) {
            return "can't look (" + e + ")";
        }
    }

    private static String viaServiceManager() {
        try {
            Object binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "bluetooth_manager");
            if (binder == null) {
                return "no bluetooth_manager";
            }
            return describe(Class.forName("android.bluetooth.IBluetoothManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder));
        } catch (Throwable e) {
            return "can't look (" + e + ")";
        }
    }

    private static String describe(Object manager) throws ReflectiveOperationException {
        Object gatt = Class.forName("android.bluetooth.IBluetoothManager").getMethod("getBluetoothGatt").invoke(manager);
        if (gatt == null) {
            return "null";
        }
        IBinder binder = ((IInterface) gatt).asBinder();
        return (binder.pingBinder() ? "alive" : "dead") + " (" + gatt.getClass().getName() + ", " + binder.getClass().getName() + ")";
    }

    /** logcat 里最近蓝牙相关的几行。 */
    static List<String> bluetoothLog() {
        List<String> kept = new ArrayList<>();
        java.lang.Process process = null;
        try {
            process = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-v", "time", "-t", String.valueOf(LOG_READ_LINES)});
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (aboutBluetooth(line)) {
                        kept.add(LogcatNoise.clip(line));
                    }
                }
            }
        } catch (Exception e) {
            kept.add("(logcat unavailable: " + e + ")");
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
        return kept.subList(Math.max(0, kept.size() - LOG_KEEP_LINES), kept.size());
    }

    /** 蓝牙相关的行；每收到一包广播一行的不算（一秒几十行，别的全被它们挤掉）。 */
    static boolean aboutBluetooth(String line) {
        return !line.contains("ScanResult")
                && (line.contains("luetooth") || line.contains("Gatt") || line.contains("GATT") || line.contains("registerScanner"));
    }
}
