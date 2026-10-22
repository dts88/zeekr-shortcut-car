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
    /**
     * 这个频率以下平、以上 −6 dB/倍频程；再留一点原样的白噪声（{@link #raspAir}）。拐点随转速（流速）往上走：
     * × (rpm / 2000)^{@link #raspTiltRpmPow}。
     */
    final float raspTiltHz;
    final float raspTiltRpmPow;
    final float raspAir;
    /**
     * 沙哑随流量（转速）变：每翻一倍转速多几 dB（带负荷、收油各一个），以 {@link #raspRefRpm} 为准，最多多 {@link #raspRpmMaxDb}。
     * 参考车的录音：收油时低转很闷、高转一下亮起来（每翻一倍 +12 dB）；带负荷时变亮主要靠消声器随转速打开。
     */
    final float[] raspRpmDb;
    final float raspRefRpm;
    final float raspRpmMaxDb;
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
    /**
     * 消声器的低通（Hz）= {@link #mufflerHz} × (rpm / 1000)^{@link #mufflerPow}，到 {@link #mufflerTopHz} 为止，满负荷再开
     * {@link #mufflerLoad} 这么多：一直开到红线（项目所有者 2026-10-21），系数照参考车的录音拟合。
     */
    final float mufflerHz;
    final float mufflerPow;
    final float mufflerTopHz;
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
    /**
     * 虚拟发动机给的电平取这个次方（车外、车内听感各一个）：踩油门主要变音色、响度变化不大 —— 发动机自己越转越响的那些
     * （燃烧、沙哑、增压器、消声器打开）已经带出好几 dB。项目所有者 2026-10-21 实车：响度差太大，要以层次和丰富程度做真实感，
     * 高速、大油门稍响一点就够；2026-10-22：油门、车速都还原平滑了，误当成急加速、急刹车的少了，响度差可以略放大一些。
     * 车内听感把高频滤掉了，自己带出来的少，次方大一些。升挡断扭时再掉 {@link #cutDipDb}（满负荷）。
     */
    final float levelPow;
    final float cabinLevelPow;
    final float cutDipDb;
    /** 总的倍数：全油门时限幅只压两三 dB（{@link Limiter}）。 */
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

    // ---- 机械增压（阶次 0 = 没有）：双螺杆，曲轴用皮带带着转。啸叫照车主偏好的那台的录音：叶片扫过出口的基频不响，
    //      响的是同步齿轮的啮合声（3 倍，第 2、3 次最响），很纯、只在增压时有；松油门旁通阀打开，齿轮声几乎没了，基频反而显出来。
    //      底下一层挤过窄出口通道的气流声、进口吸气的风声
    /** 叶片扫过出口的阶次（曲轴每转一圈扫过几下；录音里是 10.24 = 阴转子 5 叶 × 曲轴的 2.05 倍）。 */
    final float superchargerOrder;
    /** 叶片基频的电平（dB，3000 rpm，和负荷无关）。 */
    final float lobeDb;
    /** 齿轮啮合是叶片频率的几倍（阴转子上的齿数 / 叶片数）；各次谐波的电平（dB，相对第 2 次）。 */
    final float meshMultiple;
    final float[] meshHarmonicsDb;
    /** 阴、阳转子各几个叶片（转一圈扫过几下）；叶片之间细小的差别让每个转子每转一圈调制一次啸叫（边带），调制多深。 */
    final float[] rotorLobes;
    final float[] rotorAm;
    /** 满增压、3000 rpm 时齿轮声第 2 次的电平（dB）；几个音都随转速的 {@link #toneRpmPow} 次方变（怠速时很轻，和录音一样）。 */
    final float superchargerDb;
    final float toneRpmPow;
    /** 气流声随转速的这个次方变（以 6000 rpm 为准）：低转淡，高转长得快。 */
    final float superchargerRpmPow;
    /** 转速的细小抖动（份额、低通 Hz）：皮带、曲轴的扭振，啸叫不是一根死的正弦。 */
    final float superchargerWobble;
    final float superchargerWobbleHz;
    /** 旁通阀开着（不增压，齿轮不受力）时齿轮声还剩几成。 */
    final float superchargerFree;
    /** 被发动机的进气脉冲（4 阶）调制多深。 */
    final float superchargerAm;
    final float superchargerSec;
    /**
     * 挤过出口的气流（满增压、6000 rpm，dB）：白噪声每扫过一个叶片涌一下（{@link #jetPulse}），过出口通道、进气腔、
     * 中冷器的几个共振（Hz、Q、dB），再低通；左右两路不相关（两边各一个中冷器）。
     */
    final float jetDb;
    final float[] jetHz;
    final float[] jetQ;
    final float[] jetGainDb;
    final float jetPulse;
    final float airLowpassHz;
    /** 进口吸气的风声（满流量时的 dB、带通 Hz、Q），带阵风（低通 Hz、深浅）。 */
    final float inletDb;
    final float inletHz;
    final float inletQ;
    final float gustHz;
    final float gust;
    /** 车速 200 km/h 时进气的风声（进口风声、进气呼啸）多几成（× (1 + 它)，按车速的平方长）：越开越快的感觉。 */
    final float inletRam;

    // ---- 声音从哪来（驾驶座看；立体声 −1 左 … +1 右）
    /** 每个缸气门、燃烧的声音按排摆在左右多远；最靠后的缸离驾驶座多远（米）、缸距（米，1、2 缸在最前面）。 */
    final float cylinderPan;
    final float cylinderNearM;
    final float cylinderPitchM;
    /** 排气（尾管在车后两角）比发动机舱的声音晚到多久（毫秒）：管子里走一段、再从车后绕过来。 */
    final float exhaustLagMs;
    /** 两边中冷器出口的气流摆多开；进气口（左前）在哪。 */
    final float jetPan;
    final float inletPan;

    // ---- 换挡：传动的「咚」（地板中间，几个衰减的低频）+ 挂挡的「咔」（一小段带通噪声）
    final float[] thunkHz;
    final float[] thunkMs;
    final float[] thunkGainDb;
    /** 满负荷升挡时「咚」多响（dB，只跟总音量）；降挡七成。 */
    final float thunkDb;
    final float clickHz;
    final float clickQ;
    /** 「咔」相对「咚」（dB）。 */
    final float clickDb;
    /**
     * 点着、熄火停住那一刻发动机在支座上晃一下（项目所有者 2026-10-22：点火的体感再强些；熄火照车主偏好的那台）：
     * 一个衰减的低频（Hz、多快落下去 ms、多响 dB，只跟总音量）。
     */
    final float rockHz;
    final float rockMs;
    final float rockDb;

    // ---- 车内听感（开发者选项「听感」选「车内」）：发动机舱的声音隔着防火墙、排气隔着车身，各过两个一阶低通；
    //      低频多一点（车身传进来的）；总的再补一点，日常驾驶和车外差不多响
    final float[] cabinFrontHz;
    final float[] cabinRearHz;
    final float cabinLowHz;
    final float cabinLowDb;
    final float cabinGainDb;


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
        raspTiltRpmPow = b.raspTiltRpmPow;
        raspAir = b.raspAir;
        raspRpmDb = b.raspRpmDb.clone();
        raspRefRpm = b.raspRefRpm;
        raspRpmMaxDb = b.raspRpmMaxDb;
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
        mufflerHz = b.mufflerHz;
        mufflerPow = b.mufflerPow;
        mufflerTopHz = b.mufflerTopHz;
        mufflerLoad = b.mufflerLoad;
        radiationHz = b.radiationHz;
        driveIdle = b.driveIdle;
        driveLoad = b.driveLoad;
        cross = b.cross;
        bassMonoHz = b.bassMonoHz;
        rpmLoudDb = b.rpmLoudDb;
        levelPow = b.levelPow;
        cabinLevelPow = b.cabinLevelPow;
        cutDipDb = b.cutDipDb;
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
        lobeDb = b.lobeDb;
        meshMultiple = b.meshMultiple;
        meshHarmonicsDb = b.meshHarmonicsDb.clone();
        rotorLobes = b.rotorLobes.clone();
        rotorAm = b.rotorAm.clone();
        superchargerDb = b.superchargerDb;
        toneRpmPow = b.toneRpmPow;
        superchargerRpmPow = b.superchargerRpmPow;
        superchargerWobble = b.superchargerWobble;
        superchargerWobbleHz = b.superchargerWobbleHz;
        superchargerFree = b.superchargerFree;
        superchargerAm = b.superchargerAm;
        superchargerSec = b.superchargerSec;
        jetDb = b.jetDb;
        jetHz = b.jetHz.clone();
        jetQ = b.jetQ.clone();
        jetGainDb = b.jetGainDb.clone();
        jetPulse = b.jetPulse;
        airLowpassHz = b.airLowpassHz;
        inletDb = b.inletDb;
        inletHz = b.inletHz;
        inletQ = b.inletQ;
        gustHz = b.gustHz;
        gust = b.gust;
        inletRam = b.inletRam;
        cylinderPan = b.cylinderPan;
        cylinderNearM = b.cylinderNearM;
        cylinderPitchM = b.cylinderPitchM;
        exhaustLagMs = b.exhaustLagMs;
        jetPan = b.jetPan;
        inletPan = b.inletPan;
        thunkHz = b.thunkHz.clone();
        thunkMs = b.thunkMs.clone();
        thunkGainDb = b.thunkGainDb.clone();
        thunkDb = b.thunkDb;
        clickHz = b.clickHz;
        clickQ = b.clickQ;
        clickDb = b.clickDb;
        rockHz = b.rockHz;
        rockMs = b.rockMs;
        rockDb = b.rockDb;
        cabinFrontHz = b.cabinFrontHz.clone();
        cabinRearHz = b.cabinRearHz.clone();
        cabinLowHz = b.cabinLowHz;
        cabinLowDb = b.cabinLowDb;
        cabinGainDb = b.cabinGainDb;
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

    /**
     * 机械增压交叉平面 V8（「美式 V8」，{@link SoundStyle#AMERICAN}）：排气脉冲尖、沙哑足；双螺杆增压器的啸叫（齿轮声）和气流。
     * 增压器照车主偏好的那台的录音量（叶片 10.24 阶、齿轮 30.72 阶；项目所有者 2026-10-21：增压声要和它比）。
     */
    static final PulseProfile SUPERCHARGED_V8 = new Builder()
            .supercharger(10.24f, -20f)
            .loudness(0.15f, 0.42f, 0.5f, 0.56f)
            .build();

    /** 写风格用：默认值是机械增压那一份的发动机、排气和增压器（增压器要 {@link #supercharger} 才装上），一项一项改，最后 {@link #build}。 */
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
        private float raspTiltRpmPow = 0.5f;
        private float raspAir = 0.12f;
        private float[] raspRpmDb = {0f, 12f};
        private float raspRefRpm = 2850f;
        private float raspRpmMaxDb = 6f;
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
        private float mufflerHz = 2100f;
        private float mufflerPow = 0.9f;
        private float mufflerTopHz = 12000f;
        private float mufflerLoad = 0.35f;
        private float radiationHz = 45f;
        private float driveIdle = 0.3f;
        private float driveLoad = 1.5f;
        private float cross = 0.25f;
        private float bassMonoHz = 120f;
        private float rpmLoudDb = 5f;
        private float levelPow = 0.5f;
        private float cabinLevelPow = 0.5f;
        private float cutDipDb = -4.5f;
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
        private float lobeDb = -29f;
        private float meshMultiple = 3f;
        private float[] meshHarmonicsDb = {-2f, 0f, -3f, -16f, -14f};
        private float toneRpmPow = 0.8f;
        private float[] rotorLobes = {5f, 3f};
        private float[] rotorAm = {0.2f, 0.15f};
        private float superchargerDb = -100f;
        private float superchargerRpmPow = 1.7f;
        private float superchargerWobble = 0.002f;
        private float superchargerWobbleHz = 30f;
        private float superchargerFree = 0.03f;
        private float superchargerAm = 0.25f;
        private float superchargerSec = 0.12f;
        private float jetDb = -12f;
        private float[] jetHz = {1800f, 3200f, 5500f};
        private float[] jetQ = {1.2f, 1.4f, 1.6f};
        private float[] jetGainDb = {0f, -1f, -6f};
        private float jetPulse = 0.8f;
        private float airLowpassHz = 7000f;
        private float inletDb = -13f;
        private float inletHz = 1600f;
        private float inletQ = 0.7f;
        private float gustHz = 5f;
        private float gust = 0.2f;
        private float inletRam = 3f;
        private float cylinderPan = 0.45f;
        private float cylinderNearM = 1.7f;
        private float cylinderPitchM = 0.12f;
        private float exhaustLagMs = 10f;
        private float jetPan = 0.5f;
        private float inletPan = -0.3f;
        private float[] thunkHz = {55f, 140f};
        private float[] thunkMs = {35f, 12f};
        private float[] thunkGainDb = {0f, -8f};
        private float thunkDb = -11f;
        private float rockHz = 40f;
        private float rockMs = 90f;
        private float rockDb = -13f;
        private float clickHz = 2200f;
        private float clickQ = 1.5f;
        private float clickDb = -14f;
        private float[] cabinFrontHz = {600f, 3500f};
        private float[] cabinRearHz = {350f, 2000f};
        private float cabinLowHz = 100f;
        private float cabinLowDb = 4f;
        private float cabinGainDb = 3.5f;
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

        /** 装一台机械增压：叶片扫过出口的阶次、满增压 3000 rpm 时齿轮声第 2 次的电平（dB）；基频、齿轮、转子、气流、进口用默认的那一份。 */
        /** 电平取的次方（车外、车内听感）、怠速到 6000 rpm 多响多少（dB）、总的倍数。 */
        Builder loudness(float levelPow, float cabinLevelPow, float rpmLoudDb, float norm) {
            this.levelPow = levelPow;
            this.cabinLevelPow = cabinLevelPow;
            this.rpmLoudDb = rpmLoudDb;
            this.norm = norm;
            return this;
        }

        Builder supercharger(float order, float db) {
            superchargerOrder = order;
            superchargerDb = db;
            return this;
        }

        PulseProfile build() {
            if (firing.length == 0 || trim.length < firing.length || headerMs.length < firing.length
                    || pipeHz.length != 2 || helmholtzHz.length != 2 || bankMs.length != 2
                    || rotorLobes.length != 2 || rotorAm.length != 2
                    || jetQ.length != jetHz.length || jetGainDb.length != jetHz.length || raspRpmDb.length != 2
                    || thunkMs.length != thunkHz.length || thunkGainDb.length != thunkHz.length
                    || cabinFrontHz.length != 2 || cabinRearHz.length != 2) {
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
