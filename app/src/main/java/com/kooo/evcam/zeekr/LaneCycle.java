package com.kooo.evcam.zeekr;

/**
 * 超级镜子在几路画面之间的切换顺序。
 *
 * <h3>格子与方向的对应</h3>
 *
 * <p>2×2 的格子编号是「左上、右上、左下、右下」，对应 {@code 前 后 左 右} ——
 * 与主界面那四个标签的顺序一致。<b>四个方向都已在实车上确认。</b></p>
 *
 * <p>{@link #CABIN_REAR} 不是环视里的一格，是后座舱摄像头的整幅画面：只在「后视 + 后座舱」那一档（{@link MirrorViews}）
 * 出现在环上。</p>
 *
 * <h3>转向</h3>
 *
 * <p>从车顶往下看，前在上、后在下：从「后」开始顺时针走一圈就是
 * <b>后 → 左 → 前 → 右</b>，逆时针则是 <b>后 → 右 → 前 → 左</b>。
 * 手势上<b>右滑走顺时针，左滑走逆时针</b>。
 * 这几条环各自首尾相接，转到底会绕回来 —— 超级镜子是用来快速扫一圈的，
 * 转到「右」就不动了反而要多划几下回去。两路的那两档就是来回切。</p>
 *
 * <p>纯 Java，不碰 Android，方便直接跑单元测试。</p>
 */
public final class LaneCycle {

    public static final int FRONT = 0;
    public static final int REAR = 1;
    public static final int LEFT = 2;
    public static final int RIGHT = 3;
    /** 后座舱摄像头的整幅画面（不是环视里的一格）。 */
    public static final int CABIN_REAR = 4;

    /** 四向，顺时针一圈：后 左 前 右。 */
    private static final int[] CLOCKWISE = {REAR, LEFT, FRONT, RIGHT};

    /** 前后：来回两路。 */
    private static final int[] FRONT_REAR = {REAR, FRONT};

    /** 后视 + 后座舱：来回两路。 */
    private static final int[] REAR_CABIN = {REAR, CABIN_REAR};

    private LaneCycle() {
    }

    /**
     * 下一路是哪一路。
     *
     * @param current   当前这一路
     * @param clockwise true 走顺时针（后 左 前 右），false 走逆时针；两路的那两档来回切，方向不影响结果
     * @param views     切换范围
     * @return 切换后的那一路；{@code current} 不在环上时退回 {@link #REAR}
     */
    public static int next(int current, boolean clockwise, MirrorViews views) {
        int[] ring = ring(views);
        int position = indexIn(ring, current);
        if (position < 0) {
            // 比如从四向切到「前后」时，当前停在「左」——环上没有它，退回后视而不是卡住
            return REAR;
        }
        int step = clockwise ? 1 : -1;
        int next = (position + step + ring.length) % ring.length;
        return ring[next];
    }

    /** 这一路在这一档的环上吗。 */
    public static boolean isOnRing(int lane, MirrorViews views) {
        return indexIn(ring(views), lane) >= 0;
    }

    /**
     * 要不要左右镜像。
     *
     * <p><b>后视和后座舱要。</b>后视镜照出来的本来就是反的，看到车从画面右侧靠近就该往左让；
     * 后座舱也照车内后视镜里看到的样子显示（项目所有者 2026-10-19）。前视、侧视是「朝那个方向看过去」的画面，
     * 镜像了反而与实际相反。</p>
     */
    public static boolean isMirrored(int lane) {
        return lane == REAR || lane == CABIN_REAR;
    }

    public static String labelOf(int lane) {
        switch (lane) {
            case FRONT: return "前";
            case REAR: return "后";
            case LEFT: return "左";
            case RIGHT: return "右";
            case CABIN_REAR: return "后座舱";
            default: return "?";
        }
    }

    private static int[] ring(MirrorViews views) {
        switch (views) {
            case FRONT_REAR:
                return FRONT_REAR;
            case REAR_CABIN:
                return REAR_CABIN;
            case FOUR:
            default:
                return CLOCKWISE;
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
