package com.kooo.evcam.telemetry;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 谁在用车辆信号、来源该开到哪一层：纯逻辑（{@code TelemetryUsersTest}），{@link Telemetry} 照它开关来源。
 *
 * <h3>两种用的人（2026-10-12，熄屏跟着物理屏幕）</h3>
 *
 * <ul>
 *   <li><b>核心</b>（{@link #keepCore}）：熄屏判断（{@code screen.ScreenState}）在进程一起来时登记，进程活着就一直在，不注销。
 *       只要车辆接口的离散信号 —— 订阅 + 每 3 秒对账：屏幕电源事件、点火、哨兵、挡位都在里面。
 *       不读浮点传感器、不开定位（GPS 不为熄屏判断一直开着，{@code docs/lifecycle-spec.md} §2.4）。
 *       车辆信号要是只在录像时才读，「熄屏」会跟着录像停下而读不到、安卓又说亮着，录像被接回来、又读到熄屏，来回打转。</li>
 *   <li><b>完整</b>（{@link #acquire} / {@link #release}，按名字）：录像的信息条、「车辆信号」页、车辆状态面板……
 *       另外要浮点传感器（车速、转角、深度…… 每 200 ms 读一次）和定位（每 500 ms）。
 *       最后一个完整用户走了，浮点和定位停掉、它们的值清掉（下一次录像不画旧的车速和位置）；离散的照旧，核心还在用。</li>
 * </ul>
 *
 * <p>来源在有人用的时候跑：核心在，或者有完整用户。没有核心时（只有测试、或者进程起来之前）规则一样：最后一个人走了全停。</p>
 */
final class TelemetryUsers {

    /** 一次登记 / 注销之后来源要做的事。 */
    enum Change {
        NONE,
        /** 从没人到有人，只有核心：起车辆接口，不读浮点、不开定位 */
        START_CORE,
        /** 从没人到有人，第一个就是完整用户（核心还没登记）：车辆接口连浮点一起起，开定位 */
        START_FULL,
        /** 核心在跑，来了第一个完整用户：开浮点、开定位 */
        FLOATS_ON,
        /** 核心还在，最后一个完整用户走了：停浮点和定位、清掉它们的值，离散的不动 */
        FLOATS_OFF,
        /** 一个人都没了（没有核心时最后一个完整用户走了）：全停、快照清空 */
        STOP
    }

    private boolean core;
    private final Set<String> full = new LinkedHashSet<>();

    /** 核心登记；第二次无害。 */
    Change keepCore() {
        if (core) {
            return Change.NONE;
        }
        core = true;
        // 已经有完整用户：来源早就连浮点一起在跑了
        return full.isEmpty() ? Change.START_CORE : Change.NONE;
    }

    /** 完整用户登记；重复登记无害。 */
    Change acquire(String who) {
        boolean first = full.isEmpty();
        if (!full.add(who) || !first) {
            return Change.NONE;
        }
        return core ? Change.FLOATS_ON : Change.START_FULL;
    }

    /** 完整用户注销；没登记过也无害。 */
    Change release(String who) {
        if (!full.remove(who) || !full.isEmpty()) {
            return Change.NONE;
        }
        return core ? Change.FLOATS_OFF : Change.STOP;
    }

    /** 浮点传感器和定位该不该开：有完整用户。 */
    boolean full() {
        return !full.isEmpty();
    }

    boolean core() {
        return core;
    }

    /** 完整用户的名字，登记的先后。 */
    List<String> names() {
        return new ArrayList<>(full);
    }
}
