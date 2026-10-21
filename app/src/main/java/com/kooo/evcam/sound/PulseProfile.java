package com.kooo.evcam.sound;

/**
 * 逐缸合成（{@link PulseVoice}）的一种风格：缸怎么排、怎么点火，排气脉冲、气流噪声、排气管和消声器、进气、增压、气门、缸体、
 * 车厢、传动各是什么样。只是数据，再加一种风格 = 再加一份数据。
 *
 * <p>数值的来历在 docs/local/drive-sound-plan.md §12：结构照真机（交叉平面 V8 的点火顺序、左右两排、
 * 同一排点火间隔 90/180/270° 的遮蔽），频谱照一台机械增压交叉平面 V8 的录音量出来的（阶次、固定共振峰、
 * 随机成分占比、高频滚降）去对；上车按耳朵调。项目所有者 2026-10-21 偏好这一类 V8 的声音取向。</p>
 */
final class PulseProfile {

    // ---- 发动机
    /** 点火顺序（缸号，从 1 起）；单号缸在左排，双号缸在右排。 */
    final int[] firing;
    /** 每个缸的脉冲强弱（下标 = 缸号 − 1）：制造上的细小差别。 */
    final float[] trim;
    /** 每个缸的排气歧管比最短的那根多走多久（毫秒）。 */
    final float[] headerMs;
    /** 左、右两排的排气管多走多久（毫秒）。 */
    final float[] bankMs;
    /** 上止点之后多少度：排气门开（排气脉冲）、排气门关、进气门关（气门落座、进气脉冲）、燃烧（缸体的「咔」）。 */
    final float exhaustOpenDeg;
    final float exhaustCloseDeg;
    final float intakeCloseDeg;
    final float burnDeg;

    // ---- 排气脉冲：双指数（起得快、按曲轴角落下去）
    final float riseIdleMs;
    final float riseLoadMs;
    final float decayDeg;
    /** 同一排上一个脉冲留下的压力把这一个压低多少：1 − m × e^(−间隔 / {@link #recoveryDeg})。 */
    final float recoveryDeg;
    final float recoveryIdle;
    final float recoveryLoad;
    /** 每一下的随机起伏（标准差，怠速 / 满负荷 / 收油时另加）。 */
    final float sigmaIdle;
    final float sigmaLoad;
    final float sigmaOverrun;
    /** 每一下的时机随机偏多少（曲轴角的标准差，怠速 / 满负荷）。 */
    final float jitterIdleDeg;
    final float jitterLoadDeg;

    // ---- 气流噪声（「沙哑」）：白噪声 × 每一下点火踢起来的包络，进同一根排气管
    final float raspIdleDb;
    final float raspLoadDb;
    /** 收油（断油、只泵气）时的气流噪声（dB）。 */
    final float raspOverrunDb;
    /** 包络里不随点火起伏的那一份。 */
    final float raspFloor;
    final float raspDecayDeg;
    final float raspHighpassHz;
    /** 这个频率以下平、以上 −6 dB/倍频程；再留一点原样的白噪声（{@link #raspAir}）。 */
    final float raspTiltHz;
    final float raspAir;
    final float raspPeakHz;
    final float raspPeakDb;
    final float raspPeakQ;

    // ---- 排气管、消声器（每排一套）
    /** 排气管的共振基频（左、右排，Hz）：反馈梳状滤波器，环路里带低通（高频损耗大）。 */
    final float[] pipeHz;
    final float pipeFeedback;
    final float pipeLossHz;
    final float[] formantHz;
    final float[] formantDb;
    final float[] formantQ;
    /** 消声器加尾管的亥姆霍兹共振（左、右排，Hz）。 */
    final float[] helmholtzHz;
    final float helmholtzDb;
    final float helmholtzQ;
    /** 消声器的低通：怠速时 → 3000 rpm 以上（Hz），满负荷再开 {@link #mufflerLoad} 这么多。 */
    final float mufflerIdleHz;
    final float mufflerOpenHz;
    final float mufflerLoad;
    final float radiationHz;
    /** 压力大时的饱和（0 = 不饱和；怠速 / 满负荷）。 */
    final float driveIdle;
    final float driveLoad;
    /** 每排串到另一边的量；这个频率以下左右一样（车厢里低频本来就分不出左右）。 */
    final float cross;
    final float bassMonoHz;
    /** 怠速到 6000 rpm 多响多少（dB）。 */
    final float rpmLoudDb;
    /** 总的倍数：和经典风格一样响。 */
    final float norm;

    // ---- 进气
    final float intakeIdleDb;
    final float intakeLoadDb;
    final float plenumHz;
    final float plenumQ;
    final float runnerHz;
    final float runnerQ;
    final float roarIdleDb;
    final float roarLoadDb;
    final float roarHz;
    final float roarQ;

    // ---- 机械增压（阶次 0 = 没有）：啸叫跟着转速，增压随负荷，松油门旁通阀打开
    final float superchargerOrder;
    final float superchargerH2;
    final float superchargerH3;
    final float superchargerDb;
    /** 旁通阀开着（不增压）时还剩几成啸叫。 */
    final float superchargerFree;
    final float superchargerAm;
    final float superchargerSec;

    // ---- 涡轮（没有就是 false）：起压有迟滞、哨声随叶轮转速、松油门泄压、叶轮慢慢掉转速；哨声、嘶声、泄压不跟发动机电平
    final boolean turbo;
    final float spoolLowSec;
    final float spoolHighSec;
    final float spoolDownSec;
    final float turboFromRpm;
    final float turboSpanRpm;
    final float whistleLowHz;
    final float whistleHighHz;
    final float whistleDb;
    final float hissDb;
    final float hissHz;
    final float hissQ;
    final float blowOffDb;
    final float blowOffSec;
    final float blowOffHz;
    final float blowOffQ;
    final float dumpSec;
    final float closedSec;

    // ---- 气门、缸体、车厢
    final float tickDb;
    final float[] tickHz;
    final float[] tickQ;
    final float knockDb;
    final float[] knockHz;
    final float[] knockQ;
    final float[] cabinHz;
    final float[] cabinQ;
    final float[] cabinGain;
    /** 高 Q 的「和鸣」：某个阶次扫过它时响一下。 */
    final float[] sympatheticHz;
    final float[] sympatheticQ;
    final float[] sympatheticGain;

    // ---- 传动
    /** 变速箱齿轮啸叫的阶次（每个挡一个，避开发动机的整数、半阶次；连续走法没有挡位用 {@link #cvtOrder}）。 */
    final float[] gearOrders;
    final float cvtOrder;
    final float gearDb;
    final float diffHzPerKmh;
    final float diffDb;
    final float shaftHzPerKmh;
    final float shaftDb;

    // ---- 收油
    /** 断油时每一下只剩泵气，是燃烧时的几成。 */
    final float pump;
    final float popMax;
    final float popGain;
    final float popRiseMs;
    final float popDecayMs;

    private PulseProfile(Builder b) {
        firing = b.firing.clone();
        trim = b.trim.clone();
        headerMs = b.headerMs.clone();
        bankMs = b.bankMs.clone();
        exhaustOpenDeg = b.exhaustOpenDeg;
        exhaustCloseDeg = b.exhaustCloseDeg;
        intakeCloseDeg = b.intakeCloseDeg;
        burnDeg = b.burnDeg;
        riseIdleMs = b.riseIdleMs;
        riseLoadMs = b.riseLoadMs;
        decayDeg = b.decayDeg;
        recoveryDeg = b.recoveryDeg;
        recoveryIdle = b.recoveryIdle;
        recoveryLoad = b.recoveryLoad;
        sigmaIdle = b.sigmaIdle;
        sigmaLoad = b.sigmaLoad;
        sigmaOverrun = b.sigmaOverrun;
        jitterIdleDeg = b.jitterIdleDeg;
        jitterLoadDeg = b.jitterLoadDeg;
        raspIdleDb = b.raspIdleDb;
        raspLoadDb = b.raspLoadDb;
        raspOverrunDb = b.raspOverrunDb;
        raspFloor = b.raspFloor;
        raspDecayDeg = b.raspDecayDeg;
        raspHighpassHz = b.raspHighpassHz;
        raspTiltHz = b.raspTiltHz;
        raspAir = b.raspAir;
        raspPeakHz = b.raspPeakHz;
        raspPeakDb = b.raspPeakDb;
        raspPeakQ = b.raspPeakQ;
        pipeHz = b.pipeHz.clone();
        pipeFeedback = b.pipeFeedback;
        pipeLossHz = b.pipeLossHz;
        formantHz = b.formantHz.clone();
        formantDb = b.formantDb.clone();
        formantQ = b.formantQ.clone();
        helmholtzHz = b.helmholtzHz.clone();
        helmholtzDb = b.helmholtzDb;
        helmholtzQ = b.helmholtzQ;
        mufflerIdleHz = b.mufflerIdleHz;
        mufflerOpenHz = b.mufflerOpenHz;
        mufflerLoad = b.mufflerLoad;
        radiationHz = b.radiationHz;
        driveIdle = b.driveIdle;
        driveLoad = b.driveLoad;
        cross = b.cross;
        bassMonoHz = b.bassMonoHz;
        rpmLoudDb = b.rpmLoudDb;
        norm = b.norm;
        intakeIdleDb = b.intakeIdleDb;
        intakeLoadDb = b.intakeLoadDb;
        plenumHz = b.plenumHz;
        plenumQ = b.plenumQ;
        runnerHz = b.runnerHz;
        runnerQ = b.runnerQ;
        roarIdleDb = b.roarIdleDb;
        roarLoadDb = b.roarLoadDb;
        roarHz = b.roarHz;
        roarQ = b.roarQ;
        superchargerOrder = b.superchargerOrder;
        superchargerH2 = b.superchargerH2;
        superchargerH3 = b.superchargerH3;
        superchargerDb = b.superchargerDb;
        superchargerFree = b.superchargerFree;
        superchargerAm = b.superchargerAm;
        superchargerSec = b.superchargerSec;
        turbo = b.turbo;
        spoolLowSec = b.spoolLowSec;
        spoolHighSec = b.spoolHighSec;
        spoolDownSec = b.spoolDownSec;
        turboFromRpm = b.turboFromRpm;
        turboSpanRpm = b.turboSpanRpm;
        whistleLowHz = b.whistleLowHz;
        whistleHighHz = b.whistleHighHz;
        whistleDb = b.whistleDb;
        hissDb = b.hissDb;
        hissHz = b.hissHz;
        hissQ = b.hissQ;
        blowOffDb = b.blowOffDb;
        blowOffSec = b.blowOffSec;
        blowOffHz = b.blowOffHz;
        blowOffQ = b.blowOffQ;
        dumpSec = b.dumpSec;
        closedSec = b.closedSec;
        tickDb = b.tickDb;
        tickHz = b.tickHz.clone();
        tickQ = b.tickQ.clone();
        knockDb = b.knockDb;
        knockHz = b.knockHz.clone();
        knockQ = b.knockQ.clone();
        cabinHz = b.cabinHz.clone();
        cabinQ = b.cabinQ.clone();
        cabinGain = b.cabinGain.clone();
        sympatheticHz = b.sympatheticHz.clone();
        sympatheticQ = b.sympatheticQ.clone();
        sympatheticGain = b.sympatheticGain.clone();
        gearOrders = b.gearOrders.clone();
        cvtOrder = b.cvtOrder;
        gearDb = b.gearDb;
        diffHzPerKmh = b.diffHzPerKmh;
        diffDb = b.diffDb;
        shaftHzPerKmh = b.shaftHzPerKmh;
        shaftDb = b.shaftDb;
        pump = b.pump;
        popMax = b.popMax;
        popGain = b.popGain;
        popRiseMs = b.popRiseMs;
        popDecayMs = b.popDecayMs;
    }

    /** 机械增压交叉平面 V8（{@link SoundStyle#SUPERCHARGED}）：排气脉冲尖、沙哑足，啸叫随转速。 */
    static final PulseProfile SUPERCHARGED_V8 = new Builder()
            .supercharger(14.2f, 0.5f, 0.25f, -9f, 0.25f, 0.3f, 0.12f)
            .build();

    /**
     * 双涡轮交叉平面 V8（{@link SoundStyle#TWIN_TURBO}）：涡轮吃掉一部分脉冲（起得慢一点、没那么沙哑），
     * 起压有迟滞、哨声随叶轮转速、松油门泄压、叶轮慢慢掉转速。
     */
    static final PulseProfile TWIN_TURBO_V8 = new Builder()
            .pulse(0.55f, 0.25f, 18f)
            .rasp(-18f, -4f, -18f)
            .turbo(0.9f, 0.3f, 1.6f, 1200f, 2500f)
            .whistle(2500f, 6500f, -20f)
            .hiss(-16f, 1500f, 0.8f)
            .blowOff(-14f, 0.45f, 2600f, 0.9f, 0.15f, 0.6f)
            .build();

    /** 写风格用：默认值是机械增压那一份的发动机和排气，一项一项改，最后 {@link #build}。 */
    static final class Builder {
        private int[] firing = {1, 8, 4, 3, 6, 5, 7, 2};
        private float[] trim = {1.03f, 0.97f, 1.00f, 0.98f, 1.02f, 0.96f, 1.04f, 0.99f};
        private float[] headerMs = {0.10f, 0.29f, 0.24f, 0.14f, 0.17f, 0.26f, 0.31f, 0.11f};
        private float[] bankMs = {0f, 0.35f};
        private float exhaustOpenDeg = 130f;
        private float exhaustCloseDeg = 370f;
        private float intakeCloseDeg = 590f;
        private float burnDeg = 8f;
        private float riseIdleMs = 0.40f;
        private float riseLoadMs = 0.15f;
        private float decayDeg = 18f;
        private float recoveryDeg = 107f;
        private float recoveryIdle = 1.37f;
        private float recoveryLoad = 1.37f;
        private float sigmaIdle = 0.08f;
        private float sigmaLoad = 0.05f;
        private float sigmaOverrun = 0.30f;
        private float jitterIdleDeg = 1f;
        private float jitterLoadDeg = 0.5f;
        private float raspIdleDb = -16f;
        private float raspLoadDb = -1.5f;
        private float raspOverrunDb = -18f;
        private float raspFloor = 0.15f;
        private float raspDecayDeg = 45f;
        private float raspHighpassHz = 90f;
        private float raspTiltHz = 600f;
        private float raspAir = 0.12f;
        private float raspPeakHz = 1200f;
        private float raspPeakDb = 3f;
        private float raspPeakQ = 0.7f;
        private float[] pipeHz = {172f, 164f};
        private float pipeFeedback = 0.5f;
        private float pipeLossHz = 1500f;
        private float[] formantHz = {150f, 330f, 680f};
        private float[] formantDb = {8f, 2f, 2f};
        private float[] formantQ = {1.4f, 2f, 2.5f};
        private float[] helmholtzHz = {56f, 61f};
        private float helmholtzDb = 10f;
        private float helmholtzQ = 3.5f;
        private float mufflerIdleHz = 2200f;
        private float mufflerOpenHz = 5000f;
        private float mufflerLoad = 0.35f;
        private float radiationHz = 45f;
        private float driveIdle = 0.3f;
        private float driveLoad = 1.5f;
        private float cross = 0.35f;
        private float bassMonoHz = 120f;
        private float rpmLoudDb = 5f;
        private float norm = 0.79f;
        private float intakeIdleDb = -30f;
        private float intakeLoadDb = -12f;
        private float plenumHz = 95f;
        private float plenumQ = 2f;
        private float runnerHz = 420f;
        private float runnerQ = 4f;
        private float roarIdleDb = -40f;
        private float roarLoadDb = -14f;
        private float roarHz = 1800f;
        private float roarQ = 0.7f;
        private float superchargerOrder;
        private float superchargerH2;
        private float superchargerH3;
        private float superchargerDb = -100f;
        private float superchargerFree;
        private float superchargerAm;
        private float superchargerSec = 0.12f;
        private boolean turbo;
        private float spoolLowSec = 1f;
        private float spoolHighSec = 0.3f;
        private float spoolDownSec = 1.6f;
        private float turboFromRpm = 1200f;
        private float turboSpanRpm = 2500f;
        private float whistleLowHz = 2500f;
        private float whistleHighHz = 6500f;
        private float whistleDb = -100f;
        private float hissDb = -100f;
        private float hissHz = 1500f;
        private float hissQ = 0.8f;
        private float blowOffDb = -100f;
        private float blowOffSec = 0.45f;
        private float blowOffHz = 2600f;
        private float blowOffQ = 0.9f;
        private float dumpSec = 0.15f;
        private float closedSec = 0.6f;
        private float tickDb = -34f;
        private float[] tickHz = {3100f, 4800f};
        private float[] tickQ = {14f, 10f};
        private float knockDb = -26f;
        private float[] knockHz = {820f, 1450f, 2300f};
        private float[] knockQ = {6f, 7f, 8f};
        private float[] cabinHz = {42f, 63f, 96f, 135f};
        private float[] cabinQ = {5f, 6f, 5f, 4f};
        private float[] cabinGain = {0.8f, 0.7f, 0.5f, 0.4f};
        private float[] sympatheticHz = {333f};
        private float[] sympatheticQ = {30f};
        private float[] sympatheticGain = {0.15f};
        private float[] gearOrders = {35.3f, 30.2f, 27.4f, 25.1f, 23.3f, 21.8f, 20.6f, 19.7f};
        private float cvtOrder = 26.3f;
        private float gearDb = -36f;
        private float diffHzPerKmh = 4.16f;
        private float diffDb = -40f;
        private float shaftHzPerKmh = 0.69f;
        private float shaftDb = -36f;
        private float pump = 0.3f;
        private float popMax = 0.05f;
        private float popGain = 1.2f;
        private float popRiseMs = 0.3f;
        private float popDecayMs = 5f;

        Builder pulse(float riseIdleMs, float riseLoadMs, float decayDeg) {
            this.riseIdleMs = riseIdleMs;
            this.riseLoadMs = riseLoadMs;
            this.decayDeg = decayDeg;
            return this;
        }

        /** 每一下的随机起伏（标准差：怠速、满负荷、收油时另加）和时机的随机偏移（曲轴角：怠速、满负荷）。 */
        Builder variation(float sigmaIdle, float sigmaLoad, float sigmaOverrun, float jitterIdleDeg, float jitterLoadDeg) {
            this.sigmaIdle = sigmaIdle;
            this.sigmaLoad = sigmaLoad;
            this.sigmaOverrun = sigmaOverrun;
            this.jitterIdleDeg = jitterIdleDeg;
            this.jitterLoadDeg = jitterLoadDeg;
            return this;
        }

        /** 同一排上一个脉冲压低下一个：1 − m × e^(−间隔 / {@code deg})，m 怠速、满负荷各一个（0 = 不压）。 */
        Builder recovery(float deg, float idle, float load) {
            recoveryDeg = deg;
            recoveryIdle = idle;
            recoveryLoad = load;
            return this;
        }

        Builder rasp(float idleDb, float loadDb, float overrunDb) {
            raspIdleDb = idleDb;
            raspLoadDb = loadDb;
            raspOverrunDb = overrunDb;
            return this;
        }

        Builder supercharger(float order, float h2, float h3, float db, float free, float am, float sec) {
            superchargerOrder = order;
            superchargerH2 = h2;
            superchargerH3 = h3;
            superchargerDb = db;
            superchargerFree = free;
            superchargerAm = am;
            superchargerSec = sec;
            return this;
        }

        Builder turbo(float spoolLowSec, float spoolHighSec, float spoolDownSec, float fromRpm, float spanRpm) {
            turbo = true;
            this.spoolLowSec = spoolLowSec;
            this.spoolHighSec = spoolHighSec;
            this.spoolDownSec = spoolDownSec;
            turboFromRpm = fromRpm;
            turboSpanRpm = spanRpm;
            return this;
        }

        Builder whistle(float lowHz, float highHz, float db) {
            whistleLowHz = lowHz;
            whistleHighHz = highHz;
            whistleDb = db;
            return this;
        }

        Builder hiss(float db, float hz, float q) {
            hissDb = db;
            hissHz = hz;
            hissQ = q;
            return this;
        }

        Builder blowOff(float db, float sec, float hz, float q, float dumpSec, float closedSec) {
            blowOffDb = db;
            blowOffSec = sec;
            blowOffHz = hz;
            blowOffQ = q;
            this.dumpSec = dumpSec;
            this.closedSec = closedSec;
            return this;
        }

        PulseProfile build() {
            if (firing.length == 0 || trim.length < firing.length || headerMs.length < firing.length
                    || pipeHz.length != 2 || helmholtzHz.length != 2 || bankMs.length != 2) {
                throw new IllegalStateException("pulse profile incomplete");
            }
            for (int c : firing) {
                if (c < 1 || c > trim.length) {
                    throw new IllegalStateException("cylinder " + c);
                }
            }
            return new PulseProfile(this);
        }
    }
}
