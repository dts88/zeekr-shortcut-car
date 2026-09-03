package com.kooo.evcam.zeekr;

/**
 * 「这一帧要不要拆、怎么拆」的唯一依据：<b>哪一路相机 + 什么分辨率</b>。
 *
 * <h3>为什么是这两个条件，而不是别的</h3>
 *
 * <p>拆分是对<b>像素排布</b>的描述。像素排布只由出这一帧的相机和它的输出尺寸决定 ——
 * 和车型配置无关，和录制排列（四宫格 / 原始长条）无关，和用户选了什么也无关。
 * 以前用长宽比去猜，是因为不知道这一路装的是什么；现在知道了，就不该再猜。</p>
 *
 * <h3>已知的几种</h3>
 *
 * <table>
 *   <tr><td>1280×5140</td><td>四格竖排，4 条 5px 分隔带</td></tr>
 *   <tr><td>1280×5120</td><td>四格竖排，无分隔带</td></tr>
 *   <tr><td>5120×1280</td><td>四格横排</td></tr>
 *   <tr><td>3840×2160</td><td>四格竖排，等分（每格 3840×540）</td></tr>
 * </table>
 *
 * <p>四格的顺序在所有尺寸下一致：<b>前 后 左 右</b>，见 {@link LaneCycle}。</p>
 *
 * <h3>相机作用域</h3>
 *
 * <p>1280×5140 这类长条只有合成流才会给，尺寸本身就足以说明问题。但
 * 3840×2160 是个<b>普通尺寸</b> —— 座舱那两路也声明支持它。所以这一档
 * 额外要求「必须是合成流那一路」，否则座舱拍出来的一整幅画面会被切成四块。</p>
 *
 * <p>哪一路是合成流由 {@link ZeekrCameraLocator} 在启动时认出来并登记在这里。</p>
 */
public final class CompositeSplitProfile {

    /** 一种已知的合成流尺寸。 */
    private static final class Profile {
        final int width;
        final int height;
        final CompositeStreamGeometry.Stacking stacking;
        /** 这个尺寸是不是「一看就是合成流」；false 表示还要确认相机身份。 */
        final boolean sizeIsConclusive;

        Profile(int width, int height, CompositeStreamGeometry.Stacking stacking,
                boolean sizeIsConclusive) {
            this.width = width;
            this.height = height;
            this.stacking = stacking;
            this.sizeIsConclusive = sizeIsConclusive;
        }
    }

    private static final Profile[] KNOWN = {
            new Profile(1280, 5140, CompositeStreamGeometry.Stacking.VERTICAL, true),
            new Profile(1280, 5120, CompositeStreamGeometry.Stacking.VERTICAL, true),
            new Profile(5120, 1280, CompositeStreamGeometry.Stacking.HORIZONTAL, true),
            // 16:9，座舱那两路也有这个尺寸 —— 光看尺寸判断不了，要确认相机身份
            new Profile(3840, 2160, CompositeStreamGeometry.Stacking.VERTICAL, false),
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
     * @param cameraId 出这一帧的相机；不知道时传 null
     * @return 拆分方式；不该拆时返回 {@code NOT_COMPOSITE}
     */
    public static CompositeStreamGeometry.Stacking stackingFor(String cameraId,
                                                               int width, int height) {
        if (width <= 0 || height <= 0) {
            return CompositeStreamGeometry.Stacking.NOT_COMPOSITE;
        }
        for (Profile profile : KNOWN) {
            if (profile.width != width || profile.height != height) {
                continue;
            }
            if (profile.sizeIsConclusive) {
                return profile.stacking;
            }
            // 尺寸不足以说明问题，必须是合成流那一路才拆
            String composite = compositeCameraId;
            boolean isComposite = composite != null && composite.equals(cameraId);
            return isComposite ? profile.stacking
                    : CompositeStreamGeometry.Stacking.NOT_COMPOSITE;
        }
        // 表里没有这个尺寸。只有在确定是合成流那一路时才按长条比例兜底 ——
        // 固件换一版、尺寸跟着变（或者 HAL 给了一个缩小的提示尺寸）时，
        // 总比把整条合成流当成一幅画面显示要好。座舱那两路不适用：
        // 它们本来就不是合成流，猜错的代价是把一幅好画面切成四块。
        String composite = compositeCameraId;
        if (composite != null && composite.equals(cameraId)
                && CompositeStreamGeometry.looksLikeCompositeByRatio(width, height)) {
            return width > height ? CompositeStreamGeometry.Stacking.HORIZONTAL
                    : CompositeStreamGeometry.Stacking.VERTICAL;
        }
        return CompositeStreamGeometry.Stacking.NOT_COMPOSITE;
    }

    /**
     * 这个尺寸是不是<b>一看就是</b>合成流。
     *
     * <p>用来在候选分辨率里认出合成流那一路，所以不看相机身份 ——
     * 认相机的时候还不知道哪一路是合成流。</p>
     */
    public static boolean sizeIsConclusive(int width, int height) {
        for (Profile profile : KNOWN) {
            if (profile.width == width && profile.height == height) {
                return profile.sizeIsConclusive;
            }
        }
        return false;
    }

    /** 已知尺寸的列表，形如 {@code {宽, 高}}，给诊断和设置界面用。 */
    public static int[][] knownSizes() {
        int[][] out = new int[KNOWN.length][2];
        for (int i = 0; i < KNOWN.length; i++) {
            out[i][0] = KNOWN[i].width;
            out[i][1] = KNOWN[i].height;
        }
        return out;
    }

    /** 仅供测试还原状态。 */
    static void reset() {
        compositeCameraId = null;
    }
}
