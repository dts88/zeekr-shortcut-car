package com.kooo.evcam.sound;

/**
 * 「涡轮四缸」风格（{@link SoundStyle#TURBO4}，试制。项目所有者 2026-10-23：参考车三种主题里模仿汽油机的那一种，照「超音速」的做法仿制；
 * 参考车说它沿用了自家 2.0 升涡轮四缸性能车的招牌声音）：一台涡轮增压直列四缸、带阀门的运动排气，照发动机的结构逐缸合成，按车内喇叭
 * 播放来做。纯计算（{@code TurboFourVoiceTest}），声音线程上一块一块地调，循环里不分配内存。量过的参考在 docs/local/drive-sound-plan.md §13。
 *
 * <ul>
 *   <li><b>四个缸</b>（点火顺序 1-3-4-2，每 180° 一下）：排气门一开，往排气歧管里打一个压力脉冲（起得快、按曲轴角落下去）。
 *       双涡管：1、4 缸一路，2、3 缸一路，在涡轮那里合成一根排气管。缸和缸的细小差别、歧管和两路涡管的长短让半阶次出来 ——
 *       参考里点火阶次（2、4、6、8 阶）最响，半阶次只低 13–30 dB，一整排密密的线。</li>
 *   <li><b>涡轮</b>：排气推着涡轮转。增压越高，涡轮吃掉的脉冲能量越多（脉冲变弱、变钝）；增压跟着负荷走，转速低时起得慢（排气不够），
 *       松油门时旁通阀一开很快泄掉。涡轮轴有惯性，松油门后还转一会儿：压气机很轻的哨声跟着它的转速升降；松油门时回流阀放一下气，
 *       轻轻「呼」一声。</li>
 *   <li><b>气流噪声</b>（沙哑）：每一下点火把一段噪声踢起来，进同一根排气管；涡轮后面比自然吸气的小得多（参考里线和线之间的
 *       底子比最响的线低 25–40 dB）。</li>
 *   <li><b>排气</b>：一根排气管（反馈梳状滤波器给出管子的共振）、中段和尾段消声器的几个共振峰，消声器的低通随流量（转速、负荷）打开
 *       （运动模式阀门开着）—— 参考全油门 5000 rpm 时最响的在 300–450 Hz，7000 rpm 时在 600–950 Hz。压力大时轻微饱和。
 *       最后分成左右两根尾管（长短、尾嘴的共振略不同）：低频左右一样，高频各自不同。</li>
 *   <li><b>收油</b>（断油）：每一下只剩泵气；刚松油门、转速高的那一两秒，排气里偶尔「噗」「啪」一下（没烧完的油在排气里烧掉，
 *       参考车放在后面的喇叭里），降挡补油时也有几下。参考里是稀稀拉拉的几下（每秒几下），不是一长串爆豆。</li>
 *   <li><b>换挡</b>（参考车的 e-Shift 模拟 8 速双离合）：升挡断扭时燃烧减弱、电平掉一下（参考掉 4–7 dB、一百多毫秒），开头「啪」一声；
 *       降挡补油，排气里噼啪几下。</li>
 *   <li><b>点着、熄火</b>：点着那一刻头几圈偶尔缺火，发动机在支座上晃一下；没点火（熄火转着停下来、行驶中刚带起来）只有压缩的「噗」。</li>
 * </ul>
 *
 * <p>没有窄带的随机起伏、没有挨得近又一样强的线（「超音速」那里车主听出的颗粒、一串一串）：线就是发动机的阶次，随机的只有每一下点火的
 * 强弱和时机（发动机本来就有的粗糙），和宽带的气流噪声。电平、音量逐样本平滑，最后过先看一步的限幅（{@link Limiter}），
 * 峰值不超过 {@link EngineVoice#CEILING}。</p>
 */
final class TurboFourVoice implements Voice {

    /** 激励的环形缓冲（样本，2 的幂）：事件往后写（歧管、随机偏移），每个样本读走当前这一格。 */
    private static final int RING = 2048;
    private static final int MASK = RING - 1;
    private static final float MAX_DELAY = RING - 64;
    /** 排气管的延迟线（样本，2 的幂）。 */
    private static final int PIPE = 1024;
    private static final int PIPE_MASK = PIPE - 1;
    /** 尾管的延迟线（样本，2 的幂）。 */
    private static final int TIP = 64;
    private static final int TIP_MASK = TIP - 1;

    // ---- 发动机
    /** 点火顺序（缸号）；每个缸的脉冲强弱（下标 = 缸号 − 1，制造上的细小差别）、排气歧管比最短的那根多走多久（毫秒）。 */
    static final int[] FIRING = {1, 3, 4, 2};
    static final float[] TRIM = {1.08f, 0.93f, 1.04f, 0.96f};
    static final float[] HEADER_MS = {0.10f, 0.34f, 0.16f, 0.28f};
    /** 双涡管：1、4 缸走第一路，2、3 缸走第二路；第二路到涡轮多走多久（毫秒）。 */
    static final float SCROLL_MS = 0.05f;
    /** 上止点之后多少度排气门打开（排气脉冲）。 */
    static final float EXHAUST_OPEN_DEG = 130f;
    /** 排气脉冲：起来的时间（毫秒，怠速 / 满负荷）、按曲轴角落下去。 */
    static final float RISE_IDLE_MS = 0.45f;
    static final float RISE_LOAD_MS = 0.12f;
    static final float DECAY_DEG = 12f;
    /** 每一下的随机起伏（标准差：怠速、满负荷、收油时另加）、时机的随机偏移（曲轴角：怠速、满负荷）。 */
    static final float SIGMA_IDLE = 0.07f;
    static final float SIGMA_LOAD = 0.04f;
    static final float SIGMA_OVERRUN = 0.25f;
    static final float JITTER_IDLE_DEG = 1.2f;
    static final float JITTER_LOAD_DEG = 0.5f;
    /** 断油时每一下只剩泵气，是燃烧时的几成。 */
    static final float PUMP = 0.28f;

    // ---- 涡轮
    /** 排气够推动涡轮的转速：从这里起有增压，到那里满。 */
    static final float SPOOL_FROM_RPM = 1300f;
    static final float SPOOL_FULL_RPM = 2600f;
    /** 增压起来的时间（秒，低转 / 高转）、泄掉的时间。 */
    static final float BOOST_UP_LOW_SEC = 0.9f;
    static final float BOOST_UP_HIGH_SEC = 0.25f;
    static final float BOOST_DOWN_SEC = 0.15f;
    /** 涡轮轴：怠速、不增压时的转速（份额，跟着发动机转速）；转起来、慢下来的时间（秒，惯性）。 */
    static final float SHAFT_FREE = 0.3f;
    static final float SHAFT_UP_SEC = 0.35f;
    static final float SHAFT_DOWN_SEC = 1.6f;
    /** 满增压时涡轮吃掉脉冲的几成、脉冲起来慢多少（× (1 + 它)）。 */
    static final float TURBINE_ABSORB = 0.3f;
    static final float TURBINE_SMOOTH = 0.5f;
    /** 压气机的哨声（Hz：轴转速 0 → 1）、电平（dB，满转、满增压）、二次谐波（相对 dB）。 */
    static final float WHISTLE_LOW_HZ = 1800f;
    static final float WHISTLE_HIGH_HZ = 6200f;
    static final float WHISTLE_DB = -34f;
    static final float WHISTLE_SECOND_DB = -14f;
    /** 松油门时回流阀放气的「呼」（带通 Hz、Q、dB、多快淡掉）；负荷一下掉多少算松油门、增压多少以上才有。 */
    static final float WHOOSH_HZ = 1400f;
    static final float WHOOSH_Q = 0.8f;
    static final float WHOOSH_DB = -30f;
    static final float WHOOSH_SEC = 0.3f;
    static final float LIFT_DROP = 0.25f;
    static final float LIFT_PEAK_SEC = 1.5f;
    static final float WHOOSH_FROM = 0.3f;

    // ---- 气流噪声：白噪声 × 每一下点火踢起来的包络（加一点不随点火起伏的），高通、一阶低通的拐点随转速往上走
    static final float RASP_IDLE_DB = -24f;
    static final float RASP_LOAD_DB = -14f;
    static final float RASP_OVERRUN_DB = -28f;
    static final float RASP_DECAY_DEG = 40f;
    static final float RASP_FLOOR = 0.2f;
    static final float RASP_HIGHPASS_HZ = 150f;
    static final float RASP_TILT_HZ = 900f;
    static final float RASP_TILT_TOP_HZ = 4000f;

    // ---- 排气管、消声器、尾管
    /**
     * 排气管里的气体温度（K：冷、热）：声速跟着温度的平方根走，管子、消声器的共振一起往上移（下面的 Hz 是 {@link #GAS_REF_K} 时的）。
     * 温度跟着负荷、转速（{@link #HEAT_LOAD} : 1 − 它），升温、降温都要一会儿（秒）：全油门一路拉上去，音色跟着「打开」——
     * 参考全油门 5000 rpm 时最响的在 300–450 Hz，6500–7000 rpm 时在 600–950 Hz。
     */
    static final float GAS_COLD_K = 500f;
    static final float GAS_HOT_K = 900f;
    static final float GAS_REF_K = 700f;
    static final float HEAT_LOAD = 0.6f;
    static final float HEAT_UP_SEC = 1.5f;
    static final float HEAT_DOWN_SEC = 3f;
    /** 排气管的共振基频（Hz）、反馈、环路里的损耗（低通 Hz）。 */
    static final float PIPE_HZ = 118f;
    static final float PIPE_FEEDBACK = 0.45f;
    static final float PIPE_LOSS_HZ = 1400f;
    /** 中段、尾段消声器的共振峰（Hz、dB、Q）。 */
    static final float[] FORMANT_HZ = {380f, 650f, 1150f};
    static final float[] FORMANT_DB = {-3f, 8f, -3f};
    static final float[] FORMANT_Q = {1.0f, 1.3f, 1.0f};
    /**
     * 消声器的低通（两级，吸音棉对高频吃得狠）= 它 × (rpm / 2000)^{@link #MUFFLER_POW} × (1 + {@link #MUFFLER_LOAD} × 负荷)，
     * 到 {@link #MUFFLER_TOP_HZ} 为止：参考 1–3 kHz 比 300–1000 Hz 低约 15 dB，3–8 kHz 低 30 dB 以上。
     */
    static final float MUFFLER_HZ = 480f;
    static final float MUFFLER_POW = 0.6f;
    static final float MUFFLER_LOAD = 0.3f;
    static final float MUFFLER_TOP_HZ = 1600f;
    static final float RADIATION_HZ = 70f;
    /** 压力大时的饱和（怠速 / 满负荷；0 = 不饱和）。 */
    static final float DRIVE_IDLE = 0.3f;
    static final float DRIVE_LOAD = 1.2f;
    /**
     * 两根尾管（左、右）：多走多久（毫秒）、尾嘴的共振（Hz）；共振多少 dB、Q；串到另一边多少；这个频率以下左右一样。
     * 长短只差一点：车里左右喇叭的声音会叠在一起，差得多了叠出梳状的凹口（0.35 ms 在 1.4 kHz 挖掉一道）。
     */
    static final float[] TIP_MS = {0f, 0.08f};
    static final float[] TIP_HZ = {1900f, 2200f};
    static final float TIP_DB = 2.5f;
    static final float TIP_Q = 3f;
    static final float CROSS = 0.25f;
    static final float BASS_MONO_HZ = 150f;
    /**
     * 尾管出口的湍流噪声（两根各自独立，左右的宽度主要靠它）：满流量时多响（dB，相对一下排气脉冲）、高通、两级一阶低通（Hz）；
     * 流量 = 转速 / 6500 ×（{@link #EXIT_IDLE_FLOW} + 其余 × 负荷），电平按流量的平方。
     */
    static final float EXIT_DB = -14f;
    static final float EXIT_HIGHPASS_HZ = 400f;
    static final float EXIT_TILT_HZ = 1500f;
    static final float EXIT_IDLE_FLOW = 0.3f;

    // ---- 放炮：没烧完的油在排气里烧掉（尖、短，过消声器后段，左右随机偏一点）
    /** 松油门后多久淡掉（秒）；转速从多少起才有、到多少满。 */
    static final float BURBLE_SEC = 1.2f;
    static final float BURBLE_FROM_RPM = 2500f;
    static final float BURBLE_FULL_RPM = 5000f;
    /** 每一下排气放一炮的机会（满的时候）；还在踩着油门时只有它的几成。 */
    static final float POP_CHANCE = 0.035f;
    static final float POP_ON_THROTTLE = 0.2f;
    /** 一炮多响（dB，相对一下排气脉冲）、起来、落下去（毫秒）；比排气脉冲晚多久、随机再晚多少（毫秒）；左右偏多少；自己的低通（Hz）。 */
    static final float POP_DB = -2f;
    static final float POP_RISE_MS = 0.15f;
    static final float POP_DECAY_MS = 3.5f;
    static final float POP_LAG_MS = 2f;
    static final float POP_SPREAD_MS = 6f;
    static final float POP_PAN = 0.35f;
    static final float POP_LOWPASS_HZ = 4500f;
    /** 一炮带起来的一小段噪声（相对一炮的 dB、多快落下去 毫秒）。 */
    static final float CRACKLE_DB = -10f;
    static final float CRACKLE_MS = 5f;
    /** 降挡补油：放炮的份额（满 = 1）；升挡断扭开头那一声「啪」（× 一炮）。 */
    static final float DOWNSHIFT_BURBLE = 0.7f;
    static final float UPSHIFT_BANG = 1.2f;

    // ---- 换挡
    /** 升挡断扭时燃烧还剩几成；电平再掉多少（dB，满的断扭）：合起来掉 6 dB 左右（参考掉 4–7 dB、一百多毫秒）。 */
    static final float CUT_FLOOR = 0.55f;
    static final float CUT_DIP_DB = -2f;

    // ---- 点着、没点火
    static final float MISFIRE = 0.3f;
    static final float CATCH_SEC = 0.25f;
    static final float UNFIRED_LEVEL = 0.35f;
    static final float UNFIRED_FULL_RPM = 300f;
    /** 发动机在支座上晃一下：Hz、多快落下去（毫秒）、多响（dB，只跟总音量）；行驶中点着、停住时是它的几成。 */
    static final float ROCK_HZ = 38f;
    static final float ROCK_MS = 85f;
    static final float ROCK_DB = -14f;
    static final float ROCK_ATTACK_SEC = 0.003f;
    static final float ROLL_ROCK = 0.6f;
    static final float SETTLE_ROCK = 0.35f;

    // ---- 输出
    /**
     * 虚拟发动机的电平取这个次方（踩油门主要变音色，和「美式 V8」的 0.15 差不多：城市里和它一样响）；怠速到 6000 rpm 多响多少（dB）；
     * 总的倍数（全油门时限幅只压两三 dB）。
     */
    static final float LEVEL_POW = 0.2f;
    static final float RPM_LOUD_DB = 2f;
    static final float NORM = 0.75f;

    private final float rate;

    // ---- 曲轴和事件
    private final float[] evAngle = new float[FIRING.length];
    private final int[] evCyl = new int[FIRING.length];
    private int nextEvent;
    private double theta;
    private double cycleBase;
    private double nextAngle;
    private final float[] headerSamples = new float[TRIM.length];

    // ---- 激励
    private final float[] exc = new float[RING];
    private final float[] kick = new float[RING];
    private final float[][] pop = new float[2][RING];
    private final float[] crackle = new float[RING];
    private int cursor;

    // ---- 这一块里事件用的量
    private float evCombustion;
    private float evSigma;
    private float evMisfire;
    private float evPop;
    private float evRasp;
    private float evJitter;
    private float evSamplesPerDeg;
    private float evAbsorb;

    // ---- 排气
    private float pulseDecay;
    private float pulseRise;
    private float envelope;
    private float crackleEnv;
    private float tilt;
    private float tiltK;
    private float tiltNorm;
    private final Biquad raspHighpass;
    private final float[] pipe = new float[PIPE];
    private float pipeDelay;
    private final float pipeK;
    private float heat = -1f;
    private float pipeLoss;
    private int pipeWrite;
    private final Biquad[] formants = new Biquad[FORMANT_HZ.length];
    private final Biquad muffler;
    private final Biquad muffler2;
    private final Biquad radiation;
    private final float[] tipLine = new float[TIP];
    private int tipWrite;
    private final float[] tipDelay = new float[2];
    private final Biquad[] tipPeak = new Biquad[2];
    private final Biquad[] exitHighpass = new Biquad[2];
    private final float[][] exitTilt = new float[2][2];
    private final float exitK;
    private float exitNow;
    private final float[] popDecay = new float[2];
    private final float[] popRise = new float[2];
    private final float popDecayA;
    private final float popRiseA;
    private final Biquad[] popLowpass = new Biquad[2];
    private final float crackleDecay;

    // ---- 涡轮
    private float boost;
    private float shaft;
    private float loadPeak;
    private float whoosh;
    private float whooshNow;
    private final Biquad whooshBand;
    private double whistlePhase;
    private float whistleNow;
    private float whistleHzNow;
    private float burble;

    // ---- 换挡、点着
    private boolean serialSeen;
    private int lastSerial;
    private int lastCatch;
    private float catchEnv;
    private float rockEnv;
    private float rockNow;
    private double rockPhase;
    private final float rockDecay;
    private final float rockAttackK;
    private final float rockStep;

    // ---- 输出
    private final Biquad sideHighpass;
    private final Biquad[] highpass = new Biquad[2];
    private final Biquad[] tone = new Biquad[2];
    private float toneSetHz = -1f;
    private final Limiter limiter;
    private final float ampK;
    private final float gainK;
    private float rpmNow = -1f;
    private float levelNow;
    private float gainNow;
    private int noise = 0x3C6EF372;

    TurboFourVoice(float sampleRate) {
        this.rate = sampleRate;
        int n = FIRING.length;
        for (int k = 0; k < n; k++) {
            int c = FIRING[k];
            evAngle[k] = (720f * k / n + EXHAUST_OPEN_DEG) % 720f;
            evCyl[k] = c;
        }
        sortEvents();
        nextAngle = evAngle[0];
        for (int c = 1; c <= TRIM.length; c++) {
            // 1、4 缸一路，2、3 缸一路
            float scroll = c == 1 || c == 4 ? 0f : SCROLL_MS;
            headerSamples[c - 1] = (HEADER_MS[c - 1] + scroll) * 1e-3f * sampleRate;
        }
        setTilt(RASP_TILT_HZ);
        raspHighpass = Biquad.highpass(RASP_HIGHPASS_HZ, 0.7071f, sampleRate);
        pipeK = onePole(PIPE_LOSS_HZ);
        for (int k = 0; k < FORMANT_HZ.length; k++) {
            formants[k] = Biquad.peaking(FORMANT_HZ[k], FORMANT_DB[k], FORMANT_Q[k], sampleRate);
        }
        muffler = Biquad.lowpass(MUFFLER_HZ, 0.6f, sampleRate);
        muffler2 = Biquad.lowpass(MUFFLER_HZ, 0.6f, sampleRate);
        radiation = Biquad.highpass(RADIATION_HZ, 0.6f, sampleRate);
        for (int c = 0; c < 2; c++) {
            tipDelay[c] = Math.min(TIP - 2, TIP_MS[c] * 1e-3f * sampleRate);
            tipPeak[c] = Biquad.peaking(TIP_HZ[c], TIP_DB, TIP_Q, sampleRate);
            popLowpass[c] = Biquad.lowpass(POP_LOWPASS_HZ, 0.7071f, sampleRate);
            exitHighpass[c] = Biquad.highpass(EXIT_HIGHPASS_HZ, 0.7071f, sampleRate);
            highpass[c] = Biquad.highpass(EngineVoice.HIGHPASS_HZ, 0.7071f, sampleRate);
            tone[c] = new Biquad();
        }
        exitK = onePole(EXIT_TILT_HZ);
        popDecayA = perSample(POP_DECAY_MS * 1e-3f);
        popRiseA = perSample(POP_RISE_MS * 1e-3f);
        crackleDecay = perSample(CRACKLE_MS * 1e-3f);
        whooshBand = Biquad.bandpass(WHOOSH_HZ, WHOOSH_Q, sampleRate);
        whistleHzNow = WHISTLE_LOW_HZ;
        rockDecay = perSample(ROCK_MS * 1e-3f);
        rockAttackK = 1f - perSample(ROCK_ATTACK_SEC);
        rockStep = ROCK_HZ / sampleRate;
        sideHighpass = Biquad.highpass(BASS_MONO_HZ, 0.7071f, sampleRate);
        limiter = new Limiter(sampleRate);
        ampK = 1f - (float) Math.exp(-1.0 / (EngineVoice.AMP_SMOOTH_SEC * sampleRate));
        gainK = 1f - (float) Math.exp(-1.0 / (EngineVoice.GAIN_SMOOTH_SEC * sampleRate));
    }

    @Override
    public void renderStereo(float[] lr, int frames, VoiceParams params) {
        if (frames <= 0) {
            return;
        }
        float rpmTarget = params.rpm > 0f ? params.rpm : 0f;
        if (rpmNow < 0f) {
            rpmNow = rpmTarget;
        }
        if (params.gain <= 0f && gainNow < 1e-5f) {
            // 静音着：不算，曲轴停在原地
            for (int i = 0; i < 2 * frames; i++) {
                lr[i] = 0f;
            }
            gainNow = 0f;
            rpmNow = rpmTarget;
            limiter.reset();
            return;
        }
        float dt = frames / rate;
        float load = VirtualEngine.clamp01(params.load);
        float over = VirtualEngine.clamp01(params.overrun);
        float cut = VirtualEngine.clamp01(params.cut);
        boolean cranking = params.starter > 0.5f;
        boolean unfired = cranking || params.fire < 0.5f;
        boolean moving = params.speedKmh > VirtualEngine.START_LEAVE_KMH;
        if (params.catchSerial != lastCatch) {
            lastCatch = params.catchSerial;
            catchEnv = 1f;
            rock(moving ? ROLL_ROCK : 1f);
        }
        if (unfired && !cranking && rpmTarget <= 0f && rpmNow > 0f) {
            // 熄火停住那一刻再晃一下
            rock(SETTLE_ROCK);
        }
        catchEnv *= VirtualEngine.decay(dt, CATCH_SEC);
        float rpmCalc = Math.max(rpmTarget, 100f);

        // ---- 涡轮：增压跟着负荷（排气够才起得来），轴有惯性；松油门 = 负荷比最近的峰值掉了一截
        float flow = VirtualEngine.clamp01((rpmTarget - SPOOL_FROM_RPM) / (SPOOL_FULL_RPM - SPOOL_FROM_RPM));
        float boostTarget = unfired ? 0f : load * flow;
        float upSec = BOOST_UP_LOW_SEC + (BOOST_UP_HIGH_SEC - BOOST_UP_LOW_SEC) * VirtualEngine.clamp01(rpmTarget / 6000f);
        boost += (boostTarget - boost) * VirtualEngine.follow(dt, boostTarget > boost ? upSec : BOOST_DOWN_SEC);
        float shaftTarget = SHAFT_FREE * VirtualEngine.clamp01(rpmTarget / 7000f) + (1f - SHAFT_FREE) * boost;
        shaft += (shaftTarget - shaft) * VirtualEngine.follow(dt, shaftTarget > shaft ? SHAFT_UP_SEC : SHAFT_DOWN_SEC);
        loadPeak = Math.max(load, loadPeak * VirtualEngine.decay(dt, LIFT_PEAK_SEC));
        float drop = loadPeak - load;
        boolean lift = drop > LIFT_DROP;
        float revs = VirtualEngine.clamp01((rpmTarget - BURBLE_FROM_RPM) / (BURBLE_FULL_RPM - BURBLE_FROM_RPM));
        if (lift) {
            if (boost > WHOOSH_FROM) {
                whoosh = Math.max(whoosh, boost);
            }
            burble = Math.max(burble, revs * Math.min(1f, loadPeak + 0.3f));
            loadPeak = load;
        }
        burble = Math.max(burble, revs * VirtualEngine.clamp01(params.burble));
        whoosh *= VirtualEngine.decay(dt, WHOOSH_SEC);

        // ---- 换挡：升挡断扭开头「啪」一声，降挡补油噼啪几下
        if (!serialSeen) {
            serialSeen = true;
            lastSerial = params.shiftSerial;
        } else if (params.shiftSerial != lastSerial) {
            lastSerial = params.shiftSerial;
            if (params.shiftUp) {
                bang(UPSHIFT_BANG * (0.4f + 0.6f * load));
            } else {
                burble = Math.max(burble, DOWNSHIFT_BURBLE * Math.max(revs, 0.3f));
            }
        }

        // ---- 这一块里的事件
        evCombustion = cranking ? 0.15f
                : unfired ? UNFIRED_LEVEL * VirtualEngine.clamp01(rpmTarget / UNFIRED_FULL_RPM)
                : (1f - over * (1f - PUMP)) * (1f - cut * (1f - CUT_FLOOR));
        evSigma = mix(SIGMA_IDLE, SIGMA_LOAD, load) + over * SIGMA_OVERRUN;
        evMisfire = MISFIRE * catchEnv;
        float onThrottle = load > 0.15f && over < 0.3f ? POP_ON_THROTTLE : 1f;
        evPop = unfired ? 0f : POP_CHANCE * burble * onThrottle;
        evRasp = EngineVoice.dbToLin(mix(mix(RASP_IDLE_DB, RASP_LOAD_DB, load), RASP_OVERRUN_DB, over));
        evJitter = mix(JITTER_IDLE_DEG, JITTER_LOAD_DEG, load);
        evSamplesPerDeg = rate / (6f * Math.max(rpmTarget, 50f));
        evAbsorb = 1f - TURBINE_ABSORB * boost;
        burble *= VirtualEngine.decay(dt, BURBLE_SEC);

        // ---- 排气的系数：涡轮让脉冲起得慢一点
        float decaySec = DECAY_DEG / (6f * rpmCalc);
        float riseSec = mix(RISE_IDLE_MS, RISE_LOAD_MS, load) * 1e-3f * (1f + TURBINE_SMOOTH * boost) * (1f + over);
        if (riseSec > decaySec * 0.5f) {
            riseSec = decaySec * 0.5f;
        }
        float ad = perSample(decaySec);
        float ar = perSample(riseSec);
        double tp = Math.log(decaySec / riseSec) * decaySec * riseSec / (decaySec - riseSec);
        float pulseNorm = (float) (1.0 / (Math.exp(-tp / decaySec) - Math.exp(-tp / riseSec)));
        float ae = perSample(RASP_DECAY_DEG / (6f * rpmCalc));
        float bright = 1f + params.brightDb / 6f;
        float mufflerHz = Math.min(MUFFLER_HZ * (float) Math.pow(Math.max(rpmTarget, 500f) / 2000f, MUFFLER_POW), MUFFLER_TOP_HZ)
                * (1f + MUFFLER_LOAD * load) * bright;
        muffler.setLowpass(Math.min(mufflerHz, 0.45f * rate), 0.6f, rate);
        muffler2.setLowpass(Math.min(mufflerHz, 0.45f * rate), 0.6f, rate);
        // 排气温度：管子、消声器的共振跟着声速走
        float heatTarget = VirtualEngine.clamp01(HEAT_LOAD * load + (1f - HEAT_LOAD) * rpmTarget / 6500f);
        if (heat < 0f) {
            heat = heatTarget;
        }
        heat += (heatTarget - heat) * VirtualEngine.follow(dt, heatTarget > heat ? HEAT_UP_SEC : HEAT_DOWN_SEC);
        float sound = (float) Math.sqrt((GAS_COLD_K + (GAS_HOT_K - GAS_COLD_K) * heat) / GAS_REF_K);
        for (int k = 0; k < FORMANT_HZ.length; k++) {
            formants[k].setPeaking(Math.min(FORMANT_HZ[k] * sound, 0.45f * rate), FORMANT_DB[k], FORMANT_Q[k], rate);
        }
        pipeDelay = Math.max(1f, Math.min(PIPE - 2, rate / (PIPE_HZ * sound)));
        setTilt(Math.min(RASP_TILT_HZ * (float) Math.sqrt(Math.max(rpmCalc, 300f) / 2000f), RASP_TILT_TOP_HZ));
        float drive = mix(DRIVE_IDLE, DRIVE_LOAD, load);
        float floor = RASP_FLOOR * evRasp * (unfired ? 0.2f : 1f);
        float kickShare = 1f - RASP_FLOOR;
        float popGain = EngineVoice.dbToLin(POP_DB);
        float exitFlow = VirtualEngine.clamp01(rpmTarget / 6500f) * (EXIT_IDLE_FLOW + (1f - EXIT_IDLE_FLOW) * load) * evCombustion;
        float exitTarget = EngineVoice.dbToLin(EXIT_DB) * exitFlow * exitFlow;

        // ---- 涡轮的哨声、放气
        float whistleTarget = EngineVoice.dbToLin(WHISTLE_DB) * shaft * shaft * (0.4f + 0.6f * boost) * (unfired ? 0f : 1f);
        float whistleHz = WHISTLE_LOW_HZ + (WHISTLE_HIGH_HZ - WHISTLE_LOW_HZ) * shaft;
        float whistleSecond = EngineVoice.dbToLin(WHISTLE_SECOND_DB);
        float whooshTarget = EngineVoice.dbToLin(WHOOSH_DB) * whoosh;

        // ---- 输出：电平收窄（踩油门主要变音色），升挡断扭时再掉一下
        float post = EngineVoice.dbToLin(RPM_LOUD_DB * VirtualEngine.clamp01((rpmTarget - 800f) / 5200f)) * NORM;
        float levelTarget = (float) Math.pow(Math.max(params.level, 0f), LEVEL_POW) * EngineVoice.dbToLin(CUT_DIP_DB * cut);
        boolean toneOn = params.toneHz < EngineVoice.TONE_BYPASS_HZ;
        if (toneOn && Math.abs(params.toneHz - toneSetHz) > 1f) {
            tone[0].setLowpass(params.toneHz, 0.7071f, rate);
            tone[1].setLowpass(params.toneHz, 0.7071f, rate);
            toneSetHz = params.toneHz;
        }
        float rpmStep = (rpmTarget - rpmNow) / frames;
        float rockGain = EngineVoice.dbToLin(ROCK_DB);

        for (int i = 0; i < frames; i++) {
            float r = rpmNow + rpmStep * (i + 1);
            advance(6.0 * r / rate);
            int cur = cursor;

            // 排气：脉冲（过涡轮）+ 气流噪声，进排气管
            float x = exc[cur];
            exc[cur] = 0f;
            pulseDecay = ad * pulseDecay + x;
            pulseRise = ar * pulseRise + x;
            float pulse = (pulseDecay - pulseRise) * pulseNorm;
            float k = kick[cur];
            kick[cur] = 0f;
            envelope = ae * envelope + k;
            float w = white();
            tilt += tiltK * (w - tilt);
            float nz = raspHighpass.process(tilt * tiltNorm + 0.03f * w);
            float u = PulseVoice.saturate(pulse + (envelope * kickShare + floor) * nz, drive);
            int pd = (int) pipeDelay;
            float pf = pipeDelay - pd;
            float p0 = pipe[(pipeWrite - pd) & PIPE_MASK];
            float delayed = p0 + (pipe[(pipeWrite - pd - 1) & PIPE_MASK] - p0) * pf;
            pipeLoss += pipeK * (delayed - pipeLoss);
            float y = u + PIPE_FEEDBACK * pipeLoss;
            pipe[pipeWrite] = y;
            pipeWrite = (pipeWrite + 1) & PIPE_MASK;
            for (Biquad f : formants) {
                y = f.process(y);
            }
            y = radiation.process(muffler2.process(muffler.process(y)));

            // 两根尾管：长短、尾嘴的共振略不同
            tipLine[tipWrite] = y;
            float left = tipPeak[0].process(tap(tipDelay[0]));
            float right = tipPeak[1].process(tap(tipDelay[1]));
            tipWrite = (tipWrite + 1) & TIP_MASK;
            // 出口的湍流噪声：两根各自独立
            exitNow += (exitTarget - exitNow) * ampK;
            for (int c = 0; c < 2; c++) {
                float[] e = exitTilt[c];
                e[0] += exitK * (exitHighpass[c].process(white()) - e[0]);
                e[1] += exitK * (e[0] - e[1]);
            }
            left += exitTilt[0][1] * exitNow;
            right += exitTilt[1][1] * exitNow;

            // 放炮：尖、短，左右随机偏一点；带起来的一小段噪声
            for (int c = 0; c < 2; c++) {
                float xp = pop[c][cur];
                pop[c][cur] = 0f;
                popDecay[c] = popDecayA * popDecay[c] + xp;
                popRise[c] = popRiseA * popRise[c] + xp;
            }
            float xc = crackle[cur];
            crackle[cur] = 0f;
            crackleEnv = crackleDecay * crackleEnv + xc;
            float crack = crackleEnv * white() * popGain;
            left += popLowpass[0].process((popDecay[0] - popRise[0]) * popGain + crack);
            right += popLowpass[1].process((popDecay[1] - popRise[1]) * popGain + crack);
            float l = left + CROSS * right;
            float rr = right + CROSS * left;

            // 涡轮：哨声、放气（居中，发动机舱）
            whistleNow += (whistleTarget - whistleNow) * ampK;
            whistleHzNow += (whistleHz - whistleHzNow) * ampK;
            whooshNow += (whooshTarget - whooshNow) * ampK;
            float front = whooshBand.process(white()) * whooshNow;
            if (whistleNow > 1e-6f) {
                whistlePhase += whistleHzNow / rate;
                if (whistlePhase >= 1.0) {
                    whistlePhase -= 1.0;
                }
                front += whistleNow * (EngineVoice.sin(whistlePhase) + whistleSecond * EngineVoice.sin(2.0 * whistlePhase));
            }
            l += PulseVoice.CENTER * front;
            rr += PulseVoice.CENTER * front;

            float mid = 0.5f * (l + rr);
            float side = sideHighpass.process(0.5f * (l - rr));
            levelNow += (levelTarget - levelNow) * gainK;
            float g = post * levelNow;
            l = (mid + side) * g;
            rr = (mid - side) * g;

            // 点着、停住时发动机晃一下（只跟总音量）
            if (rockEnv > 1e-7f || rockNow > 1e-7f) {
                rockEnv *= rockDecay;
                rockNow += (rockEnv - rockNow) * rockAttackK;
                rockPhase += rockStep;
                if (rockPhase >= 1.0) {
                    rockPhase -= 1.0;
                }
                float s = rockGain * rockNow * EngineVoice.sin(rockPhase);
                l += s;
                rr += s;
            }

            if (toneOn) {
                l = tone[0].process(l);
                rr = tone[1].process(rr);
            }
            l = highpass[0].process(l);
            rr = highpass[1].process(rr);
            gainNow += (params.gain - gainNow) * gainK;
            limiter.push(l * gainNow, rr * gainNow);
            lr[2 * i] = limiter.left;
            lr[2 * i + 1] = limiter.right;
            cursor = (cursor + 1) & MASK;
        }
        rpmNow = rpmTarget;
    }

    /** 此刻的增压（0–1）、涡轮轴转速（0–1）、放炮的份额（0–1）：测试看涡轮、放炮跟没跟上。 */
    float boost() {
        return boost;
    }

    float shaft() {
        return shaft;
    }

    float burble() {
        return burble;
    }

    /** 曲轴往前转 {@code degrees}：经过的事件按发生的位置（样本以内的小数）触发。 */
    private void advance(double degrees) {
        if (!(degrees > 0.0)) {
            return;
        }
        double end = theta + degrees;
        while (nextAngle <= end) {
            float at = 1f - (float) ((end - nextAngle) / degrees);
            exhaust(evCyl[nextEvent], at);
            nextEvent++;
            if (nextEvent == evAngle.length) {
                nextEvent = 0;
                cycleBase += 720.0;
            }
            nextAngle = cycleBase + evAngle[nextEvent];
        }
        theta = end;
        if (theta >= 720.0) {
            theta -= 720.0;
            cycleBase -= 720.0;
            nextAngle -= 720.0;
        }
    }

    /** 缸号 {@code cylinder} 的排气门打开，发生在当前样本往后 {@code at}（0–1）个样本处。 */
    private void exhaust(int cylinder, float at) {
        int c = cylinder - 1;
        float variation = Math.max(0f, 1f + evSigma * gauss());
        float level = evCombustion;
        if (evMisfire > 0f && random01() < evMisfire) {
            level = PUMP;
        }
        float a = TRIM[c] * variation * level * evAbsorb;
        // 时机：歧管、涡管长短 + 随机偏移（N(0, 抖动) 度，围着固定的 3 倍抖动，保证不往回排）
        float delay = headerSamples[c] + evJitter * (3f + gauss()) * evSamplesPerDeg;
        float q = at + Math.max(0f, Math.min(MAX_DELAY, delay));
        add(exc, q, a);
        add(kick, q, a * evRasp * (0.4f + 0.6f * level));
        if (evPop > 0f && random01() < evPop) {
            float lag = (POP_LAG_MS + POP_SPREAD_MS * random01()) * 1e-3f * rate;
            afterfire(Math.min(MAX_DELAY, q + lag), 0.6f + 0.8f * random01());
        }
    }

    /** 一炮：在当前样本往后 {@code q} 个样本处，{@code amp} 倍；左右随机偏一点，带起一小段噪声。 */
    private void afterfire(float q, float amp) {
        float x = POP_PAN * (2f * random01() - 1f);
        add(pop[0], q, amp * PulseVoice.CENTER_PAN * PulseVoice.panLeft(x));
        add(pop[1], q, amp * PulseVoice.CENTER_PAN * PulseVoice.panRight(x));
        add(crackle, q, amp * EngineVoice.dbToLin(CRACKLE_DB));
    }

    /** 升挡断扭开头那一声「啪」（{@code amp} 倍一炮），马上到。 */
    private void bang(float amp) {
        afterfire(1f + POP_LAG_MS * 1e-3f * rate, amp);
    }

    /** 发动机在支座上晃一下：一个从零点起、按 {@link #ROCK_MS} 落下去的低频（{@code amp} 倍）。 */
    private void rock(float amp) {
        rockEnv = Math.max(rockEnv, amp);
        rockPhase = 0.0;
    }

    /** 尾管延迟线上往回 {@code d} 个样本（小数，线性插值）。 */
    private float tap(float d) {
        int i = (int) d;
        float f = d - i;
        float a = tipLine[(tipWrite - i) & TIP_MASK];
        float b = tipLine[(tipWrite - i - 1) & TIP_MASK];
        return a + (b - a) * f;
    }

    private void sortEvents() {
        for (int i = 1; i < evAngle.length; i++) {
            float a = evAngle[i];
            int c = evCyl[i];
            int j = i - 1;
            while (j >= 0 && evAngle[j] > a) {
                evAngle[j + 1] = evAngle[j];
                evCyl[j + 1] = evCyl[j];
                j--;
            }
            evAngle[j + 1] = a;
            evCyl[j + 1] = c;
        }
    }

    /** 在当前样本往后 {@code q} 个样本处加一下（落在两个样本之间就按距离分开）。 */
    private void add(float[] ring, float q, float amp) {
        int i = (int) q;
        float w = q - i;
        ring[(cursor + i) & MASK] += amp * (1f - w);
        ring[(cursor + i + 1) & MASK] += amp * w;
    }

    /** 沙哑的拐点：一阶低通的系数、把输出的方差拉回 1。 */
    private void setTilt(float hz) {
        tiltK = onePole(hz);
        tiltNorm = (float) (1.0 / Math.sqrt(tiltK / (2.0 - tiltK)));
    }

    private float perSample(float tauSec) {
        return (float) Math.exp(-1.0 / (Math.max(tauSec, 1e-6f) * rate));
    }

    private float onePole(float hz) {
        return 1f - (float) Math.exp(-2.0 * Math.PI * hz / rate);
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** 白噪声，方差 1。 */
    private float white() {
        return nextNoise() * 1.7320508f;
    }

    /** 近似正态（四个均匀数相加），方差 1。 */
    private float gauss() {
        return (nextNoise() + nextNoise() + nextNoise() + nextNoise()) * 0.8660254f;
    }

    private float random01() {
        return (nextNoise() + 1f) * 0.5f;
    }

    /** xorshift32，[−1, 1)。可复现（测试靠它）。 */
    private float nextNoise() {
        int x = noise;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        noise = x;
        return x * (1f / 2147483648f);
    }
}
