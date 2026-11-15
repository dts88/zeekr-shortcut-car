/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import com.kooo.evcam.telemetry.Signal;

/**
 * 车辆信号里和停车警报有关的那几个读数怎么解释 —— 纯函数，便于单测。
 *
 * <h3>「布防」就是「锁着」</h3>
 *
 * <p>{@link Signal#SENTRY_MODE} 是 0 关、1 开、2 <b>布防</b>。平台笔记（{@code docs/zeekr-platform-notes.md}）
 * 记的是：开哨兵 0 → 1；<b>锁车</b>一分半后 1 → 2；<b>解锁</b> 2 → 1（比 0x20240300 晚 10 毫秒）；挂 D 1 → 0。
 * 所以「车主回来了」在总线上就是 2 → 1 —— 车主还没走到车边、屏幕和车灯还没亮，它已经先说了。</p>
 *
 * <p>警报只认 2。这不是保守，是唯一分得开两种情况的读法：</p>
 *
 * <ul>
 *   <li>有人碰车：车还锁着，2 不动，车灯 / 屏幕 / 双闪亮起来 —— 发。</li>
 *   <li>车主自己回来：解锁那一下 2 → 1，之后屏幕才亮、车灯才亮 —— 不发。</li>
 * </ul>
 *
 * <p>另有 {@code 0x20240300}（VSTD_ALRM_STS）据称是「锁车后进入防盗」，解锁时 1 → 0，但平台笔记里
 * 只有一次观察、主项目明确没采用。这里也不采用；等到有交叉验证再说。</p>
 *
 * How to interpret the few vehicle-signal readings that matter to the parking alarm; pure
 * functions, easy to unit test.
 *
 * <h3>"Armed" means "locked"</h3>
 *
 * <p>{@link Signal#SENTRY_MODE} is 0 off, 1 on, 2 <b>armed</b>. The platform notes
 * ({@code docs/zeekr-platform-notes.md}) record: turning sentry on 0 → 1; <b>locking the car</b>
 * and a minute and a half later 1 → 2; <b>unlocking</b> 2 → 1 (10 milliseconds after 0x20240300);
 * shifting into D 1 → 0. So "the owner is back" appears on the bus as 2 → 1; before the owner
 * even reaches the car, before the screen and lights come on, it has already said so.</p>
 *
 * <p>The alarm only accepts 2. That is not conservatism; it is the only reading that separates the
 * two cases:</p>
 *
 * <ul>
 *   <li>Someone touches the car: it is still locked, 2 does not move, and the lamps / screen /
 *       hazard lights come on; send.</li>
 *   <li>The owner returns: the unlock moment is 2 → 1, and only after that do the screen and
 *       lamps come on; do not send.</li>
 * </ul>
 *
 * <p>There is also {@code 0x20240300} (VSTD_ALRM_STS), reportedly "enters anti-theft after
 * locking" with 1 → 0 on unlock, but the platform notes have only one observation and the main
 * project explicitly does not use it. This code does not use it either; revisit once there is
 * cross-validation.</p>
 */
public final class SentryFacts {

    /** {@link Signal#SENTRY_MODE} 里「布防」的值：锁车之后才有。
     *
     * The "armed" value in {@link Signal#SENTRY_MODE}: present only after locking.
     */
    public static final int SENTRY_ARMED = 2;

    /** 屏幕电源事件：0 是亮着。
     *
     * Screen power event: 0 means lit.
     */
    public static final int SCREEN_LIT = 0;

    /** 屏幕电源事件：1 是黑着。
     *
     * Screen power event: 1 means dark.
     */
    public static final int SCREEN_DARK = 1;

    private SentryFacts() {
    }

    /**
     * 车现在是「锁着、布防」吗。
     *
     * @param sentryMode {@link Signal#SENTRY_MODE} 的读数，读不到为 {@code null}
     * @return 只有确实读到 {@value #SENTRY_ARMED} 才是真 —— 读不到一律当真不了
     *
     * Is the car currently "locked, armed"?
     *
     * @param sentryMode reading of {@link Signal#SENTRY_MODE}; {@code null} when unavailable
     * @return true only when the reading actually equals {@value #SENTRY_ARMED}; an unavailable
     *         reading is never treated as true
     */
    public static boolean armed(Integer sentryMode) {
        return sentryMode != null && sentryMode == SENTRY_ARMED;
    }

    /**
     * 中控屏亮着没有。
     *
     * @param screenPowerEvent {@link Signal#SCREEN_POWER_EVENT} 的读数：0 亮着、1 黑、255 读不到
     * @return 读不到（255 / null）为 {@code null}，不当成任何一个状态
     *
     * Is the center screen lit?
     *
     * @param screenPowerEvent reading of {@link Signal#SCREEN_POWER_EVENT}: 0 lit, 1 dark, 255
     *        unavailable
     * @return {@code null} when unavailable (255 / null); never treated as either state
     */
    public static Boolean screenLit(Integer screenPowerEvent) {
        if (screenPowerEvent == null) {
            return null;
        }
        if (screenPowerEvent == SCREEN_LIT) {
            return Boolean.TRUE;
        }
        return screenPowerEvent == SCREEN_DARK ? Boolean.FALSE : null;
    }

    /**
     * 车外有没有灯自己亮起来（日行灯、前后位置灯、近光，任一为亮）。
     *
     * @return 四个都读不到为 {@code null}
     *
     * Whether any exterior lamp has come on by itself (daytime running light, front/rear position
     * lamp, or low beam; any one of them lit).
     *
     * @return {@code null} when all four are unavailable
     */
    public static Boolean lampsOn(Boolean drl, Boolean frontPosition, Boolean rearPosition,
                                  Boolean lowBeam) {
        if (drl == null && frontPosition == null && rearPosition == null && lowBeam == null) {
            return null;
        }
        return Boolean.TRUE.equals(drl) || Boolean.TRUE.equals(frontPosition)
                || Boolean.TRUE.equals(rearPosition) || Boolean.TRUE.equals(lowBeam);
    }

    /** 上升沿：这一下才亮起来（之前不是亮的）。
     *
     * Rising edge: it came on just now (it was not on before).
     */
    public static boolean rising(Boolean now, Boolean before) {
        return Boolean.TRUE.equals(now) && !Boolean.TRUE.equals(before);
    }
}
