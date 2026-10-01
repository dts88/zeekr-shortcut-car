package com.kooo.evcam.zeekr;

/**
 * 侧视弹窗「往哪儿看」：放大一点，再把取景框往后、往上挪，盯住后视镜够不着的那块盲区。
 *
 * <h3>方向是猜的（2026-10-01，还没在车上对过）</h3>
 *
 * <p>侧面摄像头装在后视镜底下、朝下看。猜：每一路画面的<b>上边朝车外</b>（往上 = 远离车身、朝地平线），
 * 左侧那一路的<b>左边是车尾</b>、右侧那一路的<b>右边是车尾</b>（两路左右对称）。
 * 猜反了在设置里把滑块往另一头拉就行，范围两头对称。</p>
 *
 * <h3>能挪多远</h3>
 *
 * <p>取景框边长 = 1 / 放大倍数，能挪的余量 = 1 − 边长，往两头各一半。放大 1 倍时没有余量，挪不动。
 * 拉直开着时，画面最远到偏离光轴 fov/2（140° 时 70°）；放大 1.6 倍时取景框中心最多偏 45° 左右。</p>
 *
 * <p>纯 Java，见 {@code SideViewAimTest}。</p>
 */
public final class SideViewAim {

    private SideViewAim() {
    }

    /**
     * @param lane          {@link LaneCycle#LEFT} / {@link LaneCycle#RIGHT}
     * @param zoomPercent   放大倍数 ×100，100 = 不放大
     * @param backPercent   −100..100，正 = 往车尾，负 = 往车头
     * @param upPercent     −100..100，正 = 往上（朝车外），负 = 往下（朝车身、地面）
     * @return 这一路里要看的那一块，归一化
     */
    public static RearViewGeometry.Viewport viewport(int lane, int zoomPercent, int backPercent, int upPercent) {
        float size = 100f / Math.max(100, zoomPercent);
        float half = (1f - size) / 2f;
        float back = clamp(backPercent / 100f);
        float up = clamp(upPercent / 100f);
        // 左侧那一路车尾在左边：往后 = x 变小；右侧那一路车尾在右边：往后 = x 变大
        float towardRear = lane == LaneCycle.RIGHT ? back : -back;
        float x = half + towardRear * half;
        // 上边朝车外：往上 = y 变小
        float y = half - up * half;
        return new RearViewGeometry.Viewport(x, y, size, size);
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}
