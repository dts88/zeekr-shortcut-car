package com.kooo.evcam.sound;

/** 二阶滤波器（RBJ 公式，转置直接 II 型）：两种合成器共用。 */
final class Biquad {
    private float b0 = 1f;
    private float b1;
    private float b2;
    private float a1;
    private float a2;
    private float z1;
    private float z2;

    static Biquad peaking(float hz, float db, float q, float rate) {
        Biquad b = new Biquad();
        double a = Math.pow(10.0, db / 40.0);
        double w0 = 2.0 * Math.PI * hz / rate;
        double alpha = Math.sin(w0) / (2.0 * q);
        double cos = Math.cos(w0);
        double a0 = 1.0 + alpha / a;
        b.set((1.0 + alpha * a) / a0, -2.0 * cos / a0, (1.0 - alpha * a) / a0, -2.0 * cos / a0, (1.0 - alpha / a) / a0);
        return b;
    }

    static Biquad highpass(float hz, float q, float rate) {
        Biquad b = new Biquad();
        double w0 = 2.0 * Math.PI * hz / rate;
        double alpha = Math.sin(w0) / (2.0 * q);
        double cos = Math.cos(w0);
        double a0 = 1.0 + alpha;
        b.set((1.0 + cos) / 2.0 / a0, -(1.0 + cos) / a0, (1.0 + cos) / 2.0 / a0, -2.0 * cos / a0, (1.0 - alpha) / a0);
        return b;
    }

    static Biquad lowpass(float hz, float q, float rate) {
        Biquad b = new Biquad();
        b.setLowpass(hz, q, rate);
        return b;
    }

    /** 低频搁架（斜率 1）：{@code hz} 以下多 {@code db}。 */
    static Biquad lowShelf(float hz, float db, float rate) {
        Biquad b = new Biquad();
        double a = Math.pow(10.0, db / 40.0);
        double w0 = 2.0 * Math.PI * hz / rate;
        double cos = Math.cos(w0);
        double sa = 2.0 * Math.sqrt(a) * Math.sin(w0) / 2.0 * Math.sqrt(2.0);
        double a0 = (a + 1.0) + (a - 1.0) * cos + sa;
        b.set(a * ((a + 1.0) - (a - 1.0) * cos + sa) / a0, 2.0 * a * ((a - 1.0) - (a + 1.0) * cos) / a0,
                a * ((a + 1.0) - (a - 1.0) * cos - sa) / a0, -2.0 * ((a - 1.0) + (a + 1.0) * cos) / a0,
                ((a + 1.0) + (a - 1.0) * cos - sa) / a0);
        return b;
    }

    /** 带通：中心频率处增益 1（0 dB）。 */
    static Biquad bandpass(float hz, float q, float rate) {
        Biquad b = new Biquad();
        double w0 = 2.0 * Math.PI * hz / rate;
        double alpha = Math.sin(w0) / (2.0 * q);
        double cos = Math.cos(w0);
        double a0 = 1.0 + alpha;
        b.set(alpha / a0, 0.0, -alpha / a0, -2.0 * cos / a0, (1.0 - alpha) / a0);
        return b;
    }

    void setLowpass(float hz, float q, float rate) {
        double w0 = 2.0 * Math.PI * Math.min(hz, rate * 0.45f) / rate;
        double alpha = Math.sin(w0) / (2.0 * q);
        double cos = Math.cos(w0);
        double a0 = 1.0 + alpha;
        set((1.0 - cos) / 2.0 / a0, (1.0 - cos) / a0, (1.0 - cos) / 2.0 / a0, -2.0 * cos / a0, (1.0 - alpha) / a0);
    }

    private void set(double nb0, double nb1, double nb2, double na1, double na2) {
        b0 = (float) nb0;
        b1 = (float) nb1;
        b2 = (float) nb2;
        a1 = (float) na1;
        a2 = (float) na2;
    }

    float process(float x) {
        float y = b0 * x + z1;
        z1 = b1 * x - a1 * y + z2;
        z2 = b2 * x - a2 * y;
        return y;
    }
}
