package com.kooo.evcam.zeekr;

/**
 * 「这一帧要不要拆、怎么拆」的唯一依据：<b>哪一路相机</b>。
 *
 * <h3>环视那一路只有一种内容</h3>
 *
 * <p>它<b>在任何分辨率下给的都是同一份四格合成内容</b>，分辨率只决定它被压成什么
 * 形状、每一格剩多少细节。实测确认过三个尺寸：</p>
 *
 * <pre>
 *   1280×5140   条带原始比例，每格 1280×1280，5 条 4px 分隔带
 *   3840×2160   16:9，每格 3840×540
 *   1600×900    16:9，每格 1600×225
 * </pre>
 *
 * <p>三个都是四格竖排，前后左右的位置也一致。所以「什么分辨率」这一维是多余的 ——
 * 它不改变排布，只改变清晰度。</p>
 *
 * <h3>原来为什么按分辨率列表</h3>
 *
 * <p>因为当初不知道这一路装的是什么，只能一个一个确认。到第三个尺寸才看清规律：
 * 内容只有一种。列表于是收成一条规则，也就不用每发现一个新尺寸就加一行。</p>
 *
 * <p>1600×900 那次是个提示：它不在当时的表里、按规则不该拆，预览却拆对了。原因是
 * {@link FourLaneContainer} 有一道「忽略不认识的尺寸、保住已有几何」的防线，而拆分
 * 坐标是归一化的，套在缩放后的同一份内容上依然成立。防线替规则遮住了错误 ——
 * 但配置编辑界面照着表说「不拆」，和实际相反。那才是要改的理由。</p>
 *
 * <h3>座舱那两路一格都不拆</h3>
 *
 * <p>它们本来就不是合成流。3840×2160 三路都声明，光看分辨率分不清 ——
 * 这正是判断依据必须是<b>相机</b>而不是分辨率的原因。</p>
 *
 * <h3>哪一路是合成流</h3>
 *
 * <p>不写死 id。{@link ZeekrCameraLocator} 启动时靠 1280×5140 这种<b>只有合成流
 * 才会给</b>的长条认出它，然后登记在这里。换一台车、id 变了也照样能认出来。</p>
 *
 * <p>还没认出来时谁都不是：宁可不拆也不要拆错 —— 不拆看到的是一条挤在一起的长条，
 * 一眼就知道不对；拆错看到的是四块被切开的画面，反而像是「功能正常」。</p>
 */
public final class StreamLayoutTable {

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
        // 这一路只有一种内容：四格竖排。分辨率不参与判断。
        return CompositeStreamGeometry.Stacking.VERTICAL;
    }

    private static boolean isCompositeCamera(String cameraId) {
        String composite = compositeCameraId;
        return composite != null && composite.equals(cameraId);
    }

    /** 回到「还没认出哪一路是合成流」的状态。测试用，跳车型时也用得上。 */
    public static void reset() {
        compositeCameraId = null;
    }
}
