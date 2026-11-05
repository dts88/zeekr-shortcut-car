/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.telemetry;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.settings.DeveloperMode;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 环视录像下方的行驶信息条：开不开、显示哪几项。座舱两路不加（项目所有者 2026-10-09）。
 *
 * <h3>一条规则</h3>
 *
 * <p>开着信息条的录像，画面比视频高 {@link #HEIGHT} 像素，最下面那一条是信息条
 * （{@code EncodeSize.withInfoBar}）。凡是按「四宫格」推算画面几何的地方
 * （回看的放大、鱼眼校正）都先把这一条减掉（{@code PlaybackViewport.infoBarInset}）。
 * 信息条本身画什么，看 {@link InfoBarRenderer}；放哪几格、放在哪，看 {@link InfoBarLayout}；数据从哪来，看 {@link Telemetry}。</p>
 *
 * <h3>显示哪几项（项目所有者 2026-10-03）</h3>
 *
 * <p>试验项目（1.68.0），对所有人开放，默认关。显示哪几项在「设置 → 系统 → 车辆信号」里勾选（{@link #selection}）。
 * 没在实车验证过的信号（{@link Signal#usable()} 为假）只有开发者勾得了，也只对开发者算数；
 * 信息条那份快照只放能用的和勾了的信号（{@link Readings#usableOr}），不会把猜的东西画进录像。
 * 改了勾选马上生效：正在录的下一帧就按新的摆（{@link #selectionVersion}）。</p>
 *
 * <p>开着信息条时录制走 MediaCodec 路径（要用 GL 拼画面，和四宫格同一条规则，
 * 见 {@code AppConfig.shouldUseCodecRecording}）。</p>
 *
 * <h3>录像期间要读什么（{@link #recordingStarted} / {@link #recordingStopped}）</h3>
 *
 * <p>车辆信号的完整登记（{@link Telemetry#acquire}：浮点传感器、定位都开）；后视镜上的灯（{@link Telemetry#acquireLamps}）
 * 只在四盏里勾了至少一盏时读（{@link #wantsLamps}，项目所有者 2026-11-03：允许不勾，不勾的维持原来的转向灯），
 * 录着像改勾选马上跟着开、停。</p>
 */
public final class InfoBar {

    /** 信息条的高度（像素），和视频宽度无关。 */
    public static final int HEIGHT = 100;

    /** 勾选清单里经纬度那一项。 */
    public static final String POSITION = InfoBarLayout.POSITION_ITEM;

    /** 录像开着信息条时登记用的名字（车辆信号的完整登记、后视镜上的灯各一份）。 */
    private static final String RECORDING = "recording";

    private static final Object LOCK = new Object();
    /** 存着的勾选（含开发者模式关掉后不算数的那些）；null 表示还没从配置里读过。 */
    private static volatile Set<String> stored;
    private static volatile int version;
    /** 录像开着信息条（{@link #recordingStarted} 到 {@link #recordingStopped}）时是应用的 Context，不然 null。在 {@link #LOCK} 里碰。 */
    private static Context recording;

    private InfoBar() {
    }

    /** 这次录制要不要信息条（总开关）。 */
    public static boolean isOnForRecording(Context context) {
        return new AppConfig(context).isInfoBarEnabled();
    }

    /** 这个信号的勾选框能不能勾：能用的谁都能勾，没验证的只有开发者。 */
    public static boolean selectable(Signal signal) {
        return signal.usable() || DeveloperMode.isUnlocked();
    }

    /**
     * 信息条上放哪几项（信号名，加上 {@link #POSITION}）：勾了的、而且此刻算数的 ——
     * 开发者模式关掉后，之前勾的没验证的信号不再上信息条，勾选本身还留着。
     */
    public static Set<String> selection(Context context) {
        Set<String> effective = new LinkedHashSet<>();
        for (String name : stored(context)) {
            if (POSITION.equals(name)) {
                effective.add(name);
                continue;
            }
            Signal signal = signalNamed(name);
            if (signal != null && selectable(signal)) {
                effective.add(name);
            }
        }
        return effective;
    }

    /** 勾上或去掉一项，马上生效（正在录的信息条下一帧就换；录着像时后视镜上的灯读不读也跟着换）。 */
    public static void setSelected(Context context, String item, boolean selected) {
        synchronized (LOCK) {
            Set<String> next = new LinkedHashSet<>(stored(context));
            boolean changed = selected ? next.add(item) : next.remove(item);
            if (!changed) {
                return;
            }
            new AppConfig(context).setInfoBarItems(next);
            stored = next;
            version++;
            if (recording != null) {
                syncLamps(recording);
            }
        }
        Telemetry.get().selectionChanged();
    }

    /**
     * 录像开着信息条，开录时叫（{@code MultiCameraManager}）：登记要用车辆信号；勾了后视镜上的灯的话也登记读灯。重复叫无害。
     */
    public static void recordingStarted(Context context) {
        Context app = context.getApplicationContext();
        synchronized (LOCK) {
            recording = app;
            Telemetry.get().acquire(app, RECORDING);
            syncLamps(app);
        }
    }

    /** 不在录了（停录、开录时所有相机都没起来……）：两份登记都撤掉；没登记过也无害。 */
    public static void recordingStopped() {
        synchronized (LOCK) {
            recording = null;
            Telemetry.get().release(RECORDING);
            Telemetry.get().releaseLamps(RECORDING);
        }
    }

    /** 在 {@link #LOCK} 里：照此刻的勾选，录像这一份读不读后视镜上的灯。 */
    private static void syncLamps(Context app) {
        if (wantsLamps(selection(app))) {
            Telemetry.get().acquireLamps(app, RECORDING);
        } else {
            Telemetry.get().releaseLamps(RECORDING);
        }
    }

    /** 信息条用不用后视镜上的灯：车机设置服务那四盏（{@link Signal.Kind#SETTING}）勾了任意一盏。 */
    static boolean wantsLamps(Set<String> selection) {
        for (Signal s : Signal.values()) {
            if (s.kind == Signal.Kind.SETTING && selection.contains(s.name())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 勾选的版本：变了就要重新摆。开发者模式开关也算在里面 —— 它决定没验证的那几项算不算数。
     */
    public static int selectionVersion() {
        return version * 2 + (DeveloperMode.isUnlocked() ? 1 : 0);
    }

    private static Set<String> stored(Context context) {
        Set<String> current = stored;
        if (current == null) {
            synchronized (LOCK) {
                current = stored;
                if (current == null) {
                    current = new AppConfig(context).getInfoBarItems();
                    stored = current;
                }
            }
        }
        return current;
    }

    private static Signal signalNamed(String name) {
        try {
            return Signal.valueOf(name);
        } catch (IllegalArgumentException e) {
            // 存着的是以前版本的信号名，后来改名或拿掉了
            return null;
        }
    }
}
