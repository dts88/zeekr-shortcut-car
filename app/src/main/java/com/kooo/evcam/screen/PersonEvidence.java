/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screen;

/**
 * 有人的证据（黑着时亮屏要它，{@link ScreenTransitions#verdict}）：最近一次读到车门开着、人最近一次碰应用，各算
 * {@link ScreenTransitions#EVIDENCE_FRESH_MS}。纯逻辑，{@code ScreenTransitionsTest} 直接走；{@link ScreenState} 在主线程上写，
 * 两个时刻哪个线程都能读（诊断报告）。
 *
 * <p>熄屏（E1）那一下之前的都作废：下车时开过的车门不算回来的证据。熄屏那一下还开着的车门（车门信号比屏幕、点火晚到一点，
 * 或者车门就那么开着）也不算，要先读到关上、再读到开着才算。</p>
 */
final class PersonEvidence {

    /** 最近一次算数的「车门开着」的时刻（elapsedRealtime）；0 = 没有。 */
    private volatile long doorAtMs;
    /** 人最近一次碰应用的时刻（elapsedRealtime）；0 = 没有。 */
    private volatile long touchAtMs;
    /** 熄屏那一下就开着、之后还没读到关上过：这段时间里读到开着不算。 */
    private boolean doorHeld;

    /**
     * 一批车辆读数（车辆接口连着时才叫）：四扇侧门有一扇读到开着没有。
     *
     * @param open 有一扇开着（都关着、读不到是 false）
     */
    void doors(boolean open, long now) {
        if (!open) {
            doorHeld = false;
        } else if (!doorHeld) {
            doorAtMs = now;
        }
    }

    /** 人碰了应用。 */
    void touched(long now) {
        touchAtMs = now;
    }

    /**
     * 熄屏（E1）：之前的证据作废。
     *
     * @param doorOpenNow 这一下车门还读到开着（车辆接口没连着是 false）
     */
    void wentDark(boolean doorOpenNow) {
        doorAtMs = 0;
        touchAtMs = 0;
        doorHeld = doorOpenNow;
    }

    /** 刚拉开过车门。 */
    boolean door(long now) {
        return fresh(doorAtMs, now);
    }

    /** 刚碰过应用。 */
    boolean touch(long now) {
        return fresh(touchAtMs, now);
    }

    /** 人最后一次碰应用的时刻；0 = 没有（亮着时读不到的时限从它重新算）。 */
    long touchAtMs() {
        return touchAtMs;
    }

    /** 多久以前读到车门开着（毫秒）；没有是 -1。 */
    long doorAgoMs(long now) {
        long at = doorAtMs;
        return at > 0 ? now - at : -1;
    }

    /** 多久以前碰过应用（毫秒）；没有是 -1。 */
    long touchAgoMs(long now) {
        long at = touchAtMs;
        return at > 0 ? now - at : -1;
    }

    private static boolean fresh(long atMs, long now) {
        return atMs > 0 && now - atMs <= ScreenTransitions.EVIDENCE_FRESH_MS;
    }
}
