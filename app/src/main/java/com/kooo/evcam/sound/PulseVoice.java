package com.kooo.evcam.sound;

/**
 * 「美式 V8」风格（{@link SoundStyle#AMERICAN}，第二版。项目所有者 2026-10-21：第一版太单薄，要机械增压交叉平面 V8 的取向）：
 * 照发动机的结构逐缸合成，立体声。
 * 纯计算（{@code PulseVoiceTest}），声音线程上一块一块地调，循环里不分配内存。数据在 {@link PulseProfile}，
 * 来历和量过的参考在 docs/local/drive-sound-plan.md §12。
 *
 * <ul>
 *   <li><b>八个缸</b>：曲轴转到每个缸的排气门打开时，往那一排的排气管里打一个压力脉冲（起得快、按曲轴角落下去）。
 *       强弱 = 缸的差别 × 同一排上一个脉冲留下的压力（交叉平面 V8 每排的点火间隔是 90/180/270°，「突突」就是这么来的）
 *       × 每一下的随机起伏；时机按歧管长短错开一点，再随机偏一点。</li>
 *   <li><b>气流噪声</b>：每一下点火把一段白噪声踢起来（随点火起伏，不是一下一下断开），和脉冲一起进排气管 ——
 *       负荷越大越沙哑。参考录音里带负荷时七八成的声音是这种随机成分，第一版只有正弦，所以薄。
 *       它还跟着流量（转速）变：收油时低转很闷、高转一下亮起来，频谱的拐点随流速往上走。</li>
 *   <li><b>排气管</b>（左右各一根，长度略不同）：反馈梳状滤波器给出固定的共振峰，再加集气管、中段的共振和消声器的亥姆霍兹共振；
 *       消声器的低通随转速一直开到红线、负荷大再开一点；压力大时轻微饱和。</li>
 *   <li><b>进气</b>：每个缸进气门关时一个脉冲进进气腔、进气道的共振；负荷大时进气的呼啸，车速越高风声越大。节气门几乎关着时
 *       窄缝里声速的气流「嘶」一声，每吸一口气涌一股（怠速时一股一股，转速高了连成一片、被别的盖住）。</li>
 *   <li><b>机械增压</b>（双螺杆，曲轴用皮带带着转，比曲轴快、一点火就跟着转）：几个部件各出各的声，位置、转速不同
 *       （项目所有者 2026-10-23：应该分开、更有层次，不是单纯的风声；颗粒状噪音要着重避免 —— 全用确定的音，没有随机的抖动）：
 *       同步齿轮的啮合声（叶片频率的 2 倍那一串；样子照车主偏好的那台的录音、音高照参考视频那台，项目所有者 2026-10-24；
 *       只在增压时有，旁通阀一开几乎没了）；
 *       出口的压力脉动（做功、压缩：叶片频率的 1、3、5 次，跟增压的 1.5 次方，经左右两个中冷器，出口风道的共振随压缩加热
 *       往上走）；进口那边空转的叶片声（任何负荷都有，踩下去起压那一下猛一下）；负载时齿轮受力，两个转子各转一圈、每次点火
 *       都调一下（边带，增压时深、不增压时浅：负载时粗、带着低吼，项目所有者 2026-10-24）；曲轴每次点火的扭振带着转速
 *       一起抖（周期性的，很小）。底下一层轻的气流声（出口一股一股、进口吸气，左右两路不相关）。松油门「呼」一声；
 *       松开以后（节气门关上、旁通阀打开，项目所有者 2026-10-23 的参考视频）燃烧、压缩那几层没了，剩下干净的啸叫 —— 叶片频率的
 *       第 2、3、4 次，松开那一下最响，随转速落下去很快变淡：和踩着油门时的声调明显不同。
 *       增压时进气腔里的压力脉动（每个进气门关上那一下，两排各自不均匀的间隔让每一下强弱、早晚不同）：
 *       十几根发动机的整数阶跟齿轮声一起随转速走，叠成和弦。</li>
 *   <li><b>气门落座、燃烧</b>：每个缸的气门关上「嗒」一下、燃烧「咔」一下，各过几个高频共振，摆在那个缸的位置上。</li>
 *   <li><b>车厢</b>：几个低频的车身、车厢共振（某个阶次扫过时「嗡」一下）和一个高 Q 的「和鸣」。</li>
 *   <li><b>传动</b>（D 挡、车在走）：变速箱齿轮的啸叫（每个挡阶次不同）、传动轴的二阶、差速器的啸叫（跟着车速）；
 *       倒挡齿轮的啸叫、起动机照经典风格（{@link EngineVoice}）。</li>
 *   <li><b>换挡</b>：升挡时断扭（燃烧减弱、沙哑少、偶尔「突」），换挡那一刻地板中间「咚」一下、挂挡「咔」一下（降挡轻一点）。</li>
 *   <li><b>收油</b>（断油）：每一下只剩泵气、起伏更大，偶尔轻轻「突」一下，急松油门时多一点。不做像枪声的放炮。</li>
 *   <li><b>启动、熄火</b>：起动机（或行驶中电机）带着转、没点火时只有压缩的「噗」；点着的头几圈偶尔缺火，发动机在支座上晃一下
 *       （项目所有者 2026-10-22：点火体感再强些）。熄火后也只剩「噗」，最后几圈越转越慢、越来越轻，停住那一下再晃一下。</li>
 * </ul>
 *
 * <p><b>声音从哪来</b>（项目所有者 2026-10-21：不是死大才真实，要有层次、听得出方位）：从驾驶座看，发动机舱在前 ——
 * 进气、增压器居中，进气口在左前，两边的中冷器一左一右，每个缸的气门、燃烧按排在左右、按前后远近（1、2 缸最远）差一点响度和时间；
 * 排气在后，两根尾管在车后两角（左右分得比发动机舱开），比发动机舱的声音晚到约 10 ms；传动、换挡的「咚」在地板中间；
 * 车厢共振不分左右，{@link PulseProfile#bassMonoHz} 以下左右一样。</p>
 *
 * <p><b>车内听感</b>（开发者选项「听感」选「车内」）：发动机舱的声音隔着防火墙、排气隔着车身，各自变闷（排气更闷），
 * 低频多一点（车身传进来的）；响度（100 Hz 以上）和车外一样。</p>
 *
 * <p>电平、音量逐样本平滑，最后过先看一步的限幅（{@link Limiter}），峰值不超过 {@link EngineVoice#CEILING}。</p>
 */
final class PulseVoice implements Voice {

    /** 激励的环形缓冲（样本，2 的幂）：事件往后写（歧管、随机偏移），每个样本读走当前这一格。 */
    private static final int RING = 2048;
    private static final int MASK = RING - 1;
    /** 最多往后排多远（样本）。 */
    private static final float MAX_DELAY = RING - 64;
    /** 排气管的延迟线（样本，2 的幂，够最低的共振基频用）。 */
    private static final int PIPE = 1024;
    private static final int PIPE_MASK = PIPE - 1;

    private static final int EXHAUST = 0;
    private static final int EXHAUST_CLOSE = 1;
    private static final int INTAKE_CLOSE = 2;
    private static final int BURN = 3;

    /** 进气、增压、车厢共振这些居中的声音进左右各多少（摆在别处的按等功率摆，居中时也是这么多）。 */
    static final float CENTER = 0.7f;
    /** 摆在别处的声音：等功率摆位的增益再乘它，居中时左右各 {@link #CENTER}。 */
    static final float CENTER_PAN = CENTER * 1.4142135f;
    /** 每个缸的气门、燃烧声的总量和以前左右八二分时一样。 */
    static final float CYLINDER_NORM = 0.825f;
    static final float SOUND_MPS = 343f;
    /** 升挡断扭时燃烧还剩几成（比收油的泵气还少：高转满负荷时增压器照转，排气不掉多一点就听不出）；沙哑少几成、「突」多几成。 */
    static final float CUT_FLOOR = 0.15f;
    static final float CUT_RASP = 0.6f;
    static final float CUT_POP = 0.5f;
    /** 挂挡「咔」那一小段噪声多长、多快落下去（秒）。 */
    static final float CLICK_SEC = 0.004f;
    static final float CLICK_DECAY_SEC = 0.0008f;
    /** 降挡的「咚」是升挡的几成。 */
    static final float DOWNSHIFT_THUNK = 0.7f;
    /** 进气风声按这个车速（km/h）的平方长（{@link PulseProfile#inletRam}）。 */
    static final float RAM_KMH = 200f;
    /** 进气的呼啸要有气流：转速低于这个（rpm）越来越小，熄火停住时没了。 */
    static final float ROAR_FULL_RPM = 600f;
    /** 沙哑的拐点随转速往上走，到这里（Hz）为止。 */
    static final float TILT_TOP_HZ = 4000f;
    /** 起动机带着转时，每一下压缩是燃烧的几成。 */
    static final float CRANK_LEVEL = 0.15f;
    /**
     * 没点火、也不是起动机在带（熄火停下来、行驶中电机刚带起来）：每一下压缩是燃烧的几成（比起动机带着时转得快、气多，
     * 熄火后在车里还听得见）；在 {@link #UNFIRED_FULL_RPM} 以下越来越轻（流量小了）。
     */
    static final float UNFIRED_LEVEL = 0.35f;
    static final float UNFIRED_FULL_RPM = 300f;
    /** 点着那一刻起，头几圈缺火的机会（随 {@link #CATCH_SEC} 消失）。 */
    static final float MISFIRE = 0.3f;
    static final float CATCH_SEC = 0.25f;
    /** 发动机在支座上晃一下（{@link PulseProfile#rockDb} 的几倍）：停着点着、行驶中点着、停着熄火停住、行驶中停住。 */
    static final float CATCH_ROCK = 1f;
    static final float ROLL_CATCH_ROCK = 0.6f;
    static final float SETTLE_ROCK = 0.35f;
    static final float MOVING_SETTLE_ROCK = 0.15f;
    static final float ROCK_ATTACK_SEC = 0.003f;
    /** 进气脉冲落下去的曲轴角、起来的时间。 */
    static final float INTAKE_DECAY_DEG = 30f;
    static final float INTAKE_RISE_MS = 1f;
    /** 松油门：负荷比最近的峰值（{@link #LIFT_PEAK_SEC} 慢慢回落）掉了这么多，或者掉到一半以下。 */
    static final float LIFT_PEAK_SEC = 1.5f;
    static final float LIFT_DROP = 0.25f;
    /** 机械增压旁通阀打开的「呼」（dB、多久淡掉、带通）。项目所有者 2026-10-22 嫌松油门后的「呼」有点大：−20 → −26 dB。 */
    static final float WHOOSH_DB = -26f;
    static final float WHOOSH_SEC = 0.3f;
    static final float WHOOSH_HZ = 1200f;
    /**
     * 车内听感时增压器的啸叫（叶片、齿轮）只过两个这么高的一阶低通、低几 dB，不过整个防火墙的两级低通（那会把
     * 1–4 kHz 的啸叫压掉 10–20 dB）。项目所有者 2026-10-22：起压时机械质感严重不足（他用车内听感）；增压器就在挡风玻璃下面，啸叫
     * 在车里本来就听得见。2026-10-24 一级 → 两级：一级时齿轮声的高次谐波在车里 4.8–9.6 kHz 发亮（4500 rpm 满增压时比没有增压器
     * 多 17.6 dB），负载时反而比松开时更「亮」，不是更「重」（项目所有者：负载时要有更重的机械感）。
     */
    static final float CABIN_TONE_DB = -6f;
    static final float CABIN_TONE_HZ = 3500f;
    /** 增压器的每个音到 {@link #WHINE_TOP_HZ} − {@link #WHINE_FADE_HZ}（Hz）开始渐隐，到 {@link #WHINE_TOP_HZ} 没了。 */
    static final float WHINE_TOP_HZ = 10000f;
    static final float WHINE_FADE_HZ = 3000f;
    /** 阳转子那份边带的相位（周）：两个转子的差别对不齐。 */
    static final double MALE_PHASE = 1.3 / (2.0 * Math.PI);
    /** 不增压时出口的气流还剩几成；松开油门时进口还有几成流量。 */
    static final float JET_FREE = 0.15f;
    static final float INLET_FREE = 0.25f;
    /** 一股气的形状 (½ + ½ cos)³ 的平均值：减掉它，一股一股的起伏平均为 0。 */
    static final float PUFF_MEAN = 0.3125f;
    static final float GEAR_SEC = 0.05f;
    static final float CABIN_LOWPASS_HZ = 200f;
    /** 一个「突」在脉冲之后随机晚多少（样本以内）。 */
    static final float POP_SPREAD = 20f;
    /** 节气门的嘶声每一股起来多快（秒）；每一股的大小按这个转速（rpm，怠速）为准，转速高了按平方根分少（{@link PulseProfile#hissDb}）。 */
    static final float HISS_RISE_SEC = 0.0005f;
    static final float HISS_REF_RPM = 900f;
    /** 松开油门以后的啸叫按这个转速（rpm）定电平（{@link PulseProfile#decelDb}）；相邻两次谐波的相位差（周，固定的）。 */
    static final float DECEL_REF_RPM = 4500f;
    static final double DECEL_PHASE = 0.4;

    private final PulseProfile p;
    private final float rate;

    // ---- 曲轴和事件（一圈 720° 里按角度排好）
    private final float[] evAngle;
    private final int[] evKind;
    private final int[] evCyl;
    private int nextEvent;
    private double theta;
    private double cycleBase;
    private double nextAngle;
    /** 每个缸（下标 = 缸号 − 1）在哪一排、和同一排上一个点火隔多少度、歧管多走多少样本。 */
    private final int[] bankOf;
    private final float[] gapDeg;
    private final float[] headerSamples;
    private final float[] bankSamples;
    /** 排气比发动机舱晚到多少样本。 */
    private final float lagSamples;
    /** 每个缸的位置（下标 = 缸号 − 1）：气门、燃烧声的响度、晚到多少样本、进左右各多少。 */
    private final float[] cylGain;
    private final float[] cylDelay;
    private final float[] cylLeft;
    private final float[] cylRight;

    // ---- 激励
    private final float[][] exc = new float[2][RING];
    private final float[][] kick = new float[2][RING];
    private final float[][] pop = new float[2][RING];
    private final float[][] tick = new float[2][RING];
    private final float[][] knock = new float[2][RING];
    private final float[] intake = new float[RING];
    /** 两排进气腔各自的压力脉动（0 左排、1 右排）。 */
    private final float[][] pulsation = new float[2][RING];
    private final float[] click = new float[RING];
    private final float[] hiss = new float[RING];
    private int cursor;

    // ---- 这一块里事件用的量（每块算一次）
    private float evCombustion;
    private float evSigma;
    private float evMisfire;
    private float evPop;
    private float evRecovery;
    private float evJitter;
    private float evSamplesPerDeg;
    private float evRasp;
    private float evTick;
    private float evKnock;
    private float evIntake;
    private float evPulsation;
    private float evCut;
    private float evHiss;

    // ---- 排气（每排一套）
    private final float[] pulseDecay = new float[2];
    private final float[] pulseRise = new float[2];
    private final float[] popDecay = new float[2];
    private final float[] popRise = new float[2];
    private final float[] envelope = new float[2];
    private final float[] tilt = new float[2];
    private final Biquad[] noiseHighpass = new Biquad[2];
    private final Biquad[] noisePeak = new Biquad[2];
    private final float[][] pipe = new float[2][PIPE];
    private final float[] pipeDelay = new float[2];
    private final float[] pipeLoss = new float[2];
    private int pipeWrite;
    private final Biquad[][] formants = new Biquad[2][];
    private final Biquad[] muffler = new Biquad[2];
    private final Biquad[] radiation = new Biquad[2];
    private final float[] bankOut = new float[2];
    /** 沙哑的拐点随转速变：每块重算。 */
    private float tiltK;
    private float tiltNorm;
    private final float pipeK;
    private final float popDecayA;
    private final float popRiseA;

    // ---- 进气
    private float intakeDecay;
    private float intakeRise;
    private final Biquad plenum;
    private final Biquad runner;
    private final Biquad roar;
    private float roarNow;
    /** 节气门的嘶声：每一股的包络（落、起）、归一、带通，底下那一层的电平（目标、逐样本）。 */
    private float hissDecay;
    private float hissRise;
    private final float hissDecayA;
    private final float hissRiseA;
    private final float hissNorm;
    private final Biquad hissBand;
    private final Biquad hissBand2;
    private float hissTarget;
    private float hissNow;

    // ---- 增压
    private final boolean supercharged;
    private final float lobeGain;
    private final float[] meshGains;
    private float meshNow;
    /** 齿轮声的几个来源：各自相对的叶片相位（周）、游移的低通噪声；几个加起来的能量折回一个。 */
    private final double[] voicePhase;
    private final float[] voiceNoise;
    private final float voiceK;
    private final float voiceNorm;
    private final float voiceScale;
    /** 增压时进气腔的压力脉动（{@link PulseProfile#pulsationDb}）：几个跟着转速走的共振和各自的增益（每块重算），每一下最多晚多少样本。 */
    private final Biquad[][] pulsationModes;
    private final float[] pulsationGains;
    private final Biquad[] pulsationTop = new Biquad[2];
    /** 两排进气腔进左右各多少（等功率摆位，{@link PulseProfile#plenumPan}）。 */
    private final float[] plenumLeft = new float[2];
    private final float[] plenumRight = new float[2];
    /** 齿轮声的几个来源进左右各多少（{@link PulseProfile#meshSpread}）；这一个样本的啸叫（左、右）。 */
    private final float[] voiceLeft;
    private final float[] voiceRight;
    private float whineL;
    private float whineR;
    /** {@link PulseProfile#widthHz} 以上左右的差放大多少（倍）；一阶搁架的系数、状态。 */
    private final float widthGain;
    private final float widthK;
    private float widthLp;
    private final float pulsationLagSamples;
    private double lobePhase;
    private double femalePhase;
    private double malePhase;
    /** 出口的压力脉动：每次谐波的增益（相对 {@link PulseProfile#dischargeDb}）、这一块的目标和逐样本的电平；两个中冷器的低通、风道的共振（左、右）。 */
    private final float[] dischargeGains;
    private float dischargeTarget;
    private float dischargeNow;
    private final Biquad[] intercooler = new Biquad[2];
    private final Biquad[] duct = new Biquad[2];
    /** 松开油门以后的啸叫：每次谐波的增益（相对 {@link PulseProfile#decelDb}）、这一块的目标、逐样本的电平、松开那一下多响的（0–1）。 */
    private final float[] decelGains;
    private float decelTarget;
    private float decelNow;
    private float decelKick;
    /** 负载时那层机械感（{@link PulseProfile#growlFree}）：这一块的目标、逐样本的；踩下去起压那一下（{@link PulseProfile#pressBurst}）。 */
    private float growlTarget;
    private float growlNow;
    /** 负载时壳体那一路（{@link PulseProfile#bodyHz}）：几个壳体的共振、增益。 */
    private final Biquad[] bodyModes;
    private final Biquad bodyTop;
    private final float bodyGain;
    private float pressKick;
    private float pressNow;
    private float boost;
    private float whoosh;
    private float whineNow;
    private float jetNow;
    private float inletNow;
    private float whooshNow;
    private final Biquad[][] jetBands = new Biquad[2][];
    private final float[] jetGains;
    private final Biquad[] airLowpass = new Biquad[2];
    private final Biquad inletBand;
    private final Biquad whooshBand;
    private float loadPeak;
    /** 进气口、两边中冷器出口进左右各多少（等功率摆位，居中 = {@link #CENTER}）。 */
    private final float inletLeft;
    private final float inletRight;
    private final float jetNear;
    private final float jetFar;

    // ---- 气门、缸体、车厢
    private final Biquad[][] tickModes = new Biquad[2][];
    private final Biquad[][] knockModes = new Biquad[2][];
    private final Biquad cabinLowpass;
    private final Biquad[] cabinModes;
    private final Biquad[] sympathetic;

    // ---- 传动、倒挡、起动机
    private double gearPhase;
    private double diffPhase;
    private double shaftPhase;
    private double reversePhase;
    private double starterPhase;
    private float gearNow;
    private float diffNow;
    private float shaftNow;
    private float reverseNow;
    private float starterNow;

    // ---- 换挡：「咚」是几个衰减的低频（只跟总音量），「咔」是一小段噪声过带通
    private final float[] thunkEnv;
    private final double[] thunkPhase;
    private final float[] thunkDecay;
    private final float[] thunkStep;
    private final Biquad clickBand;
    private boolean serialSeen;
    private int lastSerial;
    // ---- 点着、停住时发动机在支座上晃一下：一个衰减的低频，几毫秒起来
    private float rockEnv;
    private float rockNow;
    private double rockPhase;
    private final float rockDecay;
    private final float rockAttackK;
    private final float rockStep;
    /** 见过的点着次数（{@link VoiceParams#catchSerial}）。 */
    private int lastCatch;

    // ---- 车内听感：前（防火墙）、后（车身）每个声道两个一阶低通，最后一个低频搁架
    private final boolean cabin;
    /** 电平取的次方（按听感，{@link PulseProfile#levelPow}）。 */
    private final float levelPow;
    private final float[] frontK;
    private final float[] rearK;
    private final float[][] frontLp = new float[2][2];
    private final float[][] rearLp = new float[2][2];
    private final Biquad[] cabinLow = new Biquad[2];
    private final float cabinGain;
    /** 车内听感：增压器啸叫自己的一阶低通（{@link #CABIN_TONE_HZ}）和增益。 */
    private float toneLpL;
    private float toneLp2L;
    private float toneLpR;
    private float toneLp2R;
    private final float toneK;
    private final float cabinToneGain;
    /** 车内听感，这一块里进气腔和弦的倍数（高转时收）。 */
    private float chordGain = 1f;

    // ---- 输出
    private final Biquad sideHighpass;
    private final Biquad[] highpass = new Biquad[2];
    private final Biquad[] tone = new Biquad[2];
    private float toneSetHz = -1f;
    private final Limiter limiter;
    private final float ampK;
    private final float gainK;
    private final float gearK;
    private float rpmNow = -1f;
    /** 燃烧还剩几成（松油门时按 {@link PulseProfile#fuelOutSec} 收到泵气）；排气的温度（0 冷 … 1 热；−1 还没开始）。 */
    private float fuel = -1f;
    private float heat = -1f;
    private float levelNow;
    private float gainNow;
    private float catchEnv;
    private int noise = 0x2545F491;
    /** 「咔」自己的噪声：换不换挡，别的声音用的随机数都一样（测试靠它）。 */
    private int clickNoise = 0x1B873593;
    /** 节气门嘶声自己的噪声：有没有这一层，别的声音用的随机数都一样。 */
    private int hissNoise = 0x3C6EF372;

    PulseVoice(PulseProfile profile, float sampleRate) {
        this(profile, sampleRate, false);
    }

    /** @param cabin 车内听感（隔着防火墙、车身听） */
    PulseVoice(PulseProfile profile, float sampleRate, boolean cabin) {
        this.p = profile;
        this.rate = sampleRate;
        this.cabin = cabin;
        levelPow = cabin ? profile.cabinLevelPow : profile.levelPow;
        int n = profile.firing.length;
        int cylinders = profile.trim.length;
        bankOf = new int[cylinders];
        gapDeg = new float[cylinders];
        headerSamples = new float[cylinders];
        cylGain = new float[cylinders];
        cylDelay = new float[cylinders];
        cylLeft = new float[cylinders];
        cylRight = new float[cylinders];
        for (int c : profile.firing) {
            bankOf[c - 1] = c % 2 == 1 ? 0 : 1;
            // 1、2 缸在最前面，离驾驶座最远：响度按距离、晚到按声速
            float d = profile.cylinderNearM + (3 - (c - 1) / 2) * profile.cylinderPitchM;
            cylGain[c - 1] = CYLINDER_NORM * profile.cylinderNearM / d;
            cylDelay[c - 1] = (d - profile.cylinderNearM) / SOUND_MPS * sampleRate;
            float x = bankOf[c - 1] == 0 ? -profile.cylinderPan : profile.cylinderPan;
            cylLeft[c - 1] = panLeft(x);
            cylRight[c - 1] = panRight(x);
        }
        lagSamples = profile.exhaustLagMs * 1e-3f * sampleRate;
        // 同一排上一个点火隔多少度：点火顺序定死的
        for (int k = 0; k < n; k++) {
            int c = profile.firing[k];
            int j = 1;
            while (bankOf[profile.firing[((k - j) % n + n) % n] - 1] != bankOf[c - 1]) {
                j++;
            }
            gapDeg[c - 1] = 720f * j / n;
            headerSamples[c - 1] = profile.headerMs[c - 1] * 1e-3f * sampleRate;
        }
        bankSamples = new float[] {profile.bankMs[0] * 1e-3f * sampleRate, profile.bankMs[1] * 1e-3f * sampleRate};
        // 事件表：每个缸四件事，按角度排好
        int count = 4 * n;
        evAngle = new float[count];
        evKind = new int[count];
        evCyl = new int[count];
        float[] offsets = {profile.exhaustOpenDeg, profile.exhaustCloseDeg, profile.intakeCloseDeg, profile.burnDeg};
        int[] kinds = {EXHAUST, EXHAUST_CLOSE, INTAKE_CLOSE, BURN};
        int e = 0;
        for (int k = 0; k < n; k++) {
            float tdc = 720f * k / n;
            for (int j = 0; j < 4; j++) {
                evAngle[e] = (tdc + offsets[j]) % 720f;
                evKind[e] = kinds[j];
                evCyl[e] = profile.firing[k];
                e++;
            }
        }
        sortEvents();
        nextEvent = 0;
        nextAngle = evAngle[0];

        setTilt(profile.raspTiltHz);
        pipeK = onePole(profile.pipeLossHz);
        popDecayA = perSample(profile.popDecayMs * 1e-3f);
        popRiseA = perSample(profile.popRiseMs * 1e-3f);
        for (int b = 0; b < 2; b++) {
            noiseHighpass[b] = Biquad.highpass(profile.raspHighpassHz, 0.7071f, sampleRate);
            noisePeak[b] = Biquad.peaking(profile.raspPeakHz, profile.raspPeakDb, profile.raspPeakQ, sampleRate);
            pipeDelay[b] = Math.max(1f, Math.min(PIPE - 2, sampleRate / profile.pipeHz[b]));
            int f = profile.formantHz.length;
            formants[b] = new Biquad[f + 1];
            for (int k = 0; k < f; k++) {
                formants[b][k] = Biquad.peaking(profile.formantHz[k], profile.formantDb[k], profile.formantQ[k], sampleRate);
            }
            formants[b][f] = Biquad.peaking(profile.helmholtzHz[b], profile.helmholtzDb, profile.helmholtzQ, sampleRate);
            muffler[b] = Biquad.lowpass(profile.mufflerHz, 0.6f, sampleRate);
            radiation[b] = Biquad.highpass(profile.radiationHz, 0.6f, sampleRate);
            tickModes[b] = bank(profile.tickHz, profile.tickQ);
            knockModes[b] = bank(profile.knockHz, profile.knockQ);
            highpass[b] = Biquad.highpass(EngineVoice.HIGHPASS_HZ, 0.7071f, sampleRate);
            tone[b] = new Biquad();
        }
        limiter = new Limiter(sampleRate);
        plenum = Biquad.bandpass(profile.plenumHz, profile.plenumQ, sampleRate);
        runner = Biquad.bandpass(profile.runnerHz, profile.runnerQ, sampleRate);
        roar = Biquad.bandpass(profile.roarHz, profile.roarQ, sampleRate);
        hissBand = Biquad.bandpass(profile.hissHz, profile.hissQ, sampleRate);
        hissBand2 = Biquad.bandpass(profile.hissHz, profile.hissQ, sampleRate);
        double hd = profile.hissDecayMs * 1e-3;
        double hr = HISS_RISE_SEC;
        double hp = Math.log(hd / hr) * hd * hr / (hd - hr);
        hissNorm = (float) (1.0 / (Math.exp(-hp / hd) - Math.exp(-hp / hr)));
        hissDecayA = perSample((float) hd);
        hissRiseA = perSample(HISS_RISE_SEC);
        supercharged = profile.superchargerOrder > 0f;
        lobeGain = EngineVoice.dbToLin(profile.lobeDb);
        meshGains = new float[profile.meshHarmonicsDb[0].length];
        voicePhase = new double[profile.meshVoiceDetune.length];
        voiceNoise = new float[voicePhase.length];
        voiceK = onePole(profile.meshVoiceWanderHz);
        voiceNorm = (float) (1.0 / Math.sqrt(voiceK / (2.0 - voiceK)));
        voiceScale = (float) (1.0 / Math.sqrt(voicePhase.length));
        pulsationModes = new Biquad[2][profile.pulsationOrders.length];
        for (int b = 0; b < 2; b++) {
            for (int k = 0; k < profile.pulsationOrders.length; k++) {
                pulsationModes[b][k] = new Biquad();
            }
            pulsationTop[b] = Biquad.lowpass(profile.pulsationTopHz, 0.7071f, sampleRate);
            float x = b == 0 ? -profile.plenumPan : profile.plenumPan;
            plenumLeft[b] = CENTER_PAN * panLeft(x);
            plenumRight[b] = CENTER_PAN * panRight(x);
        }
        pulsationGains = new float[profile.pulsationOrders.length];
        voiceLeft = new float[profile.meshVoiceDetune.length];
        voiceRight = new float[voiceLeft.length];
        for (int v = 0; v < voiceLeft.length; v++) {
            // 第 1 个居中，后面的一左一右
            float x = v == 0 ? 0f : (v % 2 == 1 ? -1f : 1f) * profile.meshSpread;
            voiceLeft[v] = CENTER_PAN * panLeft(x);
            voiceRight[v] = CENTER_PAN * panRight(x);
        }
        widthGain = EngineVoice.dbToLin(profile.widthDb);
        widthK = onePole(profile.widthHz);
        pulsationLagSamples = profile.pulsationLagMs * 1e-3f * sampleRate;
        dischargeGains = new float[profile.dischargeHarmonics.length];
        for (int k = 0; k < dischargeGains.length; k++) {
            dischargeGains[k] = EngineVoice.dbToLin(profile.dischargeHarmonicsDb[k]);
        }
        for (int s = 0; s < 2; s++) {
            intercooler[s] = Biquad.lowpass(profile.intercoolerHz, 0.7071f, sampleRate);
            duct[s] = Biquad.peaking(profile.ductHz[s], profile.ductDb, profile.ductQ, sampleRate);
        }
        bodyModes = new Biquad[profile.bodyHz.length];
        for (int k = 0; k < bodyModes.length; k++) {
            bodyModes[k] = Biquad.bandpass(profile.bodyHz[k], profile.bodyQ, sampleRate);
        }
        bodyTop = Biquad.lowpass(profile.bodyTopHz, 0.7071f, sampleRate);
        bodyGain = EngineVoice.dbToLin(profile.bodyDb);
        decelGains = new float[profile.decelHarmonics.length];
        for (int k = 0; k < decelGains.length; k++) {
            decelGains[k] = EngineVoice.dbToLin(profile.decelHarmonicsDb[k]);
        }
        jetGains = new float[profile.jetHz.length];
        for (int k = 0; k < jetGains.length; k++) {
            jetGains[k] = EngineVoice.dbToLin(profile.jetGainDb[k]);
        }
        for (int b = 0; b < 2; b++) {
            jetBands[b] = bank(profile.jetHz, profile.jetQ);
            airLowpass[b] = Biquad.lowpass(profile.airLowpassHz, 0.7071f, sampleRate);
        }
        inletBand = Biquad.bandpass(profile.inletHz, profile.inletQ, sampleRate);
        whooshBand = Biquad.bandpass(WHOOSH_HZ, 0.9f, sampleRate);
        inletLeft = CENTER_PAN * panLeft(profile.inletPan);
        inletRight = CENTER_PAN * panRight(profile.inletPan);
        jetNear = CENTER_PAN * panLeft(-profile.jetPan);
        jetFar = CENTER_PAN * panRight(-profile.jetPan);
        int modes = profile.thunkHz.length;
        thunkEnv = new float[modes];
        thunkPhase = new double[modes];
        thunkDecay = new float[modes];
        thunkStep = new float[modes];
        for (int k = 0; k < modes; k++) {
            thunkDecay[k] = perSample(profile.thunkMs[k] * 1e-3f);
            thunkStep[k] = profile.thunkHz[k] / sampleRate;
        }
        clickBand = Biquad.bandpass(profile.clickHz, profile.clickQ, sampleRate);
        rockDecay = perSample(profile.rockMs * 1e-3f);
        rockAttackK = 1f - perSample(ROCK_ATTACK_SEC);
        rockStep = profile.rockHz / sampleRate;
        frontK =new float[] {onePole(profile.cabinFrontHz[0]), onePole(profile.cabinFrontHz[1])};
        rearK = new float[] {onePole(profile.cabinRearHz[0]), onePole(profile.cabinRearHz[1])};
        for (int b = 0; b < 2; b++) {
            cabinLow[b] = Biquad.lowShelf(profile.cabinLowHz, profile.cabinLowDb, sampleRate);
        }
        cabinGain = EngineVoice.dbToLin(profile.cabinGainDb);
        toneK = onePole(CABIN_TONE_HZ);
        cabinToneGain = EngineVoice.dbToLin(CABIN_TONE_DB);
        cabinLowpass = Biquad.lowpass(CABIN_LOWPASS_HZ, 0.7071f, sampleRate);
        cabinModes = bank(profile.cabinHz, profile.cabinQ);
        sympathetic = bank(profile.sympatheticHz, profile.sympatheticQ);
        sideHighpass = Biquad.highpass(profile.bassMonoHz, 0.7071f, sampleRate);
        ampK = 1f - (float) Math.exp(-1.0 / (EngineVoice.AMP_SMOOTH_SEC * sampleRate));
        gainK = 1f - (float) Math.exp(-1.0 / (EngineVoice.GAIN_SMOOTH_SEC * sampleRate));
        gearK = 1f - (float) Math.exp(-1.0 / (GEAR_SEC * sampleRate));
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
            // 静音着：不算，曲轴停在原地（重新出声时从这里接着走）
            for (int i = 0; i < 2 * frames; i++) {
                lr[i] = 0f;
            }
            gainNow = 0f;
            rpmNow = rpmTarget;
            limiter.reset();
            return;
        }
        PulseProfile p = this.p;
        float dt = frames / rate;
        float load = VirtualEngine.clamp01(params.load);
        float over = VirtualEngine.clamp01(params.overrun);
        float starter = VirtualEngine.clamp01(params.starter);
        boolean cranking = starter > 0.5f;
        boolean unfired = cranking || params.fire < 0.5f;
        boolean moving = params.speedKmh > VirtualEngine.START_LEAVE_KMH;
        if (params.catchSerial != lastCatch) {
            // 刚点着：头几圈偶尔缺火，发动机在支座上晃一下
            lastCatch = params.catchSerial;
            catchEnv = 1f;
            rock(moving ? ROLL_CATCH_ROCK : CATCH_ROCK);
        }
        if (unfired && !cranking && rpmTarget <= 0f && rpmNow > 0f) {
            // 熄火停住那一刻：再晃一下
            rock(moving ? MOVING_SETTLE_ROCK : SETTLE_ROCK);
        }
        catchEnv *= VirtualEngine.decay(dt, CATCH_SEC);
        float rpmCalc = Math.max(rpmTarget, 100f);

        // ---- 这一块里的事件（升挡断扭：燃烧减弱、沙哑少、偶尔「突」；没点火只有压缩的「噗」）
        float cut = VirtualEngine.clamp01(params.cut);
        evCut = cut;
        // 松油门：电控先把扭矩慢慢收掉，之后才断油（燃烧按 fuelOutSec 收到泵气）；踩下去马上回来
        float fuelTarget = 1f - over * (1f - p.pump);
        if (fuel < 0f || fuelTarget >= fuel) {
            fuel = fuelTarget;
        } else {
            fuel += (fuelTarget - fuel) * VirtualEngine.follow(dt, p.fuelOutSec);
        }
        evCombustion = cranking ? CRANK_LEVEL
                : unfired ? UNFIRED_LEVEL * VirtualEngine.clamp01(rpmTarget / UNFIRED_FULL_RPM)
                : fuel * (1f - cut * (1f - CUT_FLOOR));
        evSigma = mix(p.sigmaIdle, p.sigmaLoad, load) + over * p.sigmaOverrun;
        evMisfire = MISFIRE * catchEnv;
        evPop = p.popMax * VirtualEngine.clamp01(0.1f * over + 0.6f * params.burble + CUT_POP * cut);
        evRecovery = mix(p.recoveryIdle, p.recoveryLoad, load);
        evJitter = mix(p.jitterIdleDeg, p.jitterLoadDeg, load);
        evSamplesPerDeg = rate / (6f * Math.max(rpmTarget, 50f));
        float flowDb = raspFlowDb(rpmTarget, over);
        // 收油（断油）时沙哑往收油的电平靠（项目所有者 2026-10-22：松油门后「呼」的声音有点大，量下来就是它）
        evRasp = EngineVoice.dbToLin(mix(mix(p.raspIdleDb, p.raspLoadDb, load), p.raspOverrunDb, over) + flowDb
                + (cabin ? rpmDb(p.cabinRaspRpm, p.cabinRaspRpmDb, rpmCalc) : 0f));
        evTick = EngineVoice.dbToLin(p.tickDb) * (0.4f + 0.6f * Math.min(rpmTarget / 6000f, 1f));
        evKnock = EngineVoice.dbToLin(p.knockDb) * (0.35f + 0.65f * load) * evCombustion;
        evIntake = EngineVoice.dbToLin(mix(p.intakeIdleDb, p.intakeLoadDb, (float) Math.pow(load, 1.2)));

        // ---- 换挡：记号一变，「咚」从头开始响，「咔」写进环形缓冲
        if (!serialSeen) {
            serialSeen = true;
            lastSerial = params.shiftSerial;
        } else if (params.shiftSerial != lastSerial) {
            lastSerial = params.shiftSerial;
            shift(EngineVoice.dbToLin(p.thunkDb) * (0.3f + 0.7f * load) * (params.shiftUp ? 1f : DOWNSHIFT_THUNK));
        }

        // ---- 排气的系数
        float decaySec = p.decayDeg / (6f * rpmCalc);
        float riseSec = mix(p.riseIdleMs, p.riseLoadMs, load) * 1e-3f * (1f + over);
        if (riseSec > decaySec * 0.5f) {
            riseSec = decaySec * 0.5f;
        }
        float ad = perSample(decaySec);
        float ar = perSample(riseSec);
        double tp = Math.log(decaySec / riseSec) * decaySec * riseSec / (decaySec - riseSec);
        float pulseNorm = (float) (1.0 / (Math.exp(-tp / decaySec) - Math.exp(-tp / riseSec)));
        float ae = perSample(p.raspDecayDeg / (6f * rpmCalc));
        float bright = 1f + params.brightDb / 6f;
        float mufflerHz = Math.min(p.mufflerHz * (float) Math.pow(Math.max(rpmTarget, 500f) / 1000f, p.mufflerPow), p.mufflerTopHz)
                * (1f + p.mufflerLoad * load) * bright;
        muffler[0].setLowpass(mufflerHz, 0.6f, rate);
        muffler[1].setLowpass(mufflerHz, 0.6f, rate);
        // 排气温度：管子、消声器的共振跟着声速走
        float heatTarget = unfired ? 0f : VirtualEngine.clamp01(load * rpmTarget / p.heatFullRpm);
        if (heat < 0f) {
            heat = heatTarget;
        }
        heat += (heatTarget - heat) * VirtualEngine.follow(dt, heatTarget > heat ? p.heatUpSec : p.heatDownSec);
        float sound = (float) Math.sqrt((p.gasColdK + (p.gasHotK - p.gasColdK) * heat) / p.gasRefK);
        for (int b = 0; b < 2; b++) {
            pipeDelay[b] = Math.max(1f, Math.min(PIPE - 2, rate / (p.pipeHz[b] * sound)));
            int f = p.formantHz.length;
            for (int k = 0; k < f; k++) {
                formants[b][k].setPeaking(Math.min(p.formantHz[k] * sound, 0.45f * rate), p.formantDb[k], p.formantQ[k], rate);
            }
            formants[b][f].setPeaking(p.helmholtzHz[b] * sound, p.helmholtzDb, p.helmholtzQ, rate);
        }
        setTilt(Math.min(p.raspTiltHz * (float) Math.pow(Math.max(rpmCalc, 300f) / 2000f, p.raspTiltRpmPow), TILT_TOP_HZ));
        float drive = mix(p.driveIdle, p.driveLoad, load);
        float raspLevel = evRasp;
        float floorShare = Math.min(1f, p.raspFloor + 0.4f * over);
        float floor = floorShare * raspLevel * (unfired ? 0.2f : 1f);
        float kickShare = 1f - floorShare;

        // ---- 进气
        float aiD = perSample(INTAKE_DECAY_DEG / (6f * rpmCalc));
        float aiR = perSample(INTAKE_RISE_MS * 1e-3f);
        float wind = Math.min(Math.max(params.speedKmh, 0f) / RAM_KMH, 1.2f);
        float ram = 1f + p.inletRam * wind * wind;
        float roarTarget = EngineVoice.dbToLin(mix(p.roarIdleDb, p.roarLoadDb, (float) Math.pow(load, 1.5))
                + params.intakeBoostDb) * ram * VirtualEngine.clamp01(rpmTarget / ROAR_FULL_RPM);
        // 节气门的嘶声：缝里是声速的气流，流量只看缝多大 —— 转速高了每一股分到的少；节气门开大了缝两边没有压差，没了
        float shut = 1f - load;
        hissTarget = EngineVoice.dbToLin(p.hissDb) * shut * shut * VirtualEngine.clamp01(rpmTarget / ROAR_FULL_RPM);
        evHiss = hissTarget * (float) Math.sqrt(HISS_REF_RPM / rpmCalc);

        // ---- 增压：松油门 = 负荷比最近的峰值掉了不少
        loadPeak = Math.max(load, loadPeak * VirtualEngine.decay(dt, LIFT_PEAK_SEC));
        float drop = loadPeak - load;
        boolean lift = drop > LIFT_DROP || (loadPeak > 0.3f && load < 0.5f * loadPeak);
        if (lift) {
            loadPeak = load;
        }
        float whineTarget = 0f;
        float meshTarget = 0f;
        float jetTarget = 0f;
        float inletTarget = 0f;
        float whooshTarget = 0f;
        if (supercharged) {
            boost += (load - boost) * VirtualEngine.follow(dt, p.superchargerSec);
            if (lift && boost > 0.3f) {
                whoosh = Math.max(whoosh, boost * Math.min(1f, drop * 2f));
                decelKick = Math.max(decelKick, boost);
            }
            whoosh *= VirtualEngine.decay(dt, WHOOSH_SEC);
            decelKick *= VirtualEngine.decay(dt, p.decelBurstSec);
            // 踩下去起压那一下：负荷跳上去、增压还没跟上；负载时那层机械感跟着增压
            pressKick = Math.max(pressKick * VirtualEngine.decay(dt, p.pressBurstSec), VirtualEngine.clamp01(2f * (load - boost)));
            growlTarget = p.growlFree + (1f - p.growlFree) * boost;
            float revs = Math.min(rpmTarget / 6000f, 1.2f);
            float speed = (float) Math.pow(revs, p.superchargerRpmPow);
            // 几个音：随转速温和地长；齿轮声只在增压时有
            whineTarget = (float) Math.pow(Math.min(Math.max(rpmTarget, 300f), 7200f) / 3000f, p.toneRpmPow);
            meshTarget = whineTarget * (p.superchargerFree + (1f - p.superchargerFree) * boost)
                    * EngineVoice.dbToLin(meshRpmDb(p, rpmTarget));
            for (int k = 0; k < meshGains.length; k++) {
                meshGains[k] = EngineVoice.dbToLin(p.superchargerDb + meshHarmonicDb(p, rpmTarget, k));
            }
            // 每一下的幅度跟进气腔的绝对压力（怠速时只有大气压的几成、满增压时一倍多），不随转速变：转速高了一下接一下来得密、
            // 每一下也抖得快，总的能量和发动机的中频一样大致不变
            chordGain = cabin ? EngineVoice.dbToLin(rpmDb(p.cabinChordRpm, p.cabinChordRpmDb, rpmCalc)) : 1f;
            // 出口的压力脉动：跟增压（压比）的 1.5 次方；压缩把空气加热，风道里的声速变快，共振跟着往上走
            dischargeTarget = EngineVoice.dbToLin(p.dischargeDb) * whineTarget * boost * (float) Math.sqrt(boost);
            float charge = (float) Math.sqrt(1f + (p.chargeHeat - 1f) * boost);
            for (int s = 0; s < 2; s++) {
                duct[s].setPeaking(Math.min(p.ductHz[s] * charge, 0.45f * rate), p.ductDb, p.ductQ, rate);
            }
            evPulsation = EngineVoice.dbToLin(p.pulsationDb + rpmDb(p.pulsationRpm, p.pulsationRpmDb, rpmCalc))
                    * (p.pulsationFree + (1f - p.pulsationFree) * boost * (float) Math.sqrt(boost));
            for (int k = 0; k < pulsationGains.length; k++) {
                float hz = Math.min(p.pulsationOrders[k] * rpmCalc / 60f, rate * 0.45f);
                pulsationModes[0][k].setBandpass(hz, p.pulsationQ, rate);
                pulsationModes[1][k].setBandpass(hz, p.pulsationQ, rate);
                // 带通在中心频率处增益 1，一个脉冲进去响起来只有 2α / (1 + α)：折回来，最低那个共振每一下起头的幅度就是 pulsationDb；
                // 高处的共振宽、响得短，起头按阶次加，几个谱峰一样高；相邻的共振正负相间，免得每一下起头全叠在一起
                double alpha = Math.sin(2.0 * Math.PI * hz / rate) / (2.0 * p.pulsationQ);
                pulsationGains[k] = (float) ((k % 2 == 0 ? 1.0 : -1.0) * (1.0 + alpha) / (2.0 * alpha)
                        * p.pulsationOrders[k] / p.pulsationOrders[0]);
            }
            jetTarget = EngineVoice.dbToLin(p.jetDb) * (JET_FREE + (1f - JET_FREE) * boost) * speed;
            float flow = revs * (INLET_FREE + (1f - INLET_FREE) * load);
            inletTarget = EngineVoice.dbToLin(p.inletDb) * flow * (float) Math.sqrt(flow) * ram;
            whooshTarget = EngineVoice.dbToLin(WHOOSH_DB) * whoosh;
            // 松开油门以后的啸叫：节气门关着（负荷没了）才有，随转速落下去很快变淡；刚在增压就松开，那一下多响（节气门还没全关时就起）
            decelTarget = EngineVoice.dbToLin(p.decelDb)
                    * (float) Math.pow(Math.min(Math.max(rpmTarget, 300f), 7200f) / DECEL_REF_RPM, p.decelRpmPow)
                    * (shut * shut + p.decelBurst * decelKick);
        }

        // ---- 传动、倒挡、起动机（没点火时离合是开着的：发动机这边的变速箱、传动轴不响）
        boolean driving = params.drive && params.speedKmh > 1f && !unfired;
        float torque = Math.max(load, 0.6f * over);
        float gearOrder = params.gearIndex >= 0
                ? p.gearOrders[Math.min(params.gearIndex, p.gearOrders.length - 1)] : p.cvtOrder;
        float v = Math.max(params.speedKmh, 0f);
        float gearTarget = driving ? EngineVoice.dbToLin(p.gearDb) * torque : 0f;
        float diffTarget = driving ? EngineVoice.dbToLin(p.diffDb) * (0.3f + 0.7f * torque) * VirtualEngine.clamp01(v / 20f) : 0f;
        float shaftTarget = driving ? EngineVoice.dbToLin(p.shaftDb) * (0.3f + 0.7f * torque) * VirtualEngine.clamp01(v / 30f) : 0f;
        float diffStep = p.diffHzPerKmh * v / rate;
        float shaftStep = p.shaftHzPerKmh * v / rate;
        float reverseTarget = EngineVoice.dbToLin(EngineVoice.WHINE_DB) * VirtualEngine.clamp01(params.whineAmp);
        float starterTarget = EngineVoice.dbToLin(EngineVoice.STARTER_DB) * starter;
        float starterStep = EngineVoice.STARTER_WHINE_HZ * (0.8f + 0.2f * starter) / rate;

        // ---- 输出：电平收窄（踩油门主要变音色），升挡断扭时再掉一下
        float post = EngineVoice.dbToLin(p.rpmLoudDb * VirtualEngine.clamp01((rpmTarget - 800f) / 5200f)) * p.norm;
        // 收油时电平少掉一截（发动机仍被车拖着在转，见 PulseProfile#overrunLevelDb）
        float levelTarget = (float) Math.pow(Math.max(params.level, 0f) * EngineVoice.dbToLin(p.overrunLevelDb * over), levelPow)
                * EngineVoice.dbToLin(p.cutDipDb * cut);
        boolean toneOn = params.toneHz < EngineVoice.TONE_BYPASS_HZ;
        if (toneOn && Math.abs(params.toneHz - toneSetHz) > 1f) {
            tone[0].setLowpass(params.toneHz, 0.7071f, rate);
            tone[1].setLowpass(params.toneHz, 0.7071f, rate);
            toneSetHz = params.toneHz;
        }
        float rpmStep = (rpmTarget - rpmNow) / frames;

        for (int i = 0; i < frames; i++) {
            float r = rpmNow + rpmStep * (i + 1);
            advance(6.0 * r / rate);
            int cur = cursor;

            // 排气：每排一套
            for (int b = 0; b < 2; b++) {
                float x = exc[b][cur];
                exc[b][cur] = 0f;
                pulseDecay[b] = ad * pulseDecay[b] + x;
                pulseRise[b] = ar * pulseRise[b] + x;
                float pulse = (pulseDecay[b] - pulseRise[b]) * pulseNorm;
                float xp = pop[b][cur];
                pop[b][cur] = 0f;
                popDecay[b] = popDecayA * popDecay[b] + xp;
                popRise[b] = popRiseA * popRise[b] + xp;
                float k = kick[b][cur];
                kick[b][cur] = 0f;
                envelope[b] = ae * envelope[b] + k;
                float w = white();
                tilt[b] += tiltK * (w - tilt[b]);
                float nz = noisePeak[b].process(noiseHighpass[b].process(tilt[b] * tiltNorm + p.raspAir * w));
                float u = saturate(pulse + (popDecay[b] - popRise[b]) + (envelope[b] * kickShare + floor) * nz, drive);
                int pd = (int) pipeDelay[b];
                float pf = pipeDelay[b] - pd;
                float p0 = pipe[b][(pipeWrite - pd) & PIPE_MASK];
                float delayed = p0 + (pipe[b][(pipeWrite - pd - 1) & PIPE_MASK] - p0) * pf;
                pipeLoss[b] += pipeK * (delayed - pipeLoss[b]);
                float y = u + p.pipeFeedback * pipeLoss[b];
                pipe[b][pipeWrite] = y;
                for (Biquad f : formants[b]) {
                    y = f.process(y);
                }
                y = muffler[b].process(y);
                bankOut[b] = radiation[b].process(y);
            }
            pipeWrite = (pipeWrite + 1) & PIPE_MASK;
            float left = bankOut[0];
            float right = bankOut[1];

            // 进气
            float xi = intake[cur];
            intake[cur] = 0f;
            intakeDecay = aiD * intakeDecay + xi;
            intakeRise = aiR * intakeRise + xi;
            float ip = intakeDecay - intakeRise;
            roarNow += (roarTarget - roarNow) * ampK;
            float front = plenum.process(ip) * 1.5f + runner.process(ip) + roar.process(white()) * roarNow;
            // 节气门的嘶声：每吸一口气涌一股，底下一层一直有
            float xh = hiss[cur];
            hiss[cur] = 0f;
            hissDecay = hissDecayA * hissDecay + xh;
            hissRise = hissRiseA * hissRise + xh;
            hissNow += (hissTarget - hissNow) * ampK;
            // 车内听感时它和发动机舱别的声音一样隔着防火墙（2026-10-24：走挡风玻璃下面那一路时，车内怠速 2–8 kHz 的底噪高了 7–11 dB）
            front += hissBand2.process(hissBand.process(hissWhite())) * (p.hissFloor * hissNow + (hissDecay - hissRise) * hissNorm);
            // 车内听感：发动机舱顶上、挡风玻璃下面的增压器的音不过整个防火墙（CABIN_TONE_DB），左右各一路
            float cowlL = 0f;
            float cowlR = 0f;
            // 发动机舱里摆了位置的音（齿轮声的几个来源、两排进气腔、松开以后的啸叫）：左、右
            float sideL = 0f;
            float sideR = 0f;

            // 增压：齿轮的啮合声、出口的压力脉动（左右两个中冷器）、进口的叶片声，轻的一层气流声（左右两路）、进口的风声、松油门的「呼」
            float jetL = 0f;
            float jetR = 0f;
            float inlet = 0f;
            float cabinTonesL = 0f;
            float cabinTonesR = 0f;
            float housing = 0f;
            if (supercharged) {
                // 曲轴每次点火快一下，皮带带着增压器一起：点火频率上一个很小的、周期性的调频（不是随机的抖动）
                float hz = p.superchargerOrder * r / 60f * (1f + p.torsion * EngineVoice.sin(theta / 90.0));
                lobePhase += hz / rate;
                if (lobePhase >= 1.0) {
                    lobePhase -= 1.0;
                }
                femalePhase += hz / p.rotorLobes[0] / rate;
                if (femalePhase >= 1.0) {
                    femalePhase -= 1.0;
                }
                malePhase += hz / p.rotorLobes[1] / rate;
                if (malePhase >= 1.0) {
                    malePhase -= 1.0;
                }
                whineNow += (whineTarget - whineNow) * ampK;
                meshNow += (meshTarget - meshNow) * ampK;
                jetNow += (jetTarget - jetNow) * ampK;
                inletNow += (inletTarget - inletNow) * ampK;
                whooshNow += (whooshTarget - whooshNow) * ampK;
                float c = 0.5f + 0.5f * EngineVoice.sin(lobePhase + 0.25);
                float push = 1f + p.jetPulse * (c * c * c / PUFF_MEAN - 1f);
                jetL = airLowpass[0].process(jets(jetBands[0], white() * push)) * jetNow;
                jetR = airLowpass[1].process(jets(jetBands[1], white() * push)) * jetNow;
                inlet = inletBand.process(white()) * inletNow;
                // 出口的压力脉动（左、右两个中冷器）
                dischargeNow += (dischargeTarget - dischargeNow) * ampK;
                // 负载时齿轮受力：转子每转一圈、每次点火都调一下（边带落在整数阶之间），不增压时剩一点
                growlNow += (growlTarget - growlNow) * ampK;
                pressNow += (pressKick - pressNow) * ampK;
                float rotor = 1f + growlNow * (p.rotorAm[0] * EngineVoice.sin(femalePhase)
                        + p.rotorAm[1] * EngineVoice.sin(malePhase + MALE_PHASE));
                float mod = rotor * (1f + growlNow * p.superchargerAm * EngineVoice.sin(theta / 90.0));
                float d = discharge(hz) * dischargeNow * mod;
                float dl = duct[0].process(intercooler[0].process(d));
                float dr = duct[1].process(intercooler[1].process(d));
                whine(hz);
                float loadedL = whineL * mod;
                float loadedR = whineR * mod;
                // 负载时壳体被出口的压力顶着、齿轮受力：调过的齿轮声、出口脉动经壳体、支座传出去（偏中低频，不过防火墙）
                float shell = 0f;
                float loaded = (loadedL + loadedR) * (0.5f / CENTER) + d;
                for (Biquad m : bodyModes) {
                    shell += m.process(loaded);
                }
                housing = bodyTop.process(shell) * bodyGain;
                float pl = pulsation(0, cur) * chordGain;
                float pr = pulsation(1, cur) * chordGain;
                decelNow += (decelTarget - decelNow) * ampK;
                float lifted = decelNow > 1e-6f ? CENTER * decel(hz) * decelNow : 0f;
                float toneL = loadedL + plenumLeft[0] * pl + plenumLeft[1] * pr + lifted;
                float toneR = loadedR + plenumRight[0] * pl + plenumRight[1] * pr + lifted;
                if (cabin) {
                    // 两边的中冷器一左一右（原来在车里合成一路居中）
                    cowlL += toneL + 0.5f * (jetNear * dl + jetFar * dr);
                    cowlR += toneR + 0.5f * (jetFar * dl + jetNear * dr);
                } else {
                    sideL += toneL;
                    sideR += toneR;
                    jetL += dl;
                    jetR += dr;
                }
                front += whooshBand.process(white()) * whooshNow;
            }
            if (cabin) {
                toneLpL += toneK * (cowlL - toneLpL);
                toneLp2L += toneK * (toneLpL - toneLp2L);
                toneLpR += toneK * (cowlR - toneLpR);
                toneLp2R += toneK * (toneLpR - toneLp2R);
                cabinTonesL = toneLp2L * cabinToneGain;
                cabinTonesR = toneLp2R * cabinToneGain;
            }

            // 车厢共振、和鸣（不分前后左右）
            float both = left + right;
            float low = cabinLowpass.process(both);
            float room = 0f;
            for (int k = 0; k < cabinModes.length; k++) {
                room += p.cabinGain[k] * cabinModes[k].process(low);
            }
            for (int k = 0; k < sympathetic.length; k++) {
                room += p.sympatheticGain[k] * sympathetic[k].process(both);
            }

            // 气门落座、燃烧（每个缸在自己的位置上，写进来时已经摆好）
            float tickL = modes(tickModes[0], tick[0], cur);
            float tickR = modes(tickModes[1], tick[1], cur);
            float knockL = modes(knockModes[0], knock[0], cur);
            float knockR = modes(knockModes[1], knock[1], cur);

            // 发动机舱在前：进气、增压器居中，进气口左前，两边的中冷器；排气在后：两根尾管在车后两角
            float frontL = CENTER * front + sideL + inletLeft * inlet + jetNear * jetL + jetFar * jetR + tickL + knockL;
            float frontR = CENTER * front + sideR + inletRight * inlet + jetFar * jetL + jetNear * jetR + tickR + knockR;
            float rearL = left + p.cross * right;
            float rearR = right + p.cross * left;
            if (cabin) {
                frontL = lowpass2(frontLp[0], frontK, frontL) + cabinTonesL;
                frontR = lowpass2(frontLp[1], frontK, frontR) + cabinTonesR;
                rearL = lowpass2(rearLp[0], rearK, rearL);
                rearR = lowpass2(rearLp[1], rearK, rearR);
            }
            float l = rearL + frontL + CENTER * (room + housing);
            float rr = rearR + frontR + CENTER * (room + housing);
            float mid = 0.5f * (l + rr);
            // 低频左右一样；中高频本来就有的左右差再放大一点（一阶搁架，左右相加不变）
            float side = sideHighpass.process(0.5f * (l - rr));
            widthLp += widthK * (side - widthLp);
            side += (widthGain - 1f) * (side - widthLp);
            levelNow += (levelTarget - levelNow) * gainK;
            float g = post * levelNow;
            l = (mid + side) * g;
            rr = (mid - side) * g;

            // 传动、倒挡、起动机（只跟总音量）
            float extra = 0f;
            gearNow += (gearTarget - gearNow) * gearK;
            diffNow += (diffTarget - diffNow) * ampK;
            shaftNow += (shaftTarget - shaftNow) * ampK;
            if (gearNow > 1e-6f) {
                gearPhase += gearOrder * r / 60f / rate;
                if (gearPhase >= 1.0) {
                    gearPhase -= 1.0;
                }
                extra += gearNow * (EngineVoice.sin(gearPhase) + 0.3f * EngineVoice.sin(2.0 * gearPhase));
            }
            if (diffNow > 1e-6f) {
                diffPhase += diffStep;
                if (diffPhase >= 1.0) {
                    diffPhase -= 1.0;
                }
                extra += diffNow * EngineVoice.sin(diffPhase);
            }
            if (shaftNow > 1e-6f) {
                shaftPhase += shaftStep;
                if (shaftPhase >= 1.0) {
                    shaftPhase -= 1.0;
                }
                extra += shaftNow * EngineVoice.sin(shaftPhase);
            }
            reverseNow += (reverseTarget - reverseNow) * ampK;
            if (reverseNow > 1e-5f && params.whineHz > 0f) {
                reversePhase += params.whineHz / rate;
                if (reversePhase >= 1.0) {
                    reversePhase -= 1.0;
                }
                extra += reverseNow * (EngineVoice.sin(reversePhase) + 0.3f * EngineVoice.sin(2.0 * reversePhase));
            }
            starterNow += (starterTarget - starterNow) * ampK;
            if (starterNow > 1e-5f) {
                starterPhase += starterStep;
                if (starterPhase >= 1.0) {
                    starterPhase -= 1.0;
                }
                float compression = 0.55f + 0.45f * EngineVoice.sin(theta / 90.0);
                float buzz = EngineVoice.sin(starterPhase) + 0.35f * EngineVoice.sin(2.0 * starterPhase) + 0.25f * nextNoise();
                extra += starterNow * compression * buzz;
            }
            // 换挡的「咚」「咔」（地板中间）
            for (int k = 0; k < thunkEnv.length; k++) {
                if (thunkEnv[k] > 1e-7f) {
                    thunkEnv[k] *= thunkDecay[k];
                    thunkPhase[k] += thunkStep[k];
                    if (thunkPhase[k] >= 1.0) {
                        thunkPhase[k] -= 1.0;
                    }
                    extra += thunkEnv[k] * EngineVoice.sin(thunkPhase[k]);
                }
            }
            float xc = click[cur];
            click[cur] = 0f;
            extra += clickBand.process(xc);
            // 点着、停住时发动机晃一下（起得快但不是一下跳上去，不出「嗒」）
            if (rockEnv > 1e-7f || rockNow > 1e-7f) {
                rockEnv *= rockDecay;
                rockNow += (rockEnv - rockNow) * rockAttackK;
                rockPhase += rockStep;
                if (rockPhase >= 1.0) {
                    rockPhase -= 1.0;
                }
                extra += rockNow * EngineVoice.sin(rockPhase);
            }
            l += extra;
            rr += extra;
            if (cabin) {
                l = cabinLow[0].process(l) * cabinGain;
                rr = cabinLow[1].process(rr) * cabinGain;
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

    /** 此刻排气里的声速比参考温度时快多少（倍；管子、消声器的共振跟着它走）：测试看排气热没热起来。 */
    float gasScale() {
        return heat < 0f ? 1f : (float) Math.sqrt((p.gasColdK + (p.gasHotK - p.gasColdK) * heat) / p.gasRefK);
    }

    /** 缸号 {@code cylinder} 和同一排上一个点火隔多少曲轴角（点火顺序定死的；测试看它算对没有）。 */
    float sameBankGapDeg(int cylinder) {
        return gapDeg[cylinder - 1];
    }

    /** 缸号 {@code cylinder} 的位置：{响度, 晚到多少样本, 进左声道, 进右声道}（测试看摆对没有）。 */
    float[] cylinderPlace(int cylinder) {
        int c = cylinder - 1;
        return new float[] {cylGain[c], cylDelay[c], cylLeft[c], cylRight[c]};
    }

    /** 曲轴往前转 {@code degrees}：经过的事件按发生的位置（样本以内的小数）触发。 */
    private void advance(double degrees) {
        if (!(degrees > 0.0)) {
            return;
        }
        double end = theta + degrees;
        while (nextAngle <= end) {
            float at = 1f - (float) ((end - nextAngle) / degrees);
            fire(evKind[nextEvent], evCyl[nextEvent], at);
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

    /** 一件事发生在当前样本往后 {@code at}（0–1）个样本处。 */
    private void fire(int kind, int cylinder, float at) {
        int c = cylinder - 1;
        int b = bankOf[c];
        if (kind == EXHAUST) {
            float variation = Math.max(0f, 1f + evSigma * gauss());
            float level = evCombustion;
            if (evMisfire > 0f && random01() < evMisfire) {
                level = p.pump;
            }
            float recovery = Math.max(0.15f, 1f - evRecovery * (float) Math.exp(-gapDeg[c] / p.recoveryDeg));
            float a = p.trim[c] * recovery * variation * level;
            // 时机：歧管、排气管长短、尾管比发动机舱晚到的那一段 + 随机偏移（N(0, 抖动) 度，围着固定的 3 倍抖动，保证不往回排）
            float delay = headerSamples[c] + bankSamples[b] + lagSamples + evJitter * (3f + gauss()) * evSamplesPerDeg;
            float q = at + Math.max(0f, Math.min(MAX_DELAY, delay));
            add(exc[b], q, a);
            add(kick[b], q, a * evRasp * (0.4f + 0.6f * level) * (1f - CUT_RASP * evCut));
            if (evPop > 0f && random01() < evPop) {
                add(pop[b], q + random01() * POP_SPREAD, p.popGain * (0.6f + 0.8f * random01()));
                add(kick[b], q, 3f * evRasp);
            }
        } else if (kind == EXHAUST_CLOSE || kind == INTAKE_CLOSE) {
            float a = evTick * (0.6f + 0.4f * random01()) * cylGain[c];
            add(tick[0], at + cylDelay[c], a * cylLeft[c]);
            add(tick[1], at + cylDelay[c], a * cylRight[c]);
            if (kind == INTAKE_CLOSE) {
                add(intake, at, evIntake * (0.9f + 0.2f * random01()));
                add(hiss, at, evHiss);
                if (evPulsation > 0f) {
                    // 同一排上一下留下的压力还没恢复：这一下弱一点、晚一点（两排的样子一样、错开一圈 → 整数阶）
                    float rec = Math.max(0.15f, 1f - p.pulsationRecovery * (float) Math.exp(-gapDeg[c] / p.recoveryDeg));
                    add(pulsation[bankOf[c]], at + pulsationLagSamples * (1f - rec), evPulsation * rec * (0.97f + 0.06f * random01()));
                }
            }
        } else {
            float a = evKnock * (0.8f + 0.4f * random01()) * cylGain[c];
            add(knock[0], at + cylDelay[c], a * cylLeft[c]);
            add(knock[1], at + cylDelay[c], a * cylRight[c]);
        }
    }

    /** 换挡：「咚」的几个低频从头响起（{@code amp} 是满的那一个），「咔」—— 一小段很快落下去的噪声 —— 写进环形缓冲。 */
    private void shift(float amp) {
        for (int k = 0; k < thunkEnv.length; k++) {
            thunkEnv[k] = amp * EngineVoice.dbToLin(p.thunkGainDb[k]);
            thunkPhase[k] = 0.0;
        }
        int n = Math.min((int) (CLICK_SEC * rate), RING - 2);
        float d = perSample(CLICK_DECAY_SEC);
        float env = amp * EngineVoice.dbToLin(p.clickDb);
        for (int i = 0; i < n; i++) {
            click[(cursor + 1 + i) & MASK] += env * clickWhite();
            env *= d;
        }
    }

    /** 发动机在支座上晃一下：一个从零点起、按 {@link PulseProfile#rockMs} 落下去的低频（{@code amp} 倍 {@link PulseProfile#rockDb}）。 */
    private void rock(float amp) {
        rockEnv = Math.max(rockEnv, amp * EngineVoice.dbToLin(p.rockDb));
        rockPhase = 0.0;
    }

    /**
     * 增压器这一个样本的啸叫（放在 {@link #whineL}、{@link #whineR}，还没调）：叶片基频（任何负荷都有，踩下去起压那一下猛一下，
     * 居中）+ 齿轮啮合的几次谐波（只在增压时有，几个来源摆开：{@link PulseProfile#meshSpread}）。
     * {@code hz} 是这一刻叶片扫过出口的频率（相位已经走过了）。
     */
    private void whine(float hz) {
        float lobe = lobeGain * (1f + p.pressBurst * pressNow) * VirtualEngine.clamp01((WHINE_TOP_HZ - hz) / WHINE_FADE_HZ)
                * EngineVoice.sin(lobePhase) * whineNow;
        float wl = CENTER * lobe;
        float wr = CENTER * lobe;
        // 齿轮声的几个来源各自慢慢游移（{@link PulseProfile#meshVoiceDetune}）：同一个阶次上一小团，此起彼伏
        for (int v = 0; v < voicePhase.length; v++) {
            voiceNoise[v] += voiceK * (white() - voiceNoise[v]);
            voicePhase[v] += hz * (p.meshVoiceDetune[v] + p.meshVoiceWander * voiceNorm * voiceNoise[v]) / rate;
            voicePhase[v] -= Math.floor(voicePhase[v]);
        }
        for (int k = 0; k < meshGains.length; k++) {
            float m = p.meshMultiple * (k + 1);
            float fade = (WHINE_TOP_HZ - m * hz) / WHINE_FADE_HZ;
            if (fade <= 0f) {
                break;
            }
            float g = meshGains[k] * Math.min(fade, 1f) * voiceScale * meshNow;
            for (int v = 0; v < voicePhase.length; v++) {
                float s = g * EngineVoice.sin(m * (lobePhase + voicePhase[v]));
                wl += voiceLeft[v] * s;
                wr += voiceRight[v] * s;
            }
        }
        whineL = wl;
        whineR = wr;
    }

    /**
     * 出口的压力脉动这一个样本（{@link PulseProfile#dischargeDb}，还没乘电平）：叶片频率的几次谐波，各自固定的相位（不是随机的），
     * 到 {@link #WHINE_TOP_HZ} 渐隐。{@code hz} 是这一刻叶片扫过出口的频率（相位已经走过了）。
     */
    private float discharge(float hz) {
        float d = 0f;
        for (int k = 0; k < dischargeGains.length; k++) {
            int m = p.dischargeHarmonics[k];
            float fade = (WHINE_TOP_HZ - m * hz) / WHINE_FADE_HZ;
            if (fade <= 0f) {
                break;
            }
            d += dischargeGains[k] * Math.min(fade, 1f) * EngineVoice.sin(m * lobePhase + 0.25 * k);
        }
        return d;
    }

    /**
     * 松开油门以后的啸叫这一个样本（{@link PulseProfile#decelDb}，还没乘电平）：叶片频率的几次谐波，各自固定的相位（不是随机的），
     * 到 {@link #WHINE_TOP_HZ} 渐隐。{@code hz} 是这一刻叶片扫过出口的频率（相位已经走过了）。
     */
    private float decel(float hz) {
        float d = 0f;
        for (int k = 0; k < decelGains.length; k++) {
            int m = p.decelHarmonics[k];
            float fade = (WHINE_TOP_HZ - m * hz) / WHINE_FADE_HZ;
            if (fade <= 0f) {
                break;
            }
            d += decelGains[k] * Math.min(fade, 1f) * EngineVoice.sin(m * lobePhase + DECEL_PHASE * k);
        }
        return d;
    }

    /**
     * 第 {@code bank} 排进气腔的压力脉动这一个样本（{@link PulseProfile#pulsationDb}）：两排加起来是发动机的整数阶（半阶抵掉），
     * 和齿轮声一起在发动机舱顶上；各排从自己那一边出来（{@link PulseProfile#plenumPan}）。
     */
    private float pulsation(int bank, int cur) {
        float xs = pulsation[bank][cur];
        pulsation[bank][cur] = 0f;
        float puls = 0f;
        Biquad[] modes = pulsationModes[bank];
        for (int k = 0; k < modes.length; k++) {
            puls += pulsationGains[k] * modes[k].process(xs);
        }
        return pulsationTop[bank].process(puls);
    }

    /** 齿轮声随转速另加的 dB（{@link PulseProfile#meshRpmDb}）：按转速的对数在表里插值，两头照最边上的。 */
    static float meshRpmDb(PulseProfile p, float rpm) {
        return rpmDb(p.meshRpm, p.meshRpmDb, rpm);
    }

    /** 一张随转速变的 dB 表（转速 {@code at}、值 {@code db}）这个转速下的值：按转速的对数插值，两头照最边上的。 */
    static float rpmDb(float[] at, float[] db, float rpm) {
        double f = rpmPosition(at, rpm);
        int i = (int) f;
        return i >= db.length - 1 ? db[db.length - 1] : (float) (db[i] + (db[i + 1] - db[i]) * (f - i));
    }

    /** 齿轮声第 {@code k + 1} 次谐波这个转速下的电平（dB，相对第 2 次；{@link PulseProfile#meshHarmonicsDb}，插值同上）。 */
    static float meshHarmonicDb(PulseProfile p, float rpm, int k) {
        float[][] db = p.meshHarmonicsDb;
        double f = rpmPosition(p.meshRpm, rpm);
        int i = (int) f;
        return i >= db.length - 1 ? db[db.length - 1][k] : (float) (db[i][k] + (db[i + 1][k] - db[i][k]) * (f - i));
    }

    /** {@code rpm} 落在转速表 {@code at} 的哪儿：整数部分是下边那一格，小数是按转速的对数插值的位置；两头照最边上的。 */
    static double rpmPosition(float[] at, float rpm) {
        if (rpm <= at[0]) {
            return 0.0;
        }
        for (int i = 1; i < at.length; i++) {
            if (rpm <= at[i]) {
                return i - 1 + Math.log(rpm / at[i - 1]) / Math.log(at[i] / at[i - 1]);
            }
        }
        return at.length - 1;
    }

    /** 沙哑随流量（转速）多几 dB：以 {@link PulseProfile#raspRefRpm} 为准，每翻一倍按带负荷 / 收油的斜率，最多多 {@link PulseProfile#raspRpmMaxDb}。 */
    private float raspFlowDb(float rpm, float over) {
        double octaves = Math.log(Math.max(rpm, 300f) / p.raspRefRpm) / Math.log(2.0);
        float db = (float) (mix(p.raspRpmDb[0], p.raspRpmDb[1], over) * octaves);
        return VirtualEngine.clamp(db, -24f, p.raspRpmMaxDb);
    }

    /** 沙哑的拐点：一阶低通的系数、把输出的方差拉回 1。 */
    private void setTilt(float hz) {
        tiltK = onePole(hz);
        tiltNorm = (float) (1.0 / Math.sqrt(tiltK / (2.0 - tiltK)));
    }

    /** 两个一阶低通串起来（车内听感：隔着防火墙、车身）。 */
    private static float lowpass2(float[] state, float[] k, float x) {
        state[0] += k[0] * (x - state[0]);
        state[1] += k[1] * (state[0] - state[1]);
        return state[1];
    }

    /** 等功率摆位：位置 {@code x}（−1 左 … +1 右）进左、右声道各多少（居中各 0.707）。 */
    static float panLeft(float x) {
        return (float) Math.cos(VirtualEngine.clamp01((x + 1f) / 2f) * Math.PI / 2.0);
    }

    static float panRight(float x) {
        return (float) Math.sin(VirtualEngine.clamp01((x + 1f) / 2f) * Math.PI / 2.0);
    }

    /** 在当前样本往后 {@code q} 个样本处加一下（落在两个样本之间就按距离分开）。 */
    private void add(float[] ring, float q, float amp) {
        int i = (int) q;
        float w = q - i;
        ring[(cursor + i) & MASK] += amp * (1f - w);
        ring[(cursor + i + 1) & MASK] += amp * w;
    }

    /** 出口的一路气流：过几个通道的共振，按各自的电平加起来。 */
    private float jets(Biquad[] bands, float x) {
        float y = 0f;
        for (int k = 0; k < bands.length; k++) {
            y += jetGains[k] * bands[k].process(x);
        }
        return y;
    }

    /** 读走这一格的激励，过一组共振，加起来。 */
    private static float modes(Biquad[] bank, float[] ring, int cur) {
        float x = ring[cur];
        ring[cur] = 0f;
        float y = 0f;
        for (Biquad m : bank) {
            y += m.process(x);
        }
        return y;
    }

    private Biquad[] bank(float[] hz, float[] q) {
        Biquad[] out = new Biquad[hz.length];
        for (int k = 0; k < hz.length; k++) {
            out[k] = Biquad.bandpass(hz[k], q[k], rate);
        }
        return out;
    }

    private void sortEvents() {
        for (int i = 1; i < evAngle.length; i++) {
            float a = evAngle[i];
            int k = evKind[i];
            int c = evCyl[i];
            int j = i - 1;
            while (j >= 0 && evAngle[j] > a) {
                evAngle[j + 1] = evAngle[j];
                evKind[j + 1] = evKind[j];
                evCyl[j + 1] = evCyl[j];
                j--;
            }
            evAngle[j + 1] = a;
            evKind[j + 1] = k;
            evCyl[j + 1] = c;
        }
    }

    /** 饱和：小信号原样，压力大时圆滑地压住（{@code drive} 越大越早压）。 */
    static float saturate(float x, float drive) {
        if (drive <= 1e-3f) {
            return x;
        }
        float y = x * drive;
        float t;
        if (y >= 3f) {
            t = 1f;
        } else if (y <= -3f) {
            t = -1f;
        } else {
            float y2 = y * y;
            t = y * (27f + y2) / (27f + 9f * y2);
        }
        return t / drive;
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

    /** 「咔」的白噪声，方差 1。 */
    private float clickWhite() {
        int x = clickNoise;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        clickNoise = x;
        return x * (1.7320508f / 2147483648f);
    }

    /** 节气门嘶声的白噪声，方差 1。 */
    private float hissWhite() {
        int x = hissNoise;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        hissNoise = x;
        return x * (1.7320508f / 2147483648f);
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
