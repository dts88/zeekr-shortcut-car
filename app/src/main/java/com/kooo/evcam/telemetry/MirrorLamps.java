package com.kooo.evcam.telemetry;

/**
 * 后视镜上那盏灯此刻亮没亮（项目所有者 2026-11-03 用在盲区画面上）：盲区指示灯（开车时旁边有车）、
 * 停车后的开门预警（同一盏灯，Lab 11-02 车上对过），左右各一份。不可变，{@link MirrorLampSource} 每变一次给一份新的。
 *
 * <p>读不到的是 null：没连上、这台车没有这个号、这一侧还没读到过。值：0 灭、1 亮；开门预警见过 3（意思不知道，
 * Lab 11-02 看到 1 → 3 → 0），不是 0 的都算亮 —— 多显示一次画面，好过该显示时不显示。</p>
 *
 * <p>纯 Java，见 {@code MirrorLampsTest}。</p>
 */
public final class MirrorLamps {

    /** 哪一盏。 */
    public enum Lamp {
        BLIND_SPOT_LEFT,
        BLIND_SPOT_RIGHT,
        DOOR_WARNING_LEFT,
        DOOR_WARNING_RIGHT
    }

    /** 全都读不到（没连上、没人要）。 */
    public static final MirrorLamps UNKNOWN = new MirrorLamps(null, null, null, null);

    public final Boolean blindSpotLeft;
    public final Boolean blindSpotRight;
    public final Boolean doorWarningLeft;
    public final Boolean doorWarningRight;

    MirrorLamps(Boolean blindSpotLeft, Boolean blindSpotRight, Boolean doorWarningLeft, Boolean doorWarningRight) {
        this.blindSpotLeft = blindSpotLeft;
        this.blindSpotRight = blindSpotRight;
        this.doorWarningLeft = doorWarningLeft;
        this.doorWarningRight = doorWarningRight;
    }

    /** 车给的值：数，不是 0 就是亮；null、不是数的算读不到。 */
    static Boolean on(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return null;
    }

    public Boolean get(Lamp lamp) {
        switch (lamp) {
            case BLIND_SPOT_LEFT:
                return blindSpotLeft;
            case BLIND_SPOT_RIGHT:
                return blindSpotRight;
            case DOOR_WARNING_LEFT:
                return doorWarningLeft;
            default:
                return doorWarningRight;
        }
    }

    /** 换掉一盏的值；没变就是自己。 */
    MirrorLamps with(Lamp lamp, Boolean value) {
        if (same(get(lamp), value)) {
            return this;
        }
        return new MirrorLamps(
                lamp == Lamp.BLIND_SPOT_LEFT ? value : blindSpotLeft,
                lamp == Lamp.BLIND_SPOT_RIGHT ? value : blindSpotRight,
                lamp == Lamp.DOOR_WARNING_LEFT ? value : doorWarningLeft,
                lamp == Lamp.DOOR_WARNING_RIGHT ? value : doorWarningRight);
    }

    /** 有一盏亮着（对账只在这时做，{@link MirrorLampSource}）。 */
    boolean anyOn() {
        return Boolean.TRUE.equals(blindSpotLeft) || Boolean.TRUE.equals(blindSpotRight)
                || Boolean.TRUE.equals(doorWarningLeft) || Boolean.TRUE.equals(doorWarningRight);
    }

    private static boolean same(Boolean a, Boolean b) {
        return a == null ? b == null : a.equals(b);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof MirrorLamps)) {
            return false;
        }
        MirrorLamps other = (MirrorLamps) o;
        return same(blindSpotLeft, other.blindSpotLeft) && same(blindSpotRight, other.blindSpotRight)
                && same(doorWarningLeft, other.doorWarningLeft) && same(doorWarningRight, other.doorWarningRight);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(blindSpotLeft, blindSpotRight, doorWarningLeft, doorWarningRight);
    }

    /** 日志、诊断用（英文）：1 亮、0 灭、? 读不到。 */
    @Override
    public String toString() {
        return "blindSpot L" + mark(blindSpotLeft) + " R" + mark(blindSpotRight)
                + ", doorWarning L" + mark(doorWarningLeft) + " R" + mark(doorWarningRight);
    }

    private static String mark(Boolean on) {
        return on == null ? "?" : on ? "1" : "0";
    }
}
