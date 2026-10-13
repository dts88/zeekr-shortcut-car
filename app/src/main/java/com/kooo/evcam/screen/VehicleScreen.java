package com.kooo.evcam.screen;

import com.kooo.evcam.telemetry.Signal;

import java.util.Locale;

/**
 * 车辆信号说中控屏黑没黑（设计 v3 §1.2，项目所有者 2026-10-12 D1）。纯函数，{@code VehicleScreenTest} 直接调。
 *
 * <h3>为什么要它</h3>
 *
 * <p>这台 7X 上安卓的熄屏广播、{@code isInteractive} 只在车机下电时才变（车主下车几分钟后，先 U 盘写不进，再熄屏，
 * 再进程被结束）；屏幕真黑的那一刻安卓还说「亮着」。屏幕真黑看得到的是车辆信号（车主 2026-10-12 对着当天三次熄屏、
 * 两次亮屏确认）：</p>
 *
 * <ul>
 *   <li>黑：屏幕电源事件（{@link Signal#SCREEN_POWER_EVENT}）0 → 1，点火 ON → ACC，同一毫秒；</li>
 *   <li>亮：开车门，点火 ACC → ON 先到，屏幕电源事件 1 → 0 晚 40–100 ms —— 两项「且」起来，第一下就判成亮；</li>
 *   <li>之后点火 ACC → 0x00200102（名字不知道，多半是 OFF，只按原值记），屏幕电源事件一直是 1。</li>
 * </ul>
 *
 * <h3>规则</h3>
 *
 * <ul>
 *   <li>屏幕电源事件：1 黑、0 亮，别的（255 读不到、占位）不知道（解码在 {@link Signal}）；</li>
 *   <li>点火「ACC 或更低」= 原值在 [{@link #IGNITION_LOW_MIN}, {@link Signal#IGNITION_ACC}] 里（白名单，项目所有者定的读法）；
 *       ON、DRIVING 是高；别的原值、读不到都算不知道 —— 没见过的码（比如启动中）不拿来判黑；</li>
 *   <li>两项都知道：黑 = 事件是 1 且点火低；只知道一项就看那一项；都不知道：null（只看安卓，D1）。</li>
 * </ul>
 *
 * <p>黑匣子里两项的写法（{@link #eventText}、{@link #ignitionText}）和车辆信号那几行（{@code DarkScreenProbes}）一样：
 * 事件写 0 / 1，点火写 ACC / ON / DRIVING，别的照原值写十六进制（0x00200102），读不到是「?」。</p>
 */
public final class VehicleScreen {

    /** 点火「ACC 或更低」的下限（含）：0x00200100 起到 ACC（0x00200104）。 */
    static final int IGNITION_LOW_MIN = 0x00200100;

    private VehicleScreen() {
    }

    /**
     * 点火在不在「ACC 或更低」：在 [{@link #IGNITION_LOW_MIN}, ACC] 里是 TRUE，ON / DRIVING 是 FALSE，
     * 别的原值、读不到是 null。
     */
    static Boolean ignitionLow(Integer ignition) {
        if (ignition == null) {
            return null;
        }
        int code = ignition;
        if (code >= IGNITION_LOW_MIN && code <= Signal.IGNITION_ACC) {
            return Boolean.TRUE;
        }
        if (code == Signal.IGNITION_ON || code == Signal.IGNITION_DRIVING) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * 车辆说屏幕黑没黑：TRUE 黑，FALSE 亮，null 说不上来。
     *
     * @param screenPowerEvent 屏幕电源事件解码后的值（{@link Signal#SCREEN_POWER_EVENT}：TRUE = 1，FALSE = 0，null 读不到）
     * @param ignition         点火的原值（{@link Signal#IGNITION}），null 读不到
     */
    public static Boolean verdict(Boolean screenPowerEvent, Integer ignition) {
        Boolean low = ignitionLow(ignition);
        if (screenPowerEvent != null && low != null) {
            return screenPowerEvent && low;
        }
        if (screenPowerEvent != null) {
            return screenPowerEvent;
        }
        return low;
    }

    /** 屏幕电源事件在黑匣子里的写法：1 / 0，读不到「?」。 */
    public static String eventText(Boolean event) {
        return event == null ? "?" : event ? "1" : "0";
    }

    /** 点火在黑匣子里的写法：ACC / ON / DRIVING，别的照原值写（0x00200102），读不到「?」。 */
    public static String ignitionText(Integer ignition) {
        if (ignition == null) {
            return "?";
        }
        int code = ignition;
        if (code == Signal.IGNITION_ACC) {
            return "ACC";
        }
        if (code == Signal.IGNITION_ON) {
            return "ON";
        }
        if (code == Signal.IGNITION_DRIVING) {
            return "DRIVING";
        }
        if (code >= -0xFFFF && code <= 0xFFFF) {
            return String.valueOf(code);
        }
        return String.format(Locale.US, "0x%08X", code);
    }

    /** 一项从什么变成什么：变了是「 0 → 1」，没变是「=1」（跟在名字后面：「screen_power_event 0 → 1」）。 */
    static String change(String before, String after) {
        return before.equals(after) ? "=" + after : " " + before + " → " + after;
    }
}
