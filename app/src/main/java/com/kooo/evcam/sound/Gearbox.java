package com.kooo.evcam.sound;

/**
 * 模拟换挡用的变速箱（「模拟声浪」页的「变速箱」，项目所有者 2026-10-22）：照车主偏好的那台机械增压 V8 的两种 —— 8 速自动（默认）
 * 和 6 速手动。只是数据：齿比、主减速比，换算成每个挡位 1 km/h 对应的发动机转速；{@link VirtualEngine} 照它选挡、换挡。
 * 两种都按原厂轮胎（275/40 R20）算。数据来源、换算见 docs/local/drive-sound-plan.md §12.15、§12.16。
 */
enum Gearbox {
    /** 8 速自动（默认）：齿比 4.71、3.14、2.10、1.67、1.29、1.00、0.84、0.67，倒挡 3.30，主减速比 2.62；一挡到 6200 rpm 约 67 km/h。 */
    AUTO8(2.62f, 3.30f, 0.22f, 4.71f, 3.14f, 2.10f, 1.67f, 1.29f, 1.00f, 0.84f, 0.67f),
    /**
     * 6 速手动：齿比 2.26、1.58、1.19、1.00、0.77、0.63，倒挡 2.90，主减速比 3.70；一挡到 6200 rpm 约 99 km/h。
     * 换挡要踩离合、拨挡杆，升挡比自动挡慢（{@link #upshiftSec}）。
     */
    MANUAL6(3.70f, 2.90f, 0.35f, 2.26f, 1.58f, 1.19f, 1.00f, 0.77f, 0.63f);

    /** 原厂轮胎（275/40 R20）的滚动周长（m）：直径约 0.728 m，压着地面滚的半径小一点。 */
    static final float ROLLING_M = 2.22f;

    /** 每个挡位 1 km/h 对应多少 rpm（一挡到最高挡）。 */
    final float[] rpmPerKmh;
    /** 倒挡 1 km/h 对应多少 rpm。 */
    final float reverseRpmPerKmh;
    /** 升挡用多久（秒）：断扭、转速落到新挡位、动力回来（{@link VirtualEngine}）。 */
    final float upshiftSec;

    Gearbox(float finalDrive, float reverseRatio, float upshiftSec, float... ratios) {
        float perRatio = rpmPerKmh(1f, finalDrive);
        rpmPerKmh = new float[ratios.length];
        for (int g = 0; g < ratios.length; g++) {
            rpmPerKmh[g] = ratios[g] * perRatio;
        }
        reverseRpmPerKmh = reverseRatio * perRatio;
        this.upshiftSec = upshiftSec;
    }

    /** 手动挡：松着油门不换挡（开车的人松油门是要减速、要用发动机拖着，不是要升挡，{@link VirtualEngine}）。 */
    boolean manual() {
        return this == MANUAL6;
    }

    /** 齿比 × 主减速比 → 1 km/h 对应多少 rpm（车轮每分钟转几圈 × 总传动比）。 */
    static float rpmPerKmh(float ratio, float finalDrive) {
        return ratio * finalDrive * 1000f / 60f / ROLLING_M;
    }
}
