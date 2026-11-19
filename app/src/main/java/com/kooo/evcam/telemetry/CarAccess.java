/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.telemetry;

/**
 * 车辆接口的读写（{@link Telemetry#carAccess}）：只有 {@code button.CarMoves} 用（灵动贴的动作，项目所有者 2026-10-30、11-04；
 * 上车时切驾驶模式，issue 138）。
 *
 * <p>都是同步的 binder 调用，可以在任何线程上叫，但会等车机回话：别在主线程上叫。读不到、写不进返回 null / false，不抛。
 * 车机说「受理」不等于做到了（Lab findings §2.48、§2.50）：要读回来看。</p>
 */
public interface CarAccess {

    /** 不带区域读一个功能号；读不到是 null。 */
    Integer read(int id);

    /** 带区域读一个功能号；读不到（或者这台车机没有带区域的读法）是 null。 */
    Integer read(int id, int zone);

    /** 不带区域写一个功能号：车机受理了返回 true。 */
    boolean write(int id, int value);

    /** 带区域写一个功能号：车机受理了返回 true（这台车机没有带区域的写法也是 false）。 */
    boolean write(int id, int zone, int value);

    Float readFloat(int id, int zone);

    boolean writeFloat(int id, int zone, float value);

    Integer config(int n);

    /**
     * 车机说这个号在这个区域上现在能不能用（ECARX 的 {@code isFunctionSupported}：active / notactive / notavailable / error）；
     * 问不了（这台车机上没有这个方法、出了错）是 null。关门照 Lab findings §2.93：不是 active 不写。
     */
    String status(int id, int zone);

    /** 一个传感器现在的值；读不到（含占位值）是 null。 */
    Float sensor(int type);
}
