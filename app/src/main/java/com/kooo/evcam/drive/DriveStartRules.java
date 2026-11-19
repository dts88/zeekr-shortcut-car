/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 calvinbui - https://github.com/calvinbui
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.drive;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.telemetry.Signal;

/**
 * 上车时切驾驶模式的规矩（issue 138），纯函数，{@code DriveStartRulesTest} 直接调；照着做的是 {@link DriveStart}。
 *
 * <ul>
 *   <li>每次上车只切一次：从算上车的那一刻起（{@link DriveStart}：亮屏那条边，或者进程起来时接上的那一次），先等
 *       {@link #SETTLE_MS}，车通电、挂着 P 挡就写；之后不再写，手动切换的不动。</li>
 *   <li>只在 P 挡写：读到别的挡位就是已经开走了，这一次上车不切（不在行驶中改油门的脾气）。车上实测过的那一次也是 P 挡、
 *       车速为零（澳洲 7X，PR 145）。</li>
 *   <li>车辆接口没连上、点火或挡位还读不到：等，最多等到 {@link #WINDOW_MS}。</li>
 *   <li>写了没成（车机不受理、读回没变）：隔 {@link #RETRY_MS} 再写，一共最多 {@link #MAX_WRITES} 次。</li>
 * </ul>
 */
final class DriveStartRules {

    /** 算上车之后先等这么久再写：车上实测成功的那一次是通电后一秒多写的。 */
    static final long SETTLE_MS = 2_000L;
    /** 从算上车起最多等这么久，过了这一次上车就不切了。 */
    static final long WINDOW_MS = 30_000L;
    /** 写了没成，隔这么久再写。 */
    static final long RETRY_MS = 3_000L;
    /** 一次上车最多写几次。 */
    static final int MAX_WRITES = 3;
    /** 写成之后隔这么久再看一眼还是不是那个模式，只记进黑匣子。 */
    static final long VERIFY_MS = 5_000L;

    /** 这一刻该怎样。 */
    enum Step {
        /** 再等等（还没等够、车辆信号还没就绪、刚写过没成）。 */
        WAIT,
        /** 写。 */
        WRITE,
        /** 已经挂出 P 挡：这一次上车不切了。 */
        LEFT_PARK,
        /** 等到头车辆信号也没就绪：这一次上车不切了。 */
        TIMED_OUT,
        /** 写满了次数都没成：这一次上车不切了。 */
        FAILED
    }

    private DriveStartRules() {
    }

    /** 设置里存的值（{@code SettingsRegistry.DRIVE_START_MODE}）→ 要切到的驾驶模式；关闭、不认识的是 0。 */
    static int target(String setting) {
        if (AppConfig.DRIVE_START_MODE_COMFORT.equals(setting)) {
            return Signal.DRIVE_MODE_COMFORT;
        }
        if (AppConfig.DRIVE_START_MODE_STANDARD.equals(setting)) {
            return Signal.DRIVE_MODE_STANDARD;
        }
        if (AppConfig.DRIVE_START_MODE_SPORT.equals(setting)) {
            return Signal.DRIVE_MODE_SPORT;
        }
        return 0;
    }

    /**
     * @param sinceStartMs     从算上车起过了多久
     * @param linkConnected    车辆接口连着
     * @param ignition         点火的原值（{@link Signal#IGNITION}）；读不到 null
     * @param gear             挡位字母（{@link Signal#GEAR}）；读不到 null
     * @param writes           这一次上车已经写过几次（都没成）
     * @param sinceLastWriteMs 上一次写完过了多久；没写过随便
     */
    static Step decide(long sinceStartMs, boolean linkConnected, Integer ignition, String gear, int writes,
                       long sinceLastWriteMs) {
        if (writes >= MAX_WRITES) {
            return Step.FAILED;
        }
        if (gear != null && !"P".equals(gear)) {
            return Step.LEFT_PARK;
        }
        if (sinceStartMs > WINDOW_MS) {
            return Step.TIMED_OUT;
        }
        if (sinceStartMs < SETTLE_MS || !linkConnected || !powered(ignition) || gear == null) {
            return Step.WAIT;
        }
        if (writes > 0 && sinceLastWriteMs < RETRY_MS) {
            return Step.WAIT;
        }
        return Step.WRITE;
    }

    /** 车通电了：点火 ON 或 DRIVING。ACC 或更低、没见过的码、读不到都不算。 */
    static boolean powered(Integer ignition) {
        return ignition != null && (ignition == Signal.IGNITION_ON || ignition == Signal.IGNITION_DRIVING);
    }

    /**
     * 进程起来、屏幕亮着时，算不算一次上车：上一个进程最后看到的是熄屏（人下车了，进程后来没了），或者车机真正开过机
     * （和「这一趟」同一个判法，规格 §2.5）。进程在行驶中重启的不算：上一次上车已经切过，之后手动切换的不动。
     */
    static boolean startsAtProcessStart(boolean leftWhileDark, boolean rebooted) {
        return leftWhileDark || rebooted;
    }
}
