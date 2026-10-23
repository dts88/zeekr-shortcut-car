package com.kooo.evcam.sound;

/**
 * 虚拟发动机：车速、油门（开度和踩的快慢）、加减速度、刹车、动力流、档位 → 转速和负荷（带惯性），再给出电平和几样音色参数。
 * 纯逻辑（{@code VirtualEngineTest}），声音线程上每块推进一次（{@link #step}），不分配内存。
 * 做法在 docs/local/drive-sound-plan.md §2、§9；参数在 {@link SoundProfile}。
 *
 * <h3>几样输入各管什么（项目所有者 2026-10-21：油门开度、踩油门的快慢、车速、加减速度都要用上）</h3>
 *
 * <ul>
 *   <li><b>油门开度</b> → 负荷（驾驶员要多少）：3 % 以下算没踩，85 % 算满，中间按 0.8 次方（小油门也听得出）。</li>
 *   <li><b>加速度</b> → 车真正出了多少力：电机在出力时，加速度换算的负荷和油门的取大的（自适应巡航自己加速时油门是 0，靠它）；
 *       也让声音在猛加速时厚一点。</li>
 *   <li><b>踩油门的快慢</b> → 急踩：负荷和转速起得更快、转速冲一下、进气声和高阶次猛一下，模拟换挡时直接降到该在的挡
 *       （可以连降几挡；慢慢踩到地板附近也一样，像自动挡的强制降挡开关）；
 *       缓踩没有这些，平顺地往上走。</li>
 *   <li><b>松油门的快慢</b> → 急松（D 挡 15 km/h 以上）：排气那一路轻微的「突突」，几百毫秒淡掉；不做像枪声的放炮。</li>
 *   <li><b>减速度</b> → 模拟换挡时重刹、回收越猛，降挡越早，降挡带补油的「轰」一下。</li>
 *   <li><b>车速</b> → 转速的一部分（连续）或挡位（模拟换挡）；电平随车速略升（盖过胎噪风噪）。</li>
 *   <li><b>刹车、动力流</b> → 回收或踩刹车时负荷归零（单踏板时油门轻踩到 5–12 % 还在回收，只看油门分不出来）。</li>
 *   <li><b>档位</b>：D 照两种走法；倒挡固定在倒挡齿比上、永远不升挡（上限 {@link #REVERSE_MAX_RPM}）；P / N 空挡，转速只跟油门
 *       （上限 {@link #FREE_REV_MAX_RPM}，只在车内）。</li>
 *   <li><b>伪挡位</b>（项目所有者 2026-10-21）：连续走法下油门踩到 {@link #PSEUDO_ON_PCT} 以上（或一脚到底），也按挡位走锯齿，
 *       和模拟换挡一样升挡；油门回到 {@link #PSEUDO_OFF_PCT} 以下、踩刹车或回收时回到连续。</li>
 *   <li><b>升挡</b>（两种走法一样）：先断扭（合成器那边燃烧减弱、电平掉一点），转速停一下再按 S 形落到新挡位，
 *       动力回来时「吼」一下；每次换挡给合成器一个记号（{@link #shiftSerial}），它据此放换挡的「咚」。</li>
 * </ul>
 *
 * <h3>开始工作（{@link #ignite}）、停下来（{@link #shutdown}）</h3>
 *
 * <p>车停着：启动声 —— 起动机转动、点着、转速冲到 {@link #START_FLARE_RPM}、回落到怠速（{@link Phase#STARTING}）；
 * 不带起动机的风格（项目所有者 2026-10-21：「美式 V8」不要起动机那几声）直接从点着开始。点着那一下总音量、亮度、进气多冲一点
 * （项目所有者 2026-10-22：体感再强 20 % 左右）。之后一直转着：停着就是怠速（项目所有者 2026-10-21 选的）。</p>
 *
 * <p>车在走：像混动车行驶中起动发动机（{@link Phase#JOINING}）—— 电机先把它带到此刻该有的转速（不点火，只有压缩的「噗」），
 * 点着时补油冲一下；模拟换挡顺势降一挡，踩着刹车或在回收时不冲。项目所有者 2026-10-22 问开阀门还是混动车起动发动机：
 * 建议后者，和停着时的点火、熄火是一个说法（运动模式 = 发动机工作），项目所有者同意；原来开阀门式的淡入见
 * docs/local/drive-sound-plan.md §12.14。</p>
 *
 * <p>熄火（{@link Phase#STOPPING}，切出运动模式、下电时 {@link DriveSound} 叫）：断油断火，没有燃烧，只剩压缩的「噗」，转速自己落到 0；
 * 最后几圈每次压缩慢一下，一顿一顿；停住那一下发动机在支座上晃一下（合成器）。车停着照真车熄火（参照车主偏好的那台机械增压 V8：
 * 怠速约 0.7 秒停住）；车在走像混动车行驶中停发动机，电机帮着拖停，快一些。</p>
 */
final class VirtualEngine {

    /** 转速怎么跟车走（项目所有者 2026-10-21：两种都做，上车听了再定）。 */
    enum RpmMode { CONTINUOUS, SHIFTS }

    enum Gear { P, R, N, D, UNKNOWN }

    /** 动力流（{@code Signal.POWER_FLOW}）。 */
    enum Flow { DRIVE, REGEN, IDLE, NOT_READY, UNKNOWN }

    enum Phase { STARTING, JOINING, RUNNING, STOPPING }

    /** 一块的输入：声音线程里重用一份，不分配。 */
    static final class Input {
        /** 车速（km/h，非负）；读不到传 NaN（当 0）。 */
        float speedKmh;
        /** 油门（%）；读不到传 NaN（当没踩）。 */
        float throttlePct = Float.NaN;
        /** 踩油门的快慢（%/s）：正 = 踩下，负 = 松开（{@link DriveInputs}）。 */
        float pedalRate;
        /** 加速度（m/s²）：正 = 加速，负 = 减速（{@link DriveInputs}）。 */
        float accel;
        /** 踩着刹车（深度超过 {@link #BRAKE_ON}，或踏板 = 1）。 */
        boolean braking;
        Gear gear = Gear.UNKNOWN;
        Flow flow = Flow.UNKNOWN;
        RpmMode mode = RpmMode.CONTINUOUS;
    }

    // ---- 负荷
    static final float THROTTLE_DEADBAND = 3f;
    static final float THROTTLE_FULL = 85f;
    static final float LOAD_CURVE = 0.8f;
    /** 刹车行程超过这个（%）算踩着。 */
    static final float BRAKE_ON = 3f;
    /** 加速度到这个（m/s²）算车出满了力：7X 后驱 0–100 km/h 约 5.8 s（平均 4.8 m/s²），四驱约 3.8 s。 */
    static final float ACCEL_FULL = 5f;
    /** 加速度换算的负荷打几折再和油门的比：油门是驾驶员要的，加速度是车给的。 */
    static final float ACCEL_LOAD_WEIGHT = 0.8f;
    static final float EFFORT_SMOOTH_SEC = 0.2f;

    // ---- 踩 / 松油门的快慢
    /** 踩得比这慢（%/s）算缓踩：0 踩到 50 % 用 2 秒是 25 %/s。 */
    static final float STAB_FROM = 80f;
    /** 踩到这么快（%/s）算猛踩到底：0.2 秒踩到底是 500 %/s。 */
    static final float STAB_FULL = 500f;
    static final float STAB_DECAY_SEC = 0.5f;
    /** 猛踩时负荷起得快多少（时间常数乘 1 − 它）。 */
    static final float STAB_ATTACK_CUT = 0.6f;
    /** 猛踩时转速起得快多少（时间常数乘 1 − 它）。 */
    static final float STAB_RISE_CUT = 0.4f;
    /** 猛踩时连续走法的转速多冲多少（rpm，低速时满额，越快越少）。 */
    static final float STAB_FLARE_RPM = 1200f;
    /** 模拟换挡时冲多少（按 {@link #STAB_FLARE_RPM} 的几成，靠降挡出效果）。 */
    static final float SHIFT_FLARE_SHARE = 0.25f;
    static final float LIFT_FROM = 150f;
    static final float LIFT_FULL = 600f;
    static final float LIFT_DECAY_SEC = 0.8f;
    /** 急松油门的突突只在 D 挡、这个车速以上（km/h）。 */
    static final float OVERRUN_MIN_KMH = 15f;

    // ---- 减速
    static final float DECEL_FULL = 6f;
    static final float DECEL_SMOOTH_SEC = 0.2f;
    /** 连续走法：重刹、回收时转速往上抬多少（rpm，减速度满额时），像发动机制动。 */
    static final float DECEL_HOLD_RPM = 800f;

    // ---- 电平（dB，相对满负荷；声浪自己的音量另加）
    static final float LEVEL_FLOOR_DB = -19f;
    static final float LEVEL_LOAD_DB = 19f;
    static final float LEVEL_LOAD_CURVE = 0.6f;
    static final float LEVEL_SPEED_DB = 4f;
    static final float LEVEL_SURGE_DB = 2f;
    static final float LEVEL_STAB_DB = 2f;
    /**
     * 车速让总音量稍大一点（dB，高速时接近它，按 {@link #speedCurve}）。项目所有者 2026-10-21 实车：响度差要压窄，高速、大油门还是稍响一点。
     * 加在总音量上（{@link #outputDb}），不进电平 —— 电平各风格会压得很窄，这一点不能跟着没了。
     * 倒挡不另外降音量：车机自己在倒挡压低音量（项目所有者 2026-10-21）。
     */
    static final float SPEED_GAIN_DB = 2.5f;
    // ---- 音色
    static final float BRIGHT_STAB_DB = 3f;
    static final float BRIGHT_SURGE_DB = 2f;
    static final float INTAKE_STAB_DB = 6f;

    // ---- 巡航收敛：油门稳了、负荷不大、车在走，慢慢收一点；停着的怠速不收
    static final float CRUISE_AFTER_SEC = 4f;
    static final float CRUISE_MAX_DB = 6f;
    static final float CRUISE_SEC = 3f;
    static final float CRUISE_BACK_SEC = 0.3f;
    static final float CRUISE_MOVE_PCT = 10f;
    static final float CRUISE_LOAD = 0.4f;
    static final float CRUISE_MIN_KMH = 10f;
    static final float THROTTLE_REF_SEC = 0.5f;

    // ---- 挡位
    static final float FREE_REV_MAX_RPM = 4500f;
    static final float REVERSE_MAX_RPM = 4500f;
    static final float REVERSE_WHINE_HZ_PER_KMH = 55f;
    static final float REVERSE_WHINE_FULL_KMH = 12f;
    static final float SHIFT_RESET_KMH = 3f;
    /** 换挡时长 = 换挡时间常数 × 它。 */
    static final float SHIFT_SETTLE = 3f;
    /** 降挡时补油冲多少（rpm），以及那一下的负荷。 */
    static final float DOWNSHIFT_BLIP_RPM = 400f;
    static final float DOWNSHIFT_BLIP_LOAD = 0.4f;
    static final float BLIP_DECAY_SEC = 0.3f;
    static final float BLIP_LOAD_DECAY_SEC = 0.15f;
    /** 猛踩、重刹（回收）时降挡点往上调多少（按升挡点的比例）。 */
    static final float STAB_DOWNSHIFT_RAISE = 0.25f;
    static final float DECEL_DOWNSHIFT_RAISE = 0.2f;
    /** 降挡后转速离升挡点至少留这么多（比例），不来回跳挡。 */
    static final float SHIFT_GUARD = 0.95f;
    /**
     * 换挡看的负荷记多久（秒）：取最近的负荷慢慢回落，松油门不立刻升挡（运动模式保持挡位），
     * 猛踩、接入降下来的挡也保持一会儿。
     */
    static final float SHIFT_LOAD_HOLD_SEC = 3f;
    /** 急踩到这个程度（0–1）直接降到该在的挡，不一挡一挡地降。 */
    static final float KICKDOWN_STAB = 0.5f;
    /** 一脚到底降到哪一挡：转速在升挡点的这么多以下的最低一挡，留出往上拉的余地（不是一降就到红线、马上又升）。 */
    static final float KICKDOWN_ROOM = 0.8f;
    /** 油门踩到这里（%，地板附近）也算一脚到底：像自动挡的强制降挡开关，踩得再慢也直接降到该在的挡。 */
    static final float KICKDOWN_PEDAL_PCT = 90f;
    /** 减速度超过这个（0–1）时不升挡：刹车、回收减速时运动模式不升挡。 */
    static final float NO_UPSHIFT_DECEL = 0.1f;

    // ---- 伪挡位（连续走法，油门接近踩到底）
    static final float PSEUDO_ON_PCT = 80f;
    static final float PSEUDO_OFF_PCT = 55f;

    // ---- 升挡：断扭、转速停一下再按 S 形落下、动力回来时「吼」一下（一共多久看变速箱，{@link Gearbox#upshiftSec}）
    /** 升挡里转速先停住的那一段（占整个升挡的份额）。 */
    static final float UPSHIFT_HOLD = 0.2f;
    /** 断扭到这里（份额）开始淡出，到 {@link #CUT_FADE} 没了。 */
    static final float CUT_END = 0.6f;
    static final float CUT_FADE = 0.85f;
    /** 负荷到这里断扭算满的；小油门升挡几乎听不出来。 */
    static final float CUT_LOAD_FULL = 0.8f;
    /** 动力回来时亮多少（dB）、多快淡掉。 */
    static final float BARK_DB = 3f;
    static final float BARK_SEC = 0.08f;

    // ---- 启动
    static final float START_CRANK_SEC = 0.6f;
    static final float START_CATCH_SEC = 0.3f;
    static final float START_SETTLE_SEC = 1.3f;
    static final float START_CRANK_RPM = 250f;
    static final float START_CRANK_LOAD = 0.12f;
    /**
     * 点着后冲到多少（rpm）：项目所有者 2026-10-22 要启动的体感再强 20 % 左右，2000 → 2400；2026-10-23 嫌美式 V8 启动时转速拉得
     * 太高，恢复到 2000（冲高时多出来的音量、亮度、进气声照旧）。
     */
    static final float START_FLARE_RPM = 2000f;
    static final float START_CRANK_TAU = 0.15f;
    static final float START_CATCH_TAU = 0.08f;
    static final float START_SETTLE_TAU = 0.35f;
    static final float STARTER_SEC = 0.04f;
    /** 车速到这个（km/h）就不算停着：开始工作时走接入、停下来时按行驶中停发动机，启动途中车动了就结束启动。 */
    static final float START_LEAVE_KMH = 2f;
    /** 启动的回落段里油门踩过这个负荷，交还正常走法。 */
    static final float START_TAKEOVER_LOAD = 0.1f;
    /**
     * 点着那一下多冲一点（项目所有者 2026-10-22：体感再强 20 % 左右，响度约 +2.5 dB）：总音量多加的（dB，不随电平压窄）、
     * 高阶次多亮的、进气多响的（dB）；停着启动时冲高那一段一直满着，之后按 {@link #SURGE_SEC} 淡掉。
     */
    static final float SURGE_GAIN_DB = 2.5f;
    static final float SURGE_BRIGHT_DB = 3f;
    static final float SURGE_INTAKE_DB = 6f;
    static final float SURGE_SEC = 0.3f;

    // ---- 行驶中接入：像混动车行驶中起动发动机
    /** 电机把发动机带到该有的转速：多久以后点着（秒）、带的时间常数（秒）；这一段不点火，只有压缩的「噗」。 */
    static final float ROLL_SPIN_SEC = 0.22f;
    static final float ROLL_SPIN_TAU = 0.06f;
    /** 点着时补油冲多少（rpm）、那一下的负荷、多冲的（{@link #surge}，停着启动是 1）；踩着刹车、在回收时不冲，轻一些。 */
    static final float ROLL_FLARE_RPM = 500f;
    static final float ROLL_CATCH_LOAD = 0.6f;
    static final float ROLL_SURGE = 0.6f;
    static final float ROLL_EASING_LOAD = 0.15f;
    static final float ROLL_EASING_SURGE = 0.25f;

    // ---- 熄火（切出运动模式、下电）
    /**
     * 断油断火后转速每秒落 {@code STOP_DECEL + rpm / STOP_TAU}（rpm/s：摩擦、泵气，转得越快拖得越狠）。车停着照真车熄火，
     * 怠速约 0.7 秒停住；车在走（混动车行驶中停发动机）电机帮着拖停，快一些。
     */
    static final float STOP_DECEL = 800f;
    static final float STOP_TAU = 0.9f;
    static final float STOP_MOVING_DECEL = 2500f;
    static final float STOP_MOVING_TAU = 0.6f;
    /** 最后几圈：每个缸压缩时曲轴慢一下、膨胀时推一下（V8 一圈压缩四次），转速起伏最多 {@link #STOP_LUMP} 倍，这个转速（rpm）以下才有。 */
    static final float STOP_LUMP = 0.35f;
    static final float STOP_LUMP_RPM = 300f;
    static final float COMPRESSIONS_PER_REV = 4f;
    /** 停住以后再留多久（秒）：最后那一下晃动落完，再放掉喇叭。 */
    static final float STOP_TAIL_SEC = 0.35f;
    /** 阀门全开（{@link VoiceParams#toneHz} 的默认）。 */
    static final float TONE_OPEN_HZ = 20000f;

    // ---- 收油（断油、发动机被车拖着转）：给合成器
    /** 该有的负荷（{@link #loadTarget}）低于这个算收油。 */
    static final float OVERRUN_LOAD = 0.08f;
    /**
     * 转速高出怠速这么多（rpm）开始算，再高 {@link #OVERRUN_RPM_SPAN} 算满：真车滑行断油大约 1200–1500 rpm 以上就全断了。
     * 挡位照车主偏好的那台换高了以后（2026-10-22），滑行时转速常在 1500 上下，原来 1850 才算满就嫌晚。
     */
    static final float OVERRUN_FROM_RPM = 150f;
    static final float OVERRUN_RPM_SPAN = 500f;
    static final float OVERRUN_SEC = 0.15f;

    /** 一块最多按多长推进（秒）：线程被耽搁了也不一步跳太远。 */
    static final float MAX_DT = 0.1f;

    private final SoundProfile p;
    /** 模拟换挡、伪挡位、倒挡用的变速箱（开发者选项「变速箱」，可以随时换，{@link #setGearbox}）。 */
    private Gearbox gearbox;
    /** 启动时带不带起动机那一段。 */
    private final boolean crank;
    private Phase phase = Phase.RUNNING;
    private float phaseSec;
    private float rpm;
    private float load;
    private float stabEnv;
    private float liftEnv;
    private float decel;
    private float effortEnv;
    private float blip;
    private float blipLoad;
    private float cruiseCutDb;
    private float steadySec;
    private float throttleRef = Float.NaN;
    private int gearIndex;
    private boolean gearPicked;
    /** 换挡看的负荷：最近的负荷慢慢回落（{@link #SHIFT_LOAD_HOLD_SEC}）。 */
    private float shiftLoad;
    /** 此刻算一脚到底（急踩，或者油门到了 {@link #KICKDOWN_PEDAL_PCT}）：模拟换挡直接降到该在的挡。 */
    private boolean kickdown;
    private float shiftLeftSec;
    /** 连续走法里此刻按伪挡位走；这台走不走伪挡位。 */
    private boolean pseudo;
    private final boolean pseudoAllowed;
    /** 此刻按挡位走（模拟换挡，或伪挡位）。 */
    private boolean geared;
    private float upLeftSec;
    private float upFromRpm;
    private float cut;
    private float bark;
    private boolean barkPending;
    private int shiftSerial;
    private boolean shiftUp = true;
    private float starter;
    /** 点着火（0 / 1）：起动机带着转、行驶中电机带着转还没点着、熄火时是 0。 */
    private float fire = 1f;
    /** 点着那一下多冲的（0–1，{@link #SURGE_GAIN_DB} 那几样）。 */
    private float surge;
    /** 点着了几次（只增不减）：合成器看它变了就放点着的那一下。 */
    private int catchSerial;
    // ---- 熄火
    /** 车在走时停的（电机帮着拖停）。 */
    private boolean stopMoving;
    /** 不带最后几圈起伏的转速、起伏的相位（周）、停住的时刻（{@link #phaseSec}）。 */
    private float stopRpm;
    private float lumpPhase;
    private float stopAtSec;
    private boolean done;
    private float overrun;
    private float speedKmh;
    private Gear gear = Gear.UNKNOWN;

    VirtualEngine(SoundProfile profile) {
        this(profile, true);
    }

    VirtualEngine(SoundProfile profile, boolean crank) {
        this(profile, Gearbox.AUTO8, crank);
    }

    VirtualEngine(SoundProfile profile, Gearbox gearbox, boolean crank) {
        this(profile, gearbox, crank, true);
    }

    /**
     * @param gearbox     变速箱（模拟换挡、伪挡位、倒挡）
     * @param crank       启动时带起动机那一段（「试制 V8」）；不带就直接从点着开始
     * @param pseudoGears 连续走法里油门接近踩到底时按伪挡位走（两种 V8）；不走的话就一路平滑上去（超音速：参考车关掉 e-Shift
     *                    时就是这样，项目所有者 2026-10-23）
     */
    VirtualEngine(SoundProfile profile, Gearbox gearbox, boolean crank, boolean pseudoGears) {
        this.p = profile;
        this.gearbox = gearbox;
        this.crank = crank;
        this.pseudoAllowed = pseudoGears;
        this.rpm = profile.idleRpm;
    }

    /** 换变速箱（开发者选项改了，随时）：下一块按此刻的车速、负荷重新选挡，转速照常平滑地过去，不算一次换挡。 */
    void setGearbox(Gearbox g) {
        if (g == gearbox) {
            return;
        }
        gearbox = g;
        gearPicked = false;
        gearIndex = Math.min(gearIndex, g.rpmPerKmh.length - 1);
        shiftLeftSec = 0f;
        upLeftSec = 0f;
        cut = 0f;
    }

    /** 开始工作：车停着放启动声，车在走就像混动车那样起动发动机。 */
    void ignite(Input in) {
        stabEnv = 0f;
        liftEnv = 0f;
        decel = 0f;
        effortEnv = 0f;
        blip = 0f;
        blipLoad = 0f;
        cruiseCutDb = 0f;
        steadySec = 0f;
        throttleRef = Float.NaN;
        shiftLeftSec = 0f;
        shiftLoad = 0f;
        pseudo = false;
        geared = false;
        upLeftSec = 0f;
        cut = 0f;
        bark = 0f;
        phaseSec = 0f;
        starter = 0f;
        overrun = 0f;
        surge = 0f;
        done = false;
        float v = speedOf(in);
        speedKmh = v;
        gear = in.gear;
        gearIndex = 0;
        gearPicked = true;
        rpm = 0f;
        load = 0f;
        fire = 0f;
        if (v < START_LEAVE_KMH) {
            phase = Phase.STARTING;
            if (!crank) {
                // 不要起动机：直接从点着开始
                phaseSec = START_CRANK_SEC;
                rpm = START_CRANK_RPM;
                fire = 1f;
                surge = 1f;
                catchSerial++;
            }
            return;
        }
        // 车在走：发动机从停着被电机带起来，点着以后接上（stepJoin）
        phase = Phase.JOINING;
        shiftLoad = loadTarget(in);
        boolean easing = in.braking || in.flow == Flow.REGEN;
        if (in.gear == Gear.D && in.mode == RpmMode.SHIFTS) {
            gearIndex = pickGear(p, gearbox, v, shiftLoad);
            if (!easing && gearIndex > 0
                    && v * gearbox.rpmPerKmh[gearIndex - 1] < upshiftRpm(p, 1f) * SHIFT_GUARD) {
                // 顺势降一挡：表示运动模式接上了；换挡看的负荷记成满的，这一挡保持一会儿
                gearIndex--;
                shiftLoad = 1f;
            }
        }
    }

    /**
     * 停下来（切出运动模式、下电）：断油断火，转速自己落到 0，{@link #done} 以后才放掉喇叭。车在走时电机帮着拖停，快一些。
     * 已经在熄火就不重来。
     */
    void shutdown(Input in) {
        if (phase == Phase.STOPPING) {
            return;
        }
        phase = Phase.STOPPING;
        phaseSec = 0f;
        stopMoving = speedOf(in) >= START_LEAVE_KMH;
        stopRpm = rpm;
        stopAtSec = 0f;
        lumpPhase = 0f;
        done = false;
        fire = 0f;
        starter = 0f;
        surge = 0f;
        cut = 0f;
        upLeftSec = 0f;
        shiftLeftSec = 0f;
        barkPending = false;
        overrun = 0f;
    }

    /** 推进 {@code dt} 秒。 */
    void step(float dt, Input in) {
        if (!(dt > 0f)) {
            return;
        }
        if (dt > MAX_DT) {
            dt = MAX_DT;
        }
        float v = speedOf(in);
        speedKmh = v;
        gear = in.gear;
        phaseSec += dt;

        // 派生量：踩 / 松油门的快慢、减速度、车真正出的力
        float stab = clamp01((in.pedalRate - STAB_FROM) / (STAB_FULL - STAB_FROM));
        stabEnv = Math.max(stab, stabEnv * decay(dt, STAB_DECAY_SEC));
        boolean overrunSpeed = in.gear == Gear.D && v >= OVERRUN_MIN_KMH;
        float lift = overrunSpeed ? clamp01((-in.pedalRate - LIFT_FROM) / (LIFT_FULL - LIFT_FROM)) : 0f;
        liftEnv = Math.max(lift, liftEnv * decay(dt, LIFT_DECAY_SEC));
        decel += (clamp01(-in.accel / DECEL_FULL) - decel) * follow(dt, DECEL_SMOOTH_SEC);
        effortEnv += (effort(in) - effortEnv) * follow(dt, EFFORT_SMOOTH_SEC);
        blip *= decay(dt, BLIP_DECAY_SEC);
        blipLoad *= decay(dt, BLIP_LOAD_DECAY_SEC);
        bark *= decay(dt, BARK_SEC);
        surge *= decay(dt, SURGE_SEC);

        if (phase == Phase.STOPPING) {
            stepStop(dt);
            return;
        }
        if (phase == Phase.STARTING) {
            stepStart(dt, v, in);
            return;
        }
        if (phase == Phase.JOINING) {
            stepJoin(dt, v, in);
            return;
        }

        // 负荷：急踩时起得快，踩刹车时落得快
        float target = loadTarget(in);
        float tau = target > load ? p.loadAttackSec * (1f - STAB_ATTACK_CUT * stabEnv)
                : (in.braking ? p.brakeReleaseSec : p.loadReleaseSec);
        load += (target - load) * follow(dt, tau);
        shiftLoad = Math.max(load, shiftLoad * decay(dt, SHIFT_LOAD_HOLD_SEC));
        kickdown = stabEnv >= KICKDOWN_STAB || in.throttlePct >= KICKDOWN_PEDAL_PCT;
        if (kickdown) {
            // 一脚到底（急踩、或者踩到地板附近）：换挡按踩下去的量算，不等负荷爬上来
            shiftLoad = Math.max(shiftLoad, target);
        }
        updateCruise(dt, in.throttlePct, v);

        // 转速
        updatePseudo(in);
        geared = in.gear == Gear.D && (in.mode == RpmMode.SHIFTS || pseudo);
        boolean shifting = false;
        if (geared) {
            updateGear(dt, v, in);
            shifting = shiftLeftSec > 0f;
        } else {
            gearIndex = 0;
            gearPicked = false;
            shiftLeftSec = 0f;
            upLeftSec = 0f;
        }
        float goal = rpmTarget(v, in);
        if (upLeftSec > 0f) {
            stepUpshift(dt, goal, in.gear);
        } else {
            cut = 0f;
            moveRpm(dt, goal, shifting, in.gear);
        }
        // 看这一刻该有的负荷（不等平滑）：一松油门就断油
        float overrunTarget = target < OVERRUN_LOAD
                ? clamp01((rpm - p.idleRpm - OVERRUN_FROM_RPM) / OVERRUN_RPM_SPAN) : 0f;
        overrun += (overrunTarget - overrun) * follow(dt, OVERRUN_SEC);
    }

    /** 连续走法：油门接近踩到底时按伪挡位走（有回差：踩到 {@link #PSEUDO_ON_PCT}、或者一脚到底时进，回到 {@link #PSEUDO_OFF_PCT} 以下出）。 */
    private void updatePseudo(Input in) {
        if (!pseudoAllowed || in.gear != Gear.D || in.mode == RpmMode.SHIFTS) {
            pseudo = false;
            return;
        }
        float t = in.throttlePct > 0f ? in.throttlePct : 0f;
        boolean easing = in.braking || in.flow == Flow.REGEN;
        if (!pseudo) {
            if (!easing && (t >= PSEUDO_ON_PCT || (kickdown && t >= PSEUDO_OFF_PCT))) {
                pseudo = true;
                gearPicked = false;
            }
        } else if (easing || t < PSEUDO_OFF_PCT) {
            pseudo = false;
        }
    }

    /** 升挡这一段：断扭，转速先停住再按 S 形落到新挡位；断扭淡出时「吼」一下。 */
    private void stepUpshift(float dt, float target, Gear g) {
        upLeftSec = Math.max(0f, upLeftSec - dt);
        float f = 1f - upLeftSec / gearbox.upshiftSec;
        float u = smooth(clamp01((f - UPSHIFT_HOLD) / (1f - UPSHIFT_HOLD)));
        target = clamp(target, p.idleRpm, capFor(g));
        rpm = upFromRpm + (target - upFromRpm) * u;
        float c = f < CUT_END ? 1f : 1f - smooth(clamp01((f - CUT_END) / (CUT_FADE - CUT_END)));
        float strength = clamp01(load / CUT_LOAD_FULL);
        if (f >= CUT_END && barkPending) {
            barkPending = false;
            bark = Math.max(bark, strength);
        }
        cut = upLeftSec > 0f ? c * strength : 0f;
    }

    /** 启动：转动 → 点着冲高（{@link #surge} 一直满着）→ 回落到怠速。回落段里踩了油门、或者车动了，交还正常走法。 */
    private void stepStart(float dt, float v, Input in) {
        float crankEnd = START_CRANK_SEC;
        float catchEnd = crankEnd + START_CATCH_SEC;
        float end = catchEnd + START_SETTLE_SEC;
        float rpmTarget;
        float loadTarget;
        float starterTarget;
        float tau;
        if (phaseSec < crankEnd) {
            rpmTarget = START_CRANK_RPM;
            loadTarget = START_CRANK_LOAD;
            starterTarget = 1f;
            tau = START_CRANK_TAU;
        } else if (phaseSec < catchEnd) {
            rpmTarget = START_FLARE_RPM;
            loadTarget = 1f;
            starterTarget = 0f;
            tau = START_CATCH_TAU;
            if (fire < 0.5f) {
                catchSerial++;
            }
            fire = 1f;
            surge = 1f;
        } else {
            rpmTarget = p.idleRpm;
            loadTarget = 0f;
            starterTarget = 0f;
            tau = START_SETTLE_TAU;
        }
        starter += (starterTarget - starter) * follow(dt, STARTER_SEC);
        load += (loadTarget - load) * follow(dt, loadTarget > load ? p.loadAttackSec : p.loadReleaseSec);
        rpm += (rpmTarget - rpm) * follow(dt, tau);
        rpm = clamp(rpm, 0f, p.maxRpm);
        boolean pressed = demand(in.throttlePct) > START_TAKEOVER_LOAD;
        boolean leaving = v >= START_LEAVE_KMH;
        if (phaseSec >= end || (phaseSec >= catchEnd && (pressed || leaving))) {
            phase = Phase.RUNNING;
            starter = 0f;
            fire = 1f;
            gearPicked = false;
            if (rpm < p.idleRpm) {
                rpm = p.idleRpm;
            }
        }
    }

    /** 行驶中起动：电机把发动机带到此刻油门要的转速（不点火），{@link #ROLL_SPIN_SEC} 后点着、接上，补油冲一下。 */
    private void stepJoin(float dt, float v, Input in) {
        float want = loadTarget(in);
        load += (want - load) * follow(dt, want > load ? p.loadAttackSec : p.loadReleaseSec);
        float target = clamp(rpmTarget(v, in), p.idleRpm, capFor(in.gear));
        rpm += (target - rpm) * follow(dt, ROLL_SPIN_TAU);
        if (phaseSec < ROLL_SPIN_SEC) {
            return;
        }
        phase = Phase.RUNNING;
        fire = 1f;
        catchSerial++;
        boolean easing = in.braking || in.flow == Flow.REGEN;
        surge = Math.max(surge, easing ? ROLL_EASING_SURGE : ROLL_SURGE);
        blipLoad = Math.max(blipLoad, easing ? ROLL_EASING_LOAD : ROLL_CATCH_LOAD);
        if (!easing) {
            blip = Math.max(blip, ROLL_FLARE_RPM);
        }
    }

    /** 熄火：断油断火，转速按摩擦、泵气落到 0，最后几圈一顿一顿；停住后再留 {@link #STOP_TAIL_SEC}。 */
    private void stepStop(float dt) {
        load += (0f - load) * follow(dt, p.loadReleaseSec);
        if (stopRpm > 0f) {
            float decel = stopMoving ? STOP_MOVING_DECEL : STOP_DECEL;
            float tau = stopMoving ? STOP_MOVING_TAU : STOP_TAU;
            stopRpm = Math.max(0f, stopRpm - (decel + stopRpm / tau) * dt);
            lumpPhase += COMPRESSIONS_PER_REV * stopRpm / 60f * dt;
            lumpPhase -= (float) Math.floor(lumpPhase);
            float lump = STOP_LUMP * clamp01(1f - stopRpm / STOP_LUMP_RPM);
            rpm = stopRpm * (1f + lump * (float) Math.sin(2.0 * Math.PI * lumpPhase));
            if (stopRpm <= 0f) {
                rpm = 0f;
                stopAtSec = phaseSec;
            }
        } else if (phaseSec - stopAtSec >= STOP_TAIL_SEC) {
            rpm = 0f;
            done = true;
        }
    }

    /**
     * 模拟换挡：升挡点随换挡负荷（最近的负荷慢慢回落），降挡点随急踩、重刹抬高；刹车、回收减速时不升挡；
     * 猛踩直接降到该在的挡（一脚到底可以连降几挡），重刹一挡一挡地降；降挡带补油，升挡音量掉一下。
     */
    private void updateGear(float dt, float v, Input in) {
        if (shiftLeftSec > 0f) {
            shiftLeftSec = Math.max(0f, shiftLeftSec - dt);
        }
        if (v < SHIFT_RESET_KMH) {
            gearIndex = 0;
            gearPicked = true;
            return;
        }
        if (!gearPicked) {
            // 刚从别的挡、别的走法、别的变速箱过来：按此刻的车速和负荷直接选，不一挡一挡地升
            gearIndex = pickGear(p, gearbox, v, shiftLoad);
            gearPicked = true;
            return;
        }
        if (shiftLeftSec > 0f) {
            return;
        }
        int last = gearbox.rpmPerKmh.length - 1;
        boolean easing = in.braking || in.flow == Flow.REGEN;
        if (kickdown && !easing) {
            // 一脚到底：直接降到此刻车速下该在的挡（留出往上拉的余地），可以连降几挡
            int to = pickGear(p, gearbox, v, shiftLoad, KICKDOWN_ROOM);
            if (to < gearIndex) {
                downshiftTo(to, false);
                return;
            }
        }
        // 记住的负荷只管「先别升挡」；降挡平时看此刻的负荷，刹车 / 回收（补油降挡）才按记住的量
        float up = upshiftRpm(p, shiftLoad);
        float downRatio = p.shiftDownRatio + STAB_DOWNSHIFT_RAISE * stabEnv
                + (easing ? DECEL_DOWNSHIFT_RAISE * decel : 0f);
        float down = upshiftRpm(p, easing ? shiftLoad : load) * downRatio;
        float inGear = v * gearbox.rpmPerKmh[gearIndex];
        boolean slowing = easing && decel > NO_UPSHIFT_DECEL;
        if (gearIndex < last && inGear >= up && !slowing) {
            gearIndex++;
            shiftLeftSec = gearbox.upshiftSec;
            upLeftSec = gearbox.upshiftSec;
            upFromRpm = rpm;
            barkPending = true;
            shiftSerial++;
            shiftUp = true;
        } else if (gearIndex > 0 && inGear < down
                && v * gearbox.rpmPerKmh[gearIndex - 1] < up * SHIFT_GUARD) {
            downshiftTo(gearIndex - 1, easing);
        }
    }

    /** 降到 {@code to} 挡：转速跟着冲上去，带一点补油（刹车 / 回收时那一下更响）。 */
    private void downshiftTo(int to, boolean easing) {
        gearIndex = to;
        shiftLeftSec = p.shiftSec * SHIFT_SETTLE;
        upLeftSec = 0f;
        shiftSerial++;
        shiftUp = false;
        blip = Math.max(blip, DOWNSHIFT_BLIP_RPM);
        blipLoad = Math.max(blipLoad, easing ? DOWNSHIFT_BLIP_LOAD : DOWNSHIFT_BLIP_LOAD * 0.5f);
    }

    /** 此刻该去的转速（不改状态）。 */
    private float rpmTarget(float v, Input in) {
        float base;
        switch (in.gear) {
            case R:
                base = Math.min(gearedRpm(launchRpm(load), v * gearbox.reverseRpmPerKmh), REVERSE_MAX_RPM);
                break;
            case D:
                if (in.mode == RpmMode.SHIFTS || pseudo) {
                    base = gearedRpm(launchRpm(load), v * gearbox.rpmPerKmh[gearIndex])
                            + STAB_FLARE_RPM * SHIFT_FLARE_SHARE * stabEnv * (1f - speedCurve(p, v));
                } else {
                    boolean easing = in.braking || in.flow == Flow.REGEN;
                    base = continuousRpm(p, v, load)
                            + STAB_FLARE_RPM * stabEnv * (1f - 0.5f * speedCurve(p, v))
                            + (easing ? DECEL_HOLD_RPM * decel * speedCurve(p, v) : 0f);
                }
                break;
            default:
                // P、N、读不到：空挡，转速只跟油门
                base = p.idleRpm + (FREE_REV_MAX_RPM - p.idleRpm) * load;
                break;
        }
        return base + blip;
    }

    private void moveRpm(float dt, float target, boolean shifting, Gear g) {
        float cap = capFor(g);
        target = clamp(target, p.idleRpm, cap);
        float riseTau = p.rpmRiseSec * (1f - STAB_RISE_CUT * stabEnv);
        float tau = shifting ? p.shiftSec : (target > rpm ? riseTau : p.rpmFallSec);
        float next = rpm + (target - rpm) * follow(dt, tau);
        if (!shifting) {
            float up = p.rpmRisePerSec * (1f + stabEnv) * dt;
            float down = p.rpmFallPerSec * dt;
            next = clamp(next, rpm - down, rpm + up);
        }
        rpm = clamp(next, p.idleRpm, cap);
    }

    private float capFor(Gear g) {
        if (g == Gear.D) {
            return p.maxRpm;
        }
        return g == Gear.R ? REVERSE_MAX_RPM : FREE_REV_MAX_RPM;
    }

    private float launchRpm(float l) {
        return p.idleRpm + (p.maxRpm - p.idleRpm) * p.pedalKick * l;
    }

    /** 油门稳了、负荷不大、车在走：慢慢收最多 {@link #CRUISE_MAX_DB}；油门一动、或者停下来，马上回来。 */
    private void updateCruise(float dt, float throttlePct, float v) {
        float t = throttlePct > 0f ? throttlePct : 0f;
        if (Float.isNaN(throttleRef)) {
            throttleRef = t;
        }
        throttleRef += (t - throttleRef) * follow(dt, THROTTLE_REF_SEC);
        if (Math.abs(t - throttleRef) > CRUISE_MOVE_PCT || v < CRUISE_MIN_KMH) {
            steadySec = 0f;
        } else {
            steadySec += dt;
        }
        boolean cruising = steadySec >= CRUISE_AFTER_SEC && load < CRUISE_LOAD;
        float targetCut = cruising ? CRUISE_MAX_DB : 0f;
        cruiseCutDb += (targetCut - cruiseCutDb) * follow(dt, cruising ? CRUISE_SEC : CRUISE_BACK_SEC);
    }

    // ================================================================= 纯函数

    /** 车速曲线 S(v)：0 → 1，越快越平。 */
    static float speedCurve(SoundProfile p, float speedKmh) {
        return speedKmh > 0f ? 1f - (float) Math.exp(-speedKmh / p.speedKneeKmh) : 0f;
    }

    /** 油门开度 → 驾驶员要的负荷（0–1）。读不到当没踩。 */
    static float demand(float throttlePct) {
        if (!(throttlePct > THROTTLE_DEADBAND)) {
            return 0f;
        }
        float x = (throttlePct - THROTTLE_DEADBAND) / (THROTTLE_FULL - THROTTLE_DEADBAND);
        return (float) Math.pow(clamp01(x), LOAD_CURVE);
    }

    /** 加速度 → 车真正出的力（0–1）：只在 D / R 挡、电机在出力时算（读不到动力流时，踩着油门才算）；下坡滑行加速不算。 */
    static float effort(Input in) {
        if (in.braking || in.flow == Flow.REGEN || (in.gear != Gear.D && in.gear != Gear.R)) {
            return 0f;
        }
        boolean driving = in.flow == Flow.DRIVE || (in.flow == Flow.UNKNOWN && demand(in.throttlePct) > 0f);
        return driving ? clamp01(in.accel / ACCEL_FULL) : 0f;
    }

    /** 该有的负荷：踩刹车、回收时 0；空挡只看油门；否则油门和加速度取大的。 */
    static float loadTarget(Input in) {
        if (in.braking || in.flow == Flow.REGEN) {
            return 0f;
        }
        float d = demand(in.throttlePct);
        if (in.gear != Gear.D && in.gear != Gear.R) {
            return d;
        }
        return Math.max(d, ACCEL_LOAD_WEIGHT * effort(in));
    }

    /**
     * 连续走法的转速：一踩油门先跳起 κ × 负荷，剩下的跨度里车速给一部分、油门给一部分（docs/local/drive-sound-plan.md §2.4）。
     * 地板油也随车速慢慢爬，150 km/h 左右才到上限 —— 不会一路顶在红线上。
     */
    static float continuousRpm(SoundProfile p, float speedKmh, float load) {
        float s = speedCurve(p, speedKmh);
        float kick = p.pedalKick * load;
        float x = kick + (1f - kick) * s * (p.speedShare + (1f - p.speedShare) * load);
        return p.idleRpm + (p.maxRpm - p.idleRpm) * clamp01(x);
    }

    /**
     * 挂着挡时的转速：起步打滑的转速（{@code launch}，跟着油门）和挡位转速（车速 × 齿比）平滑地合在一起（三次方的软最大值）——
     * 低速时打滑的转速撑着，车速上来后跟着挡位走，中间不出平台、也不往下掉。
     */
    static float gearedRpm(float launch, float inGear) {
        double a = Math.max(0f, launch);
        double b = Math.max(0f, inGear);
        return (float) Math.cbrt(a * a * a + b * b * b);
    }

    /**
     * 升挡点：负荷越大越晚升，按平方 —— 轻踩、中等油门早升，不在低挡拖着；只有重踩、地板油才拉到快到上限
     * （项目所有者 2026-10-22）。离上限留 300 rpm。
     */
    static float upshiftRpm(SoundProfile p, float load) {
        float l = clamp01(load);
        return Math.min(p.shiftUpBaseRpm + p.shiftUpLoadRpm * l * l, p.maxRpm - 300f);
    }

    /** 按车速和负荷直接选挡：转速还在升挡点以下的最低一挡。 */
    static int pickGear(SoundProfile p, Gearbox gearbox, float speedKmh, float load) {
        return pickGear(p, gearbox, speedKmh, load, SHIFT_GUARD);
    }

    /** 同上，转速在升挡点的 {@code room} 倍以下（一脚到底时留出往上拉的余地，{@link #KICKDOWN_ROOM}）。 */
    static int pickGear(SoundProfile p, Gearbox gearbox, float speedKmh, float load, float room) {
        float up = upshiftRpm(p, load) * room;
        for (int g = 0; g < gearbox.rpmPerKmh.length; g++) {
            if (speedKmh * gearbox.rpmPerKmh[g] < up) {
                return g;
            }
        }
        return gearbox.rpmPerKmh.length - 1;
    }

    static float follow(float dt, float tau) {
        return tau > 0f ? 1f - (float) Math.exp(-dt / tau) : 1f;
    }

    static float decay(float dt, float tau) {
        return tau > 0f ? (float) Math.exp(-dt / tau) : 0f;
    }

    static float clamp01(float x) {
        return x < 0f ? 0f : (x > 1f ? 1f : x);
    }

    /** 0–1 的 S 形（smoothstep）。 */
    static float smooth(float x) {
        return x * x * (3f - 2f * x);
    }

    static float clamp(float x, float lo, float hi) {
        return x < lo ? lo : (x > hi ? hi : x);
    }

    private static float speedOf(Input in) {
        return in.speedKmh > 0f ? in.speedKmh : 0f;
    }

    // ================================================================= 给合成器、诊断

    Phase phase() {
        return phase;
    }

    float rpm() {
        return rpm;
    }

    float load() {
        return load;
    }

    /**
     * 合成器用的负荷：降挡补油那一下也算进去。行驶中起动、电机带着转的那一段是 0（节气门关着，点着那一下才是一个清楚的「轰」）；
     * 油门要的负荷只拿来定带到多少转。
     */
    float voiceLoad() {
        return phase == Phase.JOINING ? 0f : Math.max(load, blipLoad);
    }

    /**
     * 电平（dB，相对满负荷）：负荷、车速、真加速、急踩、巡航收敛都在里面；车速的那一点、声浪自己的音量另加（{@link #outputDb}）。
     * 各风格按自己的范围压窄它；升挡断扭的那一下由合成器按 {@link #cut} 自己掉（断扭要在收窄之后再掉）。
     */
    float levelDb() {
        float s = speedCurve(p, speedKmh);
        float l = voiceLoad();
        return LEVEL_FLOOR_DB + LEVEL_LOAD_DB * (float) Math.pow(l, LEVEL_LOAD_CURVE) + LEVEL_SPEED_DB * s
                + LEVEL_SURGE_DB * effortEnv + LEVEL_STAB_DB * stabEnv - cruiseCutDb;
    }

    /** 加在总音量上的（dB）：车速越快稍大一点（{@link #SPEED_GAIN_DB}）；点着那一下多冲一点（{@link #SURGE_GAIN_DB}）。 */
    float outputDb() {
        return SPEED_GAIN_DB * speedCurve(p, speedKmh) + SURGE_GAIN_DB * surge;
    }

    /** 起动机那一层有多响（0–1）。 */
    float starter() {
        return starter;
    }

    /** 点着火（0 / 1）：起动机、电机带着转还没点着、熄火时是 0。 */
    float fire() {
        return fire;
    }

    /** 点着了几次（只增不减）：合成器看它变了就放点着的那一下（缺火、晃一下）。 */
    int catchSerial() {
        return catchSerial;
    }

    /** 熄火放完了（转速落到 0，最后那一下晃动也落完）：可以放掉喇叭。 */
    boolean done() {
        return done;
    }

    /** 高阶次多加的 dB（急踩、猛加速、升挡后动力回来那一下、点着那一下更亮）。 */
    float brightDb() {
        return BRIGHT_STAB_DB * stabEnv + BRIGHT_SURGE_DB * effortEnv + BARK_DB * bark + SURGE_BRIGHT_DB * surge;
    }

    /** 升挡断扭（0–1，按负荷）：合成器据此减弱燃烧、电平掉一下。 */
    float cut() {
        return cut;
    }

    /** 换了几次挡（只增不减）：合成器看它变了就放换挡的声音。 */
    int shiftSerial() {
        return shiftSerial;
    }

    /** 最近一次换挡是升挡。 */
    boolean shiftUp() {
        return shiftUp;
    }

    /** 此刻按挡位走（模拟换挡，或连续走法里的伪挡位）。 */
    boolean geared() {
        return geared;
    }

    /** 连续走法里此刻按伪挡位走。 */
    boolean pseudoGears() {
        return pseudo;
    }

    /** 进气声多加的 dB（急踩时、点着那一下猛一下）。 */
    float intakeBoostDb() {
        return INTAKE_STAB_DB * stabEnv + SURGE_INTAKE_DB * surge;
    }

    /** 急松油门的突突（0–1）。 */
    float burble() {
        return liftEnv;
    }

    /**
     * 收油时发动机被车拖着转（断油）多少（0–1）：负荷几乎没有、转速在怠速之上 —— 滑行、刹车、空挡收油回落。
     * 启动、接入途中、熄火时是 0（熄火看 {@link #fire}）。
     */
    float overrun() {
        return overrun;
    }

    /** 此刻用的车速（km/h，非负）。 */
    float speedKmh() {
        return speedKmh;
    }

    /** 倒挡齿轮的啸叫频率（Hz）；不在倒挡是 0。 */
    float whineHz() {
        return gear == Gear.R ? REVERSE_WHINE_HZ_PER_KMH * speedKmh : 0f;
    }

    /** 倒挡齿轮的啸叫多响（0–1，跟着车速）；不在倒挡是 0。 */
    float whineAmp() {
        return gear == Gear.R ? clamp01(speedKmh / REVERSE_WHINE_FULL_KMH) : 0f;
    }

    /** 模拟换挡的挡位（0 = 一挡）。 */
    int gearIndex() {
        return gearIndex;
    }

    float cruiseCutDb() {
        return cruiseCutDb;
    }

    /** 合成器这一块的参数，总音量（{@link VoiceParams#gain}）除外：那个由叫的人按门控、声浪音量、{@link #outputDb} 定。 */
    void fill(VoiceParams out) {
        out.rpm = rpm;
        out.load = voiceLoad();
        out.level = EngineVoice.dbToLin(levelDb());
        out.brightDb = brightDb();
        out.intakeBoostDb = intakeBoostDb();
        out.burble = burble();
        out.starter = starter;
        out.fire = fire;
        out.catchSerial = catchSerial;
        out.whineHz = whineHz();
        out.whineAmp = whineAmp();
        out.overrun = overrun;
        out.speedKmh = speedKmh;
        out.drive = gear == Gear.D;
        out.gearIndex = geared ? gearIndex : -1;
        out.cut = cut;
        out.shiftSerial = shiftSerial;
        out.shiftUp = shiftUp;
    }
}
