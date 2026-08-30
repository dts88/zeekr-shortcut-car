package com.kooo.evcam.zeekr;

/**
 * 后视镜在四路之间的切换顺序。
 *
 * <h3>格子与方向的对应</h3>
 *
 * <p>2×2 的格子编号是「左上、右上、左下、右下」，对应 {@code 前 后 左 右} ——
 * 与主界面那四个标签的顺序一致。其中<b>只有「后 = 右上」是在实车上确认过的</b>
 * （超级后视镜取的就是这一格，画面确实是后方）；另外三个来自现有标签，尚未逐一核实。</p>
 *
 * <h3>转向</h3>
 *
 * <p>从车顶往下看，前在上、后在下：从「后」开始顺时针走一圈就是
 * <b>后 → 左 → 前 → 右</b>，逆时针则是 <b>后 → 右 → 前 → 左</b>。
 * 这两条环各自首尾相接，转到底会绕回来 —— 后视镜是用来快速扫一圈的，
 * 转到「右」就不动了反而要多划几下回去。</p>
 *
 * <p>纯 Java，不碰 Android，方便直接跑单元测试。</p>
 */
public final class LaneCycle {

    public static final int FRONT = 0;
    public static final int REAR = 1;
    public static final int LEFT = 2;
    public static final int RIGHT = 3;

    /** 顺时针一圈：后 左 前 右。 */
    private static final int[] CLOCKWISE = {REAR, LEFT, FRONT, RIGHT};

    /** 「只看前后」时的环：来回两路。 */
    private static final int[] FRONT_REAR_ONLY = {REAR, FRONT};

    private LaneCycle() {
    }

    /**
     * 下一路是哪一路。
     *
     * @param current       当前这一路
     * @param clockwise     true 走顺时针（后 左 前 右），false 走逆时针
     * @param frontRearOnly 只在前后之间切换
     * @return 切换后的那一路；{@code current} 不在环上时退回 {@link #REAR}
     */
    public static int next(int current, boolean clockwise, boolean frontRearOnly) {
        int[] ring = frontRearOnly ? FRONT_REAR_ONLY : CLOCKWISE;
        int position = indexIn(ring, current);
        if (position < 0) {
            // 比如从四路模式切到「只看前后」时，当前停在「左」——环上没有它，
            // 退回后视而不是卡住
            return REAR;
        }
        int step = clockwise ? 1 : -1;
        int next = (position + step + ring.length) % ring.length;
        return ring[next];
    }

    /** 这一路在当前模式的环上吗。 */
    public static boolean isOnRing(int lane, boolean frontRearOnly) {
        return indexIn(frontRearOnly ? FRONT_REAR_ONLY : CLOCKWISE, lane) >= 0;
    }

    /**
     * 要不要左右镜像。
     *
     * <p><b>只有后视需要。</b>后视镜照出来的本来就是反的，看到车从画面右侧靠近就该往左让；
     * 而前视、侧视是「朝那个方向看过去」的画面，镜像了反而与实际相反。</p>
     */
    public static boolean isMirrored(int lane) {
        return lane == REAR;
    }

    public static String labelOf(int lane) {
        switch (lane) {
            case FRONT: return "前";
            case REAR: return "后";
            case LEFT: return "左";
            case RIGHT: return "右";
            default: return "?";
        }
    }

    private static int indexIn(int[] ring, int lane) {
        for (int i = 0; i < ring.length; i++) {
            if (ring[i] == lane) {
                return i;
            }
        }
        return -1;
    }
}
