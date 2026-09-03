package com.kooo.evcam.zeekr;

/**
 * 「这一帧要不要拆、怎么拆」的唯一依据：<b>哪一路相机 + 什么分辨率</b>。
 *
 * <h3>为什么是这两个条件</h3>
 *
 * <p>拆分是对<b>像素排布</b>的描述。像素排布只由出这一帧的相机和它的输出尺寸决定 ——
 * 和车型配置无关，和录制排列（四宫格 / 原始长条）无关，和用户选了什么也无关。</p>
 *
 * <h3>这台车实际声明了什么</h3>
 *
 * <p>下面这张表<b>只列实测确认过的组合</b>，来自诊断报告（zeekr_dhu_sa8295，
 * 三路相机，各路的 PRIVATE / SurfaceTexture / JPEG 尺寸列表完全一致）：</p>
 *
 * <pre>
 *   相机 0  BACK      3840x2160  2560x1440  1920x1080  1920x1024  1600x900  1280x800  1280x720  640x480  320x240
 *   相机 1  FRONT     3840x2160  2560x1440  1920x1080  1920x1024  1600x900            1280x720  640x480  320x240
 *   相机 2  EXTERNAL  3840x2160  1280x5140  2560x1440  1920x1080  1920x1024  1600x900  1280x720  640x480  320x240
 * </pre>
 *
 * <p>所以<b>只有相机 2 的两个尺寸要拆</b>：</p>
 *
 * <table>
 *   <tr><td>相机 2 · 1280×5140</td><td>四格竖排，5 条 4px 分隔带，每格 1280×1280</td></tr>
 *   <tr><td>相机 2 · 3840×2160</td><td>四格竖排，等分，每格 3840×540</td></tr>
 * </table>
 *
 * <p>四格顺序在两个尺寸下一致：<b>前 后 左 右</b>，见 {@link LaneCycle}。</p>
 *
 * <p>其余组合一律不拆。特别是 <b>3840×2160 三路都有</b> —— 只看分辨率根本分不清，
 * 这正是这张表必须带上「哪一路」的原因：座舱拍到的一整幅画面被切成四块，
 * 比不拆糟糕得多。</p>
 *
 * <h3>哪一路是「相机 2」</h3>
 *
 * <p>不写死 id。{@link ZeekrCameraLocator} 启动时靠 1280×5140 这种<b>只有合成流
 * 才会给</b>的长条认出它，然后登记在这里。换一台车、id 变了也照样能认出来。</p>
 */
public final class CompositeSplitProfile {

    /** 合成流那一路已确认的拆分尺寸。 */
    private static final int[][] COMPOSITE_SIZES = {
            {1280, 5140},
            {3840, 2160},
    };

    /** 合成流那一路的相机 id；还没认出来时为 null。 */
    private static volatile String compositeCameraId;

    private CompositeSplitProfile() {
    }

    /** 启动时认出合成流之后登记一次。 */
    public static void setCompositeCameraId(String cameraId) {
        compositeCameraId = cameraId;
    }

    public static String compositeCameraId() {
        return compositeCameraId;
    }

    /**
     * 这一路相机在这个尺寸下要怎么拆。
     *
     * @param cameraId 出这一帧的相机；不知道是哪一路时传 null
     * @return 拆分方式；不该拆时返回 {@code NOT_COMPOSITE}
     */
    public static CompositeStreamGeometry.Stacking stackingFor(String cameraId,
                                                               int width, int height) {
        if (width <= 0 || height <= 0 || !isCompositeCamera(cameraId)) {
            return CompositeStreamGeometry.Stacking.NOT_COMPOSITE;
        }
        for (int[] size : COMPOSITE_SIZES) {
            if (size[0] == width && size[1] == height) {
                return stackingOf(width, height);
            }
        }
        // 表里没有这个尺寸。合成流那一路给出长条时仍然拆 —— 固件换一版、尺寸跟着变，
        // 或者 HAL 给了一个缩小的提示尺寸，总比把整条合成流当成一幅画面显示要好。
        // 这一条只对合成流那一路成立，所以不会误伤座舱。
        if (CompositeStreamGeometry.looksLikeCompositeByRatio(width, height)) {
            return stackingOf(width, height);
        }
        return CompositeStreamGeometry.Stacking.NOT_COMPOSITE;
    }

    /** 长边在哪个方向，四格就往哪个方向排。 */
    private static CompositeStreamGeometry.Stacking stackingOf(int width, int height) {
        return width > height ? CompositeStreamGeometry.Stacking.HORIZONTAL
                : CompositeStreamGeometry.Stacking.VERTICAL;
    }

    /**
     * 还没认出合成流那一路时，谁都不是。
     *
     * <p>宁可不拆也不要拆错：不拆看到的是一条挤在一起的长条，一眼就知道不对；
     * 拆错看到的是四块被切开的正常画面，反而像是「功能正常」。</p>
     */
    private static boolean isCompositeCamera(String cameraId) {
        String composite = compositeCameraId;
        return composite != null && composite.equals(cameraId);
    }

    /** 合成流那一路已确认要拆的尺寸，形如 {@code {宽, 高}}。给诊断和设置界面用。 */
    public static int[][] compositeSizes() {
        int[][] out = new int[COMPOSITE_SIZES.length][2];
        for (int i = 0; i < COMPOSITE_SIZES.length; i++) {
            out[i][0] = COMPOSITE_SIZES[i][0];
            out[i][1] = COMPOSITE_SIZES[i][1];
        }
        return out;
    }

    /** 仅供测试还原状态。 */
    static void reset() {
        compositeCameraId = null;
    }
}
