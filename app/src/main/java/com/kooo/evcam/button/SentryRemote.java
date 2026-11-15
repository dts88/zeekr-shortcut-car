/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import android.content.Context;

import com.kooo.evcam.R;

/**
 * 从「停车警报」那一侧开 / 关哨兵模式的那道门。
 *
 * <h3>为什么要多这一层</h3>
 *
 * <p>写车的东西全在 {@code button} 包里（{@link CarMoves}），{@code alarm} 包离它只差一个公开的入口。
 * 主界面那个键（{@link SentrySwitch}）会弹 Toast、会记「主界面点了」，那都是给人看的；
 * 从聊天里开哨兵没人看着屏幕，不要 Toast，也不要冒充有人按了键。</p>
 *
 * <p>这一层只做三件事：转给 {@link CarMoves}、结果翻成 {@link Outcome}、回调回到主线程。
 * 判断一条都不写在这里 —— 该不该开在 {@code alarm.GuardPolicy}，开没开成由写回读说了算。</p>
 *
 * <h3>A door from the alerts side into the car-writing side</h3>
 *
 * <p>Everything that writes to the car lives in {@code button}; the alarm package needs one public
 * entry. The on-screen key toasts and logs "the owner pressed it" — remote use has nobody looking at
 * the screen, so it must not pretend someone did. No policy here: whether to switch sentry on belongs
 * to {@code GuardPolicy}, and whether it worked is the car's read-back.</p>
 */
public final class SentryRemote {

    /** 写车的结果，和 {@link CarMoves.Result} 一一对应，名称不翻译。 */
    public enum Outcome {
        /** 读回是要的那个样子。The read-back says what we asked for. */
        DONE,
        /** 车机受理了，车没变：这台车多半没有这个功能。Accepted, no change. */
        NO_CHANGE,
        /** 车辆接口没连上、没有这个功能号、车机不受理。Not available. */
        UNAVAILABLE,
        /** 超级省电模式下不执行。Refused in power saving. */
        POWER_SAVING,
        /** 车没停稳。Not standing still. */
        MOVING,
        /** 没装这一项。Not fitted. */
        NOT_FITTED
    }

    /** 写完、读回之后（主线程）。After the write and its read-back, on the main thread. */
    public interface Callback {
        void onResult(Outcome outcome);
    }

    private SentryRemote() {
    }

    /** 开哨兵模式（已经是开 / 布防也算做成）。Switch sentry on; already on counts as done. */
    public static void on(Context context, Callback callback) {
        send(context, true, callback);
    }

    /**
     * 关哨兵模式。
     *
     * <p>布防（读数 2）时车机不受理 —— {@link CarMoves#sentryOff()} 只在读数是 1 的时候写。
     * 那不是出错，是这辆车的规矩：锁着的时候只能解一次锁再关。结果就是 {@link Outcome#UNAVAILABLE}。</p>
     *
     * Switch sentry off. An armed car (2) is refused by the car itself, so that comes back as
     * {@link Outcome#UNAVAILABLE} — a rule, not a failure.
     */
    public static void off(Context context, Callback callback) {
        send(context, false, callback);
    }

    private static void send(Context context, boolean on, Callback callback) {
        if (callback == null) {
            return;
        }
        Context app = context.getApplicationContext();
        CarMoves.run(app, on ? "sentry_remote_on" : "sentry_remote_off",
                on ? CarMoves.sentryOn() : CarMoves.sentryOff(),
                result -> callback.onResult(of(result)));
    }

    /**
     * 没做成时给车主看的那句话（和主界面那个键用的是同一批文案）。
     *
     * <p>{@code null} 表示做成了 —— 做成了不用说话，车上看得见。</p>
     *
     * The sentence to show when it did not work, from the same strings the on-screen key uses.
     * {@code null} means it worked: nothing to say, the car shows it.
     */
    public static String failureText(Context context, Outcome outcome) {
        if (outcome == null || outcome == Outcome.DONE) {
            return null;
        }
        Context app = context.getApplicationContext();
        return CarMoves.failureText(app, app.getString(R.string.smart_button_action_sentry),
                toResult(outcome));
    }

    /** {@link Outcome} 翻回车库那边的写法。Back to the shape {@link CarMoves} speaks. */
    static CarMoves.Result toResult(Outcome outcome) {
        if (outcome == null) {
            return CarMoves.Result.UNAVAILABLE;
        }
        switch (outcome) {
            case DONE:
                return CarMoves.Result.DONE;
            case NO_CHANGE:
                return CarMoves.Result.NO_CHANGE;
            case POWER_SAVING:
                return CarMoves.Result.POWER_SAVING;
            case MOVING:
                return CarMoves.Result.MOVING;
            case NOT_FITTED:
                return CarMoves.Result.NOT_FITTED;
            case UNAVAILABLE:
            default:
                return CarMoves.Result.UNAVAILABLE;
        }
    }

    static Outcome of(CarMoves.Result result) {
        if (result == null) {
            return Outcome.UNAVAILABLE;
        }
        switch (result) {
            case DONE:
                return Outcome.DONE;
            case NO_CHANGE:
                return Outcome.NO_CHANGE;
            case POWER_SAVING:
                return Outcome.POWER_SAVING;
            case MOVING:
                return Outcome.MOVING;
            case NOT_FITTED:
                return Outcome.NOT_FITTED;
            case UNAVAILABLE:
            default:
                return Outcome.UNAVAILABLE;
        }
    }
}
