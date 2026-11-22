/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screen;

/**
 * 遥控挪车（推断）：熄屏、没有人的证据的时候车挂了挡，主驾座椅读到没人 —— 车主拿手机在车外挪车。
 *
 * <p>项目所有者 2026-11-22：「app 简单化处理这个事件，继续按照『人没在车上』处理即可，但可以标注和记录、学习此情况下系统发生的变化。」
 * <b>只记录，不改任何判断</b>：熄屏、停车、相机、录像照旧。{@link ScreenState} 在主线程上喂读数，照这里的答案写黑匣子：
 * 开头一行，完了一行汇总；中间每一项信号变了照旧各写一行（「车辆信号：…」）。</p>
 *
 * <ul>
 *   <li>开始：熄屏、安卓亮着，挡位读到不在 P，主驾座椅读到没人（读不到不算：可能是人在开、只是座椅那一项没读回来）。</li>
 *   <li>完了：有了人的证据（亮屏）；车机要睡；挡位回到 P、点火掉到 ACC 或更低满 {@link #QUIET_MS}（算停稳的那一刻完 ——
 *       读数没变时没人叫这里，晚到的那一次照停稳的那一刻算）。</li>
 *   <li>汇总：多久，挂了几次挡（D、R、N 各几次），点火通电、断电各几次，哨兵模式开头和完了时是什么。</li>
 * </ul>
 *
 * <p>纯逻辑（{@code RemoteMoveTest}），只在主线程上用。</p>
 */
final class RemoteMove {

    /** 挡位回到 P、点火掉下来满这么久算停稳了。 */
    static final long QUIET_MS = 60_000L;

    /** 一批读数之后的变化。 */
    enum Change { NONE, STARTED, ENDED }

    /** 怎么完的。 */
    enum End { PERSON, SLEEP, QUIET }

    private boolean active;
    private long startedAt;
    private long endedAt;
    private End end;
    /** 挡位回到 P、点火掉下来的那一刻；0 = 没停稳。 */
    private long stillSince;
    private String gear;
    private Boolean powered;
    private int drive;
    private int reverse;
    private int neutral;
    private int powerOn;
    private int powerOff;
    private Integer sentryAtStart;
    private Integer sentryNow;

    /**
     * 一批读数（车辆接口连着时）。
     *
     * @param dark        熄屏（没有人在车上）
     * @param androidOff  安卓熄屏了（车机要睡）
     * @param gear        挡位（P / R / N / D）；读不到是 null
     * @param seatTaken   主驾座椅读到有人；读不到是 null
     * @param ignitionLow 点火在 ACC 或更低；读不到是 null
     * @param sentry      哨兵模式原值（0 关、1 开、2 布防）；读不到是 null
     */
    Change readings(boolean dark, boolean androidOff, String gear, Boolean seatTaken, Boolean ignitionLow,
                    Integer sentry, long now) {
        if (!active) {
            if (!dark || androidOff || gear == null || "P".equals(gear) || !Boolean.FALSE.equals(seatTaken)) {
                return Change.NONE;
            }
            start(gear, ignitionLow, sentry, now);
            return Change.STARTED;
        }
        if (stillSince != 0 && now - stillSince >= QUIET_MS) {
            return finish(End.QUIET, stillSince);
        }
        if (!dark) {
            return finish(End.PERSON, now);
        }
        if (androidOff) {
            return finish(End.SLEEP, now);
        }
        if (gear != null && !gear.equals(this.gear)) {
            count(gear);
            this.gear = gear;
        }
        if (ignitionLow != null) {
            boolean on = !ignitionLow;
            if (powered != null && powered != on) {
                if (on) {
                    powerOn++;
                } else {
                    powerOff++;
                }
            }
            powered = on;
        }
        if (sentry != null) {
            sentryNow = sentry;
        }
        boolean still = "P".equals(this.gear) && Boolean.FALSE.equals(powered);
        if (!still) {
            stillSince = 0;
        } else if (stillSince == 0) {
            stillSince = now;
        }
        return Change.NONE;
    }

    /** 车机要睡（安卓熄屏那一下，{@link ScreenState} 叫）：在算的这一段完了。 */
    Change headUnitSleeps(long now) {
        if (!active) {
            return Change.NONE;
        }
        return finish(stillSince != 0 && now - stillSince >= QUIET_MS ? End.QUIET : End.SLEEP,
                stillSince != 0 && now - stillSince >= QUIET_MS ? stillSince : now);
    }

    boolean active() {
        return active;
    }

    /** 开头那一下挂的挡。 */
    String gear() {
        return gear;
    }

    End end() {
        return end;
    }

    /** 这一段多久（毫秒）：完了的用完了那一刻，没完的到 {@code now}。 */
    long durationMs(long now) {
        return (active ? now : endedAt) - startedAt;
    }

    /** 停稳以后又过了多久才写汇总（毫秒）；不是停稳完的是 0。 */
    long quietLagMs(long now) {
        return end == End.QUIET ? Math.max(0, now - endedAt) : 0;
    }

    int drive() {
        return drive;
    }

    int reverse() {
        return reverse;
    }

    int neutral() {
        return neutral;
    }

    int powerOn() {
        return powerOn;
    }

    int powerOff() {
        return powerOff;
    }

    /** 哨兵模式开头时的（黑匣子「车辆信号」行的写法：off / on / armed，读不到 ?）。 */
    String sentryAtStart() {
        return sentryText(sentryAtStart);
    }

    /** 哨兵模式最后读到的。 */
    String sentryNow() {
        return sentryText(sentryNow);
    }

    static String sentryText(Integer code) {
        if (code == null) {
            return "?";
        }
        switch (code) {
            case 0:
                return "off";
            case 1:
                return "on";
            case 2:
                return "armed";
            default:
                return String.valueOf(code);
        }
    }

    private void start(String gear, Boolean ignitionLow, Integer sentry, long now) {
        active = true;
        startedAt = now;
        endedAt = 0;
        end = null;
        stillSince = 0;
        this.gear = gear;
        powered = ignitionLow == null ? null : !ignitionLow;
        drive = 0;
        reverse = 0;
        neutral = 0;
        powerOn = 0;
        powerOff = 0;
        count(gear);
        sentryAtStart = sentry;
        sentryNow = sentry;
    }

    private void count(String gear) {
        switch (gear) {
            case "D":
                drive++;
                break;
            case "R":
                reverse++;
                break;
            case "N":
                neutral++;
                break;
            default:
                break;
        }
    }

    private Change finish(End how, long at) {
        active = false;
        end = how;
        endedAt = Math.max(startedAt, at);
        return Change.ENDED;
    }
}
