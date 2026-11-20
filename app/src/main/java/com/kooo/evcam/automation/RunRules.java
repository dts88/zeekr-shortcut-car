/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 calvinbui - https://github.com/calvinbui
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

import com.kooo.evcam.telemetry.Signal;

/**
 * 一次触发里每个动作怎么走的规矩，纯函数，{@code RunRulesTest} 直接调；照着做的是 {@link Automations}。
 * 数和判法照 Calvin 的 PR 261（上车切驾驶模式、空调重新打开后恢复、陡坡缓降能用时打开，三套合成这一套）。
 *
 * <ul>
 *   <li>上车：从算上车起先等 {@link #SETTLE_MS} 再做（车上实测成功的那一次是通电后一秒多写的）。</li>
 *   <li>车辆接口没连上、车没通电、要用的读数还读不到：等，最多等到 {@link #WINDOW_MS}，过了这一次算了。
 *       要等能用的动作（陡坡缓降）不受这个时限，等到这一次结束。</li>
 *   <li>只在 P 挡写的（驾驶模式）：读到别的挡就是已经开走了，这一次不写。</li>
 *   <li>写了没成（车机不受理、读回没变）：隔 {@link #RETRY_MS} 再写，一共最多 {@link #MAX_WRITES} 次。</li>
 * </ul>
 */
final class RunRules {

    /** 上车之后先等这么久。 */
    static final long SETTLE_MS = 2_000L;
    /** 从触发起最多等这么久就绪、写第一次。 */
    static final long WINDOW_MS = 30_000L;
    /** 写了没成，隔这么久再写。 */
    static final long RETRY_MS = 3_000L;
    /** 一个动作一次最多写几次。 */
    static final int MAX_WRITES = 3;
    /** 等的时候多久再看一次。 */
    static final long TICK_MS = 500L;
    /** 每秒读一次的那几样（车辆状态、能不能用）多久读一次。 */
    static final long WATCH_MS = 1_000L;
    /** 自动化写的值，这么久里读到不算车辆状态的变化（不连锁）。 */
    static final long OWN_WRITE_MS = 10_000L;

    /** 一个动作此刻该怎样。 */
    enum Step {
        /** 再等等。 */
        WAIT,
        /** 问一次能不能用。 */
        ASK,
        /** 写。 */
        WRITE,
        /** 只在 P 挡写的，已经挂出 P 挡：这一次不写。 */
        LEFT_PARK,
        /** 等到头还没就绪：这一次不写。 */
        TIMED_OUT,
        /** 写满了次数都没成。 */
        FAILED
    }

    private RunRules() {
    }

    /**
     * @param sinceStartMs     从触发起过了多久
     * @param ready            车辆接口连着、车通电（上车的还要过了 {@link #SETTLE_MS}）
     * @param parkOnly         只在 P 挡写
     * @param gear             挡位字母，读不到 null
     * @param waitsAvailable   要等能用
     * @param available        能用已经确认（连着两次）
     * @param sinceAskMs       上一次问能不能用过了多久；没问过随便大
     * @param writes           已经写过几次（都没成）
     * @param sinceLastWriteMs 上一次写完过了多久；没写过随便
     */
    static Step decide(long sinceStartMs, boolean ready, boolean parkOnly, String gear, boolean waitsAvailable,
                       boolean available, long sinceAskMs, int writes, long sinceLastWriteMs) {
        if (writes >= MAX_WRITES) {
            return Step.FAILED;
        }
        if (parkOnly && gear != null && !"P".equals(gear)) {
            return Step.LEFT_PARK;
        }
        if (!waitsAvailable && writes == 0 && sinceStartMs > WINDOW_MS) {
            return Step.TIMED_OUT;
        }
        if (!ready || (parkOnly && gear == null)) {
            return Step.WAIT;
        }
        if (waitsAvailable && !available) {
            return sinceAskMs >= WATCH_MS ? Step.ASK : Step.WAIT;
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
     * 进程起来、屏幕亮着时，算不算一次上车：在等下一次上车（上一个进程最后看到的是熄屏，人下车了，进程后来没了），或者车机
     * 真正开过机（和「这一趟」同一个判法，规格 §2.5）。进程在行驶中重启的不算（Calvin，PR 261）。
     */
    static boolean startsAtProcessStart(boolean awaitingNextDrive, boolean rebooted) {
        return awaitingNextDrive || rebooted;
    }
}
