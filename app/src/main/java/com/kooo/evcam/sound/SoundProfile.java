package com.kooo.evcam.sound;

import java.util.ArrayList;
import java.util.List;

/**
 * 一台虚拟发动机的风格：转速怎么跟车走、动态多快、哪些阶次多响、噪声和共振、什么时候换挡（挡位在 {@link Gearbox}）。只是数据：
 * {@link VirtualEngine}（转速、负荷）和 {@link EngineVoice}（声音）照它算。再加一种风格 = 再加一份数据。
 *
 * <p>项目所有者 2026-10-21：燃油车风格，先做一份 V8 味（{@link #V8}）；只做运动模式的效果，所以动态取运动那一档。
 * 数值是起点，上车按耳朵调（docs/local/drive-sound-plan.md §2.4–2.6）。</p>
 */
final class SoundProfile {

    // ---- 转速
    /** 怠速（rpm）。 */
    final float idleRpm;
    /** 上限（rpm）。 */
    final float maxRpm;
    /** 连续走法：只靠车速能到跨度的几成（β）。 */
    final float speedShare;
    /** 一踩油门转速立刻跳起跨度的几成（κ）：连续走法的起跳，模拟换挡和倒挡起步时的打滑也用它。 */
    final float pedalKick;
    /** 车速曲线 S(v) = 1 − e^(−v / 它)（km/h）：越快越平。 */
    final float speedKneeKmh;

    // ---- 动态
    final float rpmRiseSec;
    final float rpmFallSec;
    final float rpmRisePerSec;
    final float rpmFallPerSec;
    final float loadAttackSec;
    final float loadReleaseSec;
    /** 踩刹车时负荷落到 0 的时间常数。 */
    final float brakeReleaseSec;

    // ---- 阶次：发动机每转一圈响几次（四冲程的点火阶次 = 缸数 / 2）
    final float[] orders;
    /** 每个阶次相对点火阶次的电平（dB）。 */
    final float[] orderDb;
    /** 满负荷时每个阶次多加的 dB（更亮、更冲；收油时跟着负荷掉）。 */
    final float[] orderLoadDb;

    // ---- 进气声：白噪声过带通，中心跟着转速
    /** 带通中心 = 转速 / 60 × 它。 */
    final float intakeCenterOrder;
    final float intakeQ;
    /** 满负荷时相对点火阶次的电平（dB），随负荷^1.5 变小。 */
    final float intakeDb;
    /** 排气的低频噪声（低通 300 Hz）满负荷时的电平（dB），随负荷² 变小；急松油门的「突突」也走这一路。 */
    final float exhaustDb;

    // ---- 固定共振（峰值均衡）
    final float[] resonanceHz;
    final float[] resonanceDb;
    final float[] resonanceQ;

    // ---- 换挡（挡位、齿比在变速箱里，{@link Gearbox}）
    /** 升挡点 = 它 + {@link #shiftUpLoadRpm} × 负荷²（{@link VirtualEngine#upshiftRpm}）。 */
    final float shiftUpBaseRpm;
    final float shiftUpLoadRpm;
    /** 降挡点 = 升挡点 × 它（猛踩、重刹时往上调，见 {@link VirtualEngine}）。 */
    final float shiftDownRatio;
    /** 换挡时转速落到新挡位的时间常数。 */
    final float shiftSec;
    /** 升挡断扭时音量掉多少（dB，负数；满负荷时，按断扭的深浅）。 */
    final float shiftDipDb;
    /**
     * 合成器（{@link EngineVoice}）把虚拟发动机的电平取这个次方：响度差压窄，音色照变（项目所有者 2026-10-21 实车；
     * 2026-10-22 输入还原平滑以后略放大一些）；总的倍数（全油门时限幅只压两三 dB）。
     */
    final float levelPow;
    final float norm;

    private SoundProfile(Builder b) {
        idleRpm = b.idleRpm;
        maxRpm = b.maxRpm;
        speedShare = b.speedShare;
        pedalKick = b.pedalKick;
        speedKneeKmh = b.speedKneeKmh;
        rpmRiseSec = b.rpmRiseSec;
        rpmFallSec = b.rpmFallSec;
        rpmRisePerSec = b.rpmRisePerSec;
        rpmFallPerSec = b.rpmFallPerSec;
        loadAttackSec = b.loadAttackSec;
        loadReleaseSec = b.loadReleaseSec;
        brakeReleaseSec = b.brakeReleaseSec;
        orders = toArray(b.orders);
        orderDb = toArray(b.orderDb);
        orderLoadDb = toArray(b.orderLoadDb);
        intakeCenterOrder = b.intakeCenterOrder;
        intakeQ = b.intakeQ;
        intakeDb = b.intakeDb;
        exhaustDb = b.exhaustDb;
        resonanceHz = toArray(b.resonanceHz);
        resonanceDb = toArray(b.resonanceDb);
        resonanceQ = toArray(b.resonanceQ);
        shiftUpBaseRpm = b.shiftUpBaseRpm;
        shiftUpLoadRpm = b.shiftUpLoadRpm;
        shiftDownRatio = b.shiftDownRatio;
        shiftSec = b.shiftSec;
        shiftDipDb = b.shiftDipDb;
        levelPow = b.levelPow;
        norm = b.norm;
    }

    /**
     * V8 味（点火阶次 4）：4、2、8 阶撑起主体；左右两排点火间隔不均匀，半阶次（0.5、1.5、2.5、3.5）给出「突突」；
     * 12、16 阶在负荷大时把声音提亮。动态取运动那一档。
     *
     * <p>换挡（项目所有者 2026-10-22：原来低速换挡太勤、齿比太密；轻踩不在低挡拖着，只有重踩、急踩才拉高转速）：
     * 轻踩 2000 rpm 多就升挡，地板油拉到 6000（{@link #shiftUpBaseRpm}）；挡位照车主偏好的那台的变速箱（{@link Gearbox}）。</p>
     */
    static final SoundProfile V8 = new Builder()
            .rpm(900f, 7000f)
            .continuous(0.30f, 0.30f, 70f)
            .rise(0.10f, 6000f)
            .fall(0.40f, 4000f)
            .load(0.06f, 0.25f, 0.12f)
            .order(4f, 0f, 0f)
            .order(2f, -4f, 0f)
            .order(8f, -8f, 4f)
            .order(6f, -12f, 3f)
            .order(3f, -14f, 0f)
            .order(1f, -16f, 0f)
            .order(0.5f, -22f, 6f)
            .order(1.5f, -20f, 6f)
            .order(2.5f, -20f, 6f)
            .order(3.5f, -22f, 6f)
            .order(12f, -18f, 6f)
            .order(16f, -24f, 6f)
            .intake(32f, 1.5f, -18f)
            .exhaust(-14f)
            .resonance(150f, 4f, 1.0f)
            .resonance(1200f, 3f, 1.2f)
            .shifts(2000f, 4000f, 0.55f, 0.06f, -4.5f)
            .loudness(0.17f, 0.8f)
            .build();

    private static float[] toArray(List<Float> list) {
        float[] out = new float[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    /** 写风格用：一项一项填，最后 {@link #build}。 */
    static final class Builder {
        private float idleRpm = 900f;
        private float maxRpm = 6500f;
        private float speedShare = 0.3f;
        private float pedalKick = 0.3f;
        private float speedKneeKmh = 70f;
        private float rpmRiseSec = 0.18f;
        private float rpmFallSec = 0.5f;
        private float rpmRisePerSec = 5000f;
        private float rpmFallPerSec = 3500f;
        private float loadAttackSec = 0.08f;
        private float loadReleaseSec = 0.3f;
        private float brakeReleaseSec = 0.12f;
        private final List<Float> orders = new ArrayList<>();
        private final List<Float> orderDb = new ArrayList<>();
        private final List<Float> orderLoadDb = new ArrayList<>();
        private float intakeCenterOrder = 32f;
        private float intakeQ = 1.5f;
        private float intakeDb = -18f;
        private float exhaustDb = -14f;
        private final List<Float> resonanceHz = new ArrayList<>();
        private final List<Float> resonanceDb = new ArrayList<>();
        private final List<Float> resonanceQ = new ArrayList<>();
        private float shiftUpBaseRpm = 2500f;
        private float shiftUpLoadRpm = 3500f;
        private float shiftDownRatio = 0.55f;
        private float shiftSec = 0.06f;
        private float shiftDipDb = -3f;
        private float levelPow = 1f;
        private float norm = 1f;

        Builder rpm(float idle, float max) {
            idleRpm = idle;
            maxRpm = max;
            return this;
        }

        Builder continuous(float speedShare, float pedalKick, float speedKneeKmh) {
            this.speedShare = speedShare;
            this.pedalKick = pedalKick;
            this.speedKneeKmh = speedKneeKmh;
            return this;
        }

        Builder rise(float sec, float perSec) {
            rpmRiseSec = sec;
            rpmRisePerSec = perSec;
            return this;
        }

        Builder fall(float sec, float perSec) {
            rpmFallSec = sec;
            rpmFallPerSec = perSec;
            return this;
        }

        Builder load(float attackSec, float releaseSec, float brakeReleaseSec) {
            loadAttackSec = attackSec;
            loadReleaseSec = releaseSec;
            this.brakeReleaseSec = brakeReleaseSec;
            return this;
        }

        Builder order(float order, float db, float loadDb) {
            orders.add(order);
            orderDb.add(db);
            orderLoadDb.add(loadDb);
            return this;
        }

        Builder intake(float centerOrder, float q, float db) {
            intakeCenterOrder = centerOrder;
            intakeQ = q;
            intakeDb = db;
            return this;
        }

        Builder exhaust(float db) {
            exhaustDb = db;
            return this;
        }

        Builder resonance(float hz, float db, float q) {
            resonanceHz.add(hz);
            resonanceDb.add(db);
            resonanceQ.add(q);
            return this;
        }

        Builder shifts(float upBase, float upLoad, float downRatio, float sec, float dipDb) {
            shiftUpBaseRpm = upBase;
            shiftUpLoadRpm = upLoad;
            shiftDownRatio = downRatio;
            shiftSec = sec;
            shiftDipDb = dipDb;
            return this;
        }

        Builder loudness(float levelPow, float norm) {
            this.levelPow = levelPow;
            this.norm = norm;
            return this;
        }

        SoundProfile build() {
            if (orders.isEmpty() || !(maxRpm > idleRpm)) {
                throw new IllegalStateException("sound profile incomplete");
            }
            return new SoundProfile(this);
        }
    }
}
