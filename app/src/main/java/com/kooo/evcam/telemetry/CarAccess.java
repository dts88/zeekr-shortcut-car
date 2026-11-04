package com.kooo.evcam.telemetry;

/**
 * 车辆接口的读写（{@link Telemetry#carAccess}）：只有灵动贴的动作用（驾驶模式和其他车控动作，项目所有者 2026-10-30、11-04）。
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

    /** 一个传感器现在的值；读不到（含占位值）是 null。 */
    Float sensor(int type);
}
