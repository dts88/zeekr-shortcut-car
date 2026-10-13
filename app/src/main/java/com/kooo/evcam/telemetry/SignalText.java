package com.kooo.evcam.telemetry;

import android.content.Context;

import com.kooo.evcam.R;

import java.util.Locale;

/**
 * 信号的值写成人话。系统信息页和信息条上的文字格用的是这一份 —— 两处写的一样。
 */
public final class SignalText {

    private SignalText() {
    }

    /** 归一后的值（{@link Signal#decode}）→ 字；没数据是「—」。 */
    public static String text(Context ctx, Signal s, Object v) {
        if (v == null) {
            return ctx.getString(R.string.vi_v_none);
        }
        switch (s.format) {
            case ON_OFF:
            case LEVEL:
                return pick(ctx, v, R.string.vi_v_on, R.string.vi_v_off);
            case DOOR:
                return pick(ctx, v, R.string.vi_v_open, R.string.vi_v_closed);
            case BELT:
                return pick(ctx, v, R.string.vi_v_buckled, R.string.vi_v_unbuckled);
            case SEAT:
                return pick(ctx, v, R.string.vi_v_occupied, R.string.vi_v_empty);
            case SHOWN:
            case POPUP:
                return pick(ctx, v, R.string.vi_v_shown, R.string.vi_v_hidden);
            case SCREEN:
                // 屏幕说熄屏 / 亮屏（术语表），不说开 / 关：「中控屏：开」读起来是亮着，实际 1 是黑了
                return pick(ctx, v, R.string.vi_v_screen_off, R.string.vi_v_screen_on);
            case MIRROR_DIP: {
                int code = v instanceof Integer ? (Integer) v : -1;
                return ctx.getString(code == Signal.MIRROR_TILTING ? R.string.vi_v_mirror_tilting
                        : code == Signal.MIRROR_DOWN ? R.string.vi_v_mirror_down
                        : code == Signal.MIRROR_RETURNING ? R.string.vi_v_mirror_returning
                        : R.string.vi_v_mirror_normal);
            }
            case SENTRY: {
                int code = v instanceof Integer ? (Integer) v : -1;
                return ctx.getString(code == 2 ? R.string.vi_v_armed : code == 1 ? R.string.vi_v_on : R.string.vi_v_off);
            }
            case DAY_NIGHT:
                return ctx.getString(Integer.valueOf(Signal.NIGHT).equals(v) ? R.string.vi_v_night : R.string.vi_v_day);
            case INDICATOR: {
                int code = v instanceof Integer ? (Integer) v : -1;
                return ctx.getString(code == 1 ? R.string.vi_v_left : code == 2 ? R.string.vi_v_right
                        : code == 3 ? R.string.vi_v_hazard : R.string.vi_v_off);
            }
            case LIGHT_SWITCH: {
                int code = v instanceof Integer ? (Integer) v : -1;
                if (code == 0) {
                    return ctx.getString(R.string.vi_v_off);
                }
                if (code == Signal.LIGHT_SWITCH_POSITION) {
                    return ctx.getString(R.string.vi_v_light_position);
                }
                if (code == Signal.LIGHT_SWITCH_LOW_BEAM) {
                    return ctx.getString(R.string.vi_v_light_low);
                }
                if (code == Signal.LIGHT_SWITCH_AUTO) {
                    return ctx.getString(R.string.vi_v_light_auto);
                }
                return String.format(Locale.US, "0x%08X", code);
            }
            case IGNITION: {
                int code = v instanceof Integer ? (Integer) v : -1;
                if (code == Signal.IGNITION_ACC) {
                    return ctx.getString(R.string.vi_v_ign_acc);
                }
                if (code == Signal.IGNITION_ON) {
                    return ctx.getString(R.string.vi_v_ign_on);
                }
                if (code == Signal.IGNITION_DRIVING) {
                    return ctx.getString(R.string.vi_v_ign_driving);
                }
                return String.format(Locale.US, "0x%08X", code);
            }
            case GEAR:
                return String.valueOf(v);
            case KMH:
            case MPS:
                return number(v, "%.0f km/h");
            case KM:
                return number(v, "%.0f km");
            case PERCENT:
            case PERCENT_RAW:
            case BRAKE:
                return number(v, "%.0f %%");
            case DEGREES:
                return number(v, "%.0f°");
            case CELSIUS:
                return number(v, "%.1f °C");
            default:
                return String.valueOf(v);
        }
    }

    /** 经纬度那一项：「纬度, 经度」，没定位是「—」。 */
    public static String position(Context ctx, Double latitude, Double longitude) {
        if (latitude == null || longitude == null) {
            return ctx.getString(R.string.vi_v_none);
        }
        return String.format(Locale.US, "%.6f, %.6f", latitude, longitude);
    }

    private static String pick(Context ctx, Object v, int whenTrue, int whenFalse) {
        return ctx.getString(Boolean.TRUE.equals(v) ? whenTrue : whenFalse);
    }

    private static String number(Object v, String pattern) {
        return v instanceof Float ? String.format(Locale.US, pattern, (Float) v) : String.valueOf(v);
    }
}
