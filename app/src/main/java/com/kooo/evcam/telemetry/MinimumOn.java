package com.kooo.evcam.telemetry;

/**
 * 一闪而过的信号至少亮一小段：录像一秒 20–30 帧，0.1 秒的一下只占两三帧，回看时看不到。
 *
 * <p>亮了就算亮；灭了之后，离上次亮不到 {@link #holdMs} 仍算亮。读不到（null）且之前没亮过就是读不到。
 * 和 {@link TurnSignalHold} 是同一个道理（那个管转向灯闪烁的间隙），这个管单个的短脉冲。</p>
 *
 * <p>到点要有人再算一次，灯才会灭：要保持多久由 {@link VehicleStateMapper#republishAfterMs} 告诉来源。</p>
 */
final class MinimumOn {

    private final long holdMs;
    private boolean ever;
    private long lastOnMs;

    MinimumOn(long holdMs) {
        this.holdMs = holdMs;
    }

    Boolean update(long nowMs, Boolean on) {
        if (Boolean.TRUE.equals(on)) {
            ever = true;
            lastOnMs = nowMs;
            return Boolean.TRUE;
        }
        if (ever && nowMs - lastOnMs < holdMs) {
            return Boolean.TRUE;
        }
        return on;
    }
}
