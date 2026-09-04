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
 * <h3>为什么叫「表」而不是「配置」</h3>
 *
 * <p>这里面装的是<b>设备事实</b>：这一路相机在这个尺寸下送出来的像素是怎么排的。
 * 用户改不了，因为那不是一个选择。跟它对立的是<b>配置</b>（用哪几路、
 * 每条流什么参数、预览窗口怎么摆）—— 那些才是选择。两者必须分开，
 * 这个类原来叫 {@code CompositeSplitProfile}，改名就是为了不和用户那个「配置」撞名。</p>
 *
 * <p>其余组合一律不拆。特别是 <b>3840×2160 三路都有</b> —— 只看分辨率根本分不清，
 * 这正是这张表必须带上「哪一路」的原因：座舱拍到的一整幅画面被切成四块，
 * 比不拆糟糕得多。</p>
 *
 * <h3>哪一路是「相机 2」</h3>
 *
 * <p>不写死 id。{@link ZeekrCameraLocator} 启动时靠 1280×5140 这种<b>只有合成流
 * 才会给</b>的长条认出它，然后登记在这里。换一台车、id 变了也照样能认出来。</p>
 *
 * <h3>表里没有的组合一律不拆</h3>
 *
 * <p>不按长宽比去猜。猜的代价不对称：不拆看到的是一条挤在一起的长条，一眼就知道
 * 不对；拆错看到的是四块被切开的画面，反而像是「功能正常」。长宽比只用来
 * <b>认出哪一路是合成流</b>，从不用来决定怎么拆。</p>
 */
public final class StreamLayoutTable {

    /**
     * 合成流那一路已确认的拆分尺寸，以及各自的排布。
     *
     * <p>排布<b>写在表里</b>，不从长宽比推 —— 3840×2160 的长边是宽，但四格
     * 是竖排（每格 3840×540）。按长边推会得出横排，四个画面全串位。</p>
     */
    private static final Object[][] COMPOSITE_SIZES = {
            {1280, 5140, CompositeStreamGeometry.Stacking.VERTICAL},
            {3840, 2160, CompositeStreamGeometry.Stacking.VERTICAL},
    };

    /** 合成流那一路的相机 id；还没认出来时为 null。 */
    private static volatile String compositeCameraId;

    private StreamLayoutTable() {
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
        for (Object[] entry : COMPOSITE_SIZES) {
            if ((Integer) entry[0] == width && (Integer) entry[1] == height) {
                return (CompositeStreamGeometry.Stacking) entry[2];
            }
        }
        // 表里没有这个组合就不拆，不按长宽比去猜。
        //
        // 猜的代价不对称：不拆看到的是一条挤在一起的长条，一眼就知道不对，
        // 而且换个分辨率就能解决；拆错看到的是四块被切开的画面，反而像是
        // 「功能正常」，等到有人对着录像找证据时才发现方位全是错的。
        //
        // 换固件、出现新的合成流尺寸时，往上面那张表里加一行 —— 加之前先在
        // 「开发者选项 → 相机能力清单」里确认这一路真的声明了它。
        return CompositeStreamGeometry.Stacking.NOT_COMPOSITE;
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
            out[i][0] = (Integer) COMPOSITE_SIZES[i][0];
            out[i][1] = (Integer) COMPOSITE_SIZES[i][1];
        }
        return out;
    }

    /** 回到「还没认出哪一路是合成流」的状态。测试用，跳车型时也用得上。 */
    public static void reset() {
        compositeCameraId = null;
    }
}
