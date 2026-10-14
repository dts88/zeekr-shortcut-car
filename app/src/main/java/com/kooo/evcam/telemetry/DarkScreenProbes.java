package com.kooo.evcam.telemetry;

import java.util.Locale;

/**
 * 找「车机把屏幕真的黑掉」对应哪个车辆信号：要记的那几项，和它们写进黑匣子的样子。<b>只记录，不改任何判断。</b>
 *
 * <h3>为什么要（2026-10-12，项目所有者同意先只加记录）</h3>
 *
 * <p>车主下车走开，中控屏真的黑了，安卓却还是「可交互」，熄屏广播约 4 分钟后下电才来（{@code screen.DisplayWatch} 说明里有细节）。
 * 车上试一次（录着像、下车走开），黑匣子里每一项什么时候变、变成什么，对着「屏幕真黑了」那一刻看。</p>
 *
 * <ul>
 *   <li>{@link #IDS}：信号表里没有的几个功能号（不带区域）。号码、名字照 zeekr-shortcut-lab 的信号手册和 0.4.0 的遍历
 *       （{@code docs/signals.md}、{@code docs/findings.md} §2.39、{@code docs/reports/2026-09-28-sweep-0.4.0.txt}）：
 *       停车时读到 0、1 或 255（未知），含义都没弄清。
 *       <b>不进信号表</b>：「系统信息」页、信息条都不变；{@link EcarxSource} 只是一起订上、对账时一起读，原值照记（255 也记）。
 *       这几个号的<b>可用状态</b>（回调 {@code onSupportedFunctionStatusChanged}，Lab §2.39：熄屏 / 上下电前后电源开关的可用状态
 *       active ↔ notactive，和屏幕电源事件 0 → 1 → 0 同一刻）也记，单独一项「名字.status」（{@link #STATUS}），只有回调、不去读。</li>
 *   <li>{@link #WATCHED}：信号表里已有的几项（屏幕电源事件、点火 / 用车状态 0x20259000、挡位、哨兵、主驾门、主驾座椅、主驾安全带），
 *       用它们自己的解法（{@link Signal#decode}），变了也记。屏幕电源事件原来在 {@link #IDS} 里，车主 2026-10-12 确认它就是
 *       「屏幕真黑了」之后进了信号表（{@link Signal#SCREEN_POWER_EVENT}，熄屏判断用它）；黑匣子里的名字和 0 / 1 的写法不变，
 *       只是读不到（255、读失败）现在写「?」—— 和信号表别的几项一样。</li>
 *   <li>{@link #RAW_WATCHED}（2.11.5）：信号表里已有、但照原值记的几项（原厂 360 显示状态、原厂画面弹出、泊车影像激活）：信号表的解法把几种值并成一种，
 *       这里要的是原值。</li>
 * </ul>
 *
 * <p>写成英文记号（和「第一轮读数」那一行一样小写），好和 Lab 手册里的常量名对上；纯函数（{@code DarkScreenProbesTest}）。</p>
 */
final class DarkScreenProbes {

    /** 每一项一个窗口里最多记几次变化，多出来的只数（{@code blackbox.ChangeTracker}）。 */
    static final int MAX_LINES_PER_WINDOW = 8;
    static final long WINDOW_MS = 5 * 60_000L;

    /** 功能号，不带区域；回调里的区域不是 0 的单独一项（{@link #name}）。 */
    static final int[] IDS = {
            0x20316800,   // IVehicle.SETTING_FUNC_SCREEN_DIS_POWER_EVENT
            0x20316900,   // IVehicle.SETTING_FUNC_SCREEN_HUD_POWER_EVENT
            0x2032A800,   // IVehicle.SETTING_FUNC_POWER_MODE_STATE
            0x21100100,   // IBcm.BCM_FUNC_POWER_ONOFF
            0x20240B00,   // IVehicle.SETTING_FUNC_DCDC_ACTVD
            0x20240A00    // IVehicle.SETTING_FUNC_HV_SYS_RLY_STS
    };

    /** 和 {@link #IDS} 一一对应。 */
    static final String[] NAMES = {
            "screen_dis_power_event",
            "screen_hud_power_event",
            "power_mode_state",
            "bcm_power_onoff",
            "dcdc_actvd",
            "hv_sys_rly_sts"
    };

    /**
     * 信号表里已有的、也跟着记的几项。点火这一项就是 Lab 的 {@code AVAILABLE_USGMODE}（0x20259000）。
     * 只放离散的：变了的那一行在回调、对账处理完时写，浮点的按节拍读、不经过那两处。
     * 屏幕电源事件放第一个：起点那一行（「车辆信号起点：」）里它排在信号表这几项的最前面。
     */
    static final Signal[] WATCHED = {
            Signal.SCREEN_POWER_EVENT,
            Signal.IGNITION,
            Signal.GEAR,
            Signal.SENTRY_MODE,
            Signal.DOOR_DRIVER,
            Signal.SEAT_DRIVER,
            Signal.BELT_DRIVER
    };

    /**
     * 信号表里已有、但变了照<b>原值</b>记的几项（项目所有者 2026-10-15 批准，2.11.5 第 D4 项；只记录）。名字、含义照 zeekr-shortcut-lab 的信号手册
     * （{@code docs/signals.md}、{@code docs/findings.md}）：
     *
     * <ul>
     *   <li>0x2031FE00 原厂 360 显示状态（{@code stock_360}）：2 没显示、1 原厂 360 画面、0 侧方小窗 —— 信号表把 0 和 2 都算「不在」；</li>
     *   <li>0x2031B200 原厂画面弹出（{@code stock_popup}，{@code CEM_SMALL_POP_RES}）：原厂 360 或侧方小窗弹着 1，收起来 0；</li>
     *   <li>0x23030100 泊车影像激活（{@code park_assist}）：挂 R 时 1，原厂 360 开着时也是 1。</li>
     * </ul>
     *
     * <p>为什么：熄屏放开座舱那一下环视断了的那两次（10-13 23:59、10-14 21:52），车机在 ACC 后约 30 秒自己有没有动原厂 360 / 环视
     * 不知道（rel_final §3、rel_hypotheses M4）。以前这几项只在「第一轮读数」里出现一次。和别的几项一样一项一个窗口限量（{@link #MAX_LINES_PER_WINDOW}）。</p>
     */
    static final Signal[] RAW_WATCHED = {
            Signal.STOCK_360,
            Signal.STOCK_POPUP,
            Signal.PARK_ASSIST
    };

    private DarkScreenProbes() {
    }

    /** 是不是照原值记的那几项之一（{@link #RAW_WATCHED}）。 */
    static boolean watchesRaw(Signal s) {
        for (Signal w : RAW_WATCHED) {
            if (w == s) {
                return true;
            }
        }
        return false;
    }

    /** 可用状态那一项的名字是功能号那一项的名字加上它：「bcm_power_onoff.status」。 */
    static final String STATUS = ".status";

    /**
     * 第 {@code index} 个号在区域 {@code zone} 上的那一项的名字。区域 0（Lab 遍历里这几个号都没有区域，回调按理都是 0）就是 {@link #NAMES} 里的；
     * 不是 0 的单独一项「screen_dis_power_event@0x1」：几个区域的值混成一项，看着就像来回跳，还把限量用完。对账读的是不带区域的值，算区域 0。
     */
    static String name(int index, int zone) {
        return zone == 0 ? NAMES[index] : NAMES[index] + "@0x" + Integer.toHexString(zone);
    }

    /** 可用状态写成它自己的名字（ECARX 的 {@code FunctionStatus}：active / notactive / notavailable / error）；没有是「?」。 */
    static String status(Object value) {
        return value == null ? "?" : String.valueOf(value);
    }

    /** 这个功能号是第几项；不是要记的号是 -1。 */
    static int indexOf(int id) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i] == id) {
                return i;
            }
        }
        return -1;
    }

    static boolean watches(Signal s) {
        for (Signal w : WATCHED) {
            if (w == s) {
                return true;
            }
        }
        return false;
    }

    /** 信号表里那几项在黑匣子里的名字：和「第一轮读数」一样，信号名小写。 */
    static String key(Signal s) {
        return s.name().toLowerCase(Locale.US);
    }

    /**
     * 功能号的原值：小的数照写十进制（0、1、255……），像 ECARX 枚举那样的大数写十六进制（0x2032A801）；读不到是「?」。
     */
    static String raw(Object value) {
        if (!(value instanceof Number)) {
            return "?";
        }
        int v = ((Number) value).intValue();
        if (v >= -0xFFFF && v <= 0xFFFF) {
            return String.valueOf(v);
        }
        return String.format(Locale.US, "0x%08X", v);
    }

    /** 信号表里那几项归一后的值（{@link Signal#decode}）写成记号；没数据（含占位值）是「?」。 */
    static String text(Signal s, Object decoded) {
        if (decoded == null) {
            return "?";
        }
        switch (s.format) {
            case SCREEN:
                // 照原值写 0 / 1（它还是原值那一项时就这么写，「screen_power_event 0 → 1」老脚本按这个找）
                return Boolean.TRUE.equals(decoded) ? "1" : "0";
            case GEAR:
                return String.valueOf(decoded);
            case IGNITION: {
                int code = decoded instanceof Integer ? (Integer) decoded : 0;
                if (code == Signal.IGNITION_ACC) {
                    return "ACC";
                }
                if (code == Signal.IGNITION_ON) {
                    return "ON";
                }
                if (code == Signal.IGNITION_DRIVING) {
                    return "DRIVING";
                }
                return raw(decoded);
            }
            case SENTRY: {
                int code = decoded instanceof Integer ? (Integer) decoded : -1;
                return code == 0 ? "off" : code == 1 ? "on" : code == 2 ? "armed" : String.valueOf(decoded);
            }
            case DOOR:
                return Boolean.TRUE.equals(decoded) ? "open" : "closed";
            case SEAT:
                return Boolean.TRUE.equals(decoded) ? "occupied" : "empty";
            case BELT:
                return Boolean.TRUE.equals(decoded) ? "buckled" : "unbuckled";
            default:
                return String.valueOf(decoded);
        }
    }
}
