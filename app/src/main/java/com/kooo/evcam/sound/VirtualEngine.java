package com.kooo.evcam.sound;

/**
 * 虚拟发动机：车速、油门（开度和踩的快慢）、加减速度、刹车、动力流、档位 → 转速和负荷（带惯性），再给出电平和几样音色参数。
 * 纯逻辑（{@code VirtualEngineTest}），声音线程上每块推进一次（{@link #step}），不分配内存。
 * 做法在 docs/local/drive-sound-plan.md §2、§9；参数在 {@link SoundProfile}。
 *
 * <h3>几样输入各管什么（项目所有者 2026-10-21：油门开度、踩油门的快慢、车速、加减速度都要用上）</h3>
 *
 * <ul>
 *   <li><b>油门开度</b> → 负荷（驾驶员要多少）：3 % 以下算没踩，85 % 算满，中间按 0.8 次方（小油门也听得出）。
 *       项目所有者 2026-10-24：功率直接按油门开度（和踩的快慢）模拟 —— 车速、加速度都是滞后的（先出了力，车速、加速度才跟上），
 *       数据传过来本来就有延迟，不拿它们算；车重、车速、阻力只是配合（{@link #cruiseLoad}）。运动模式车机的踏板更灵
 *       （{@link Input#sportPedal}、{@link #SPORT_PEDAL_EXP}）。猛加速时声音厚一点也按油门（{@link #push}）。</li>
 *   <li><b>加速度</b> → 只在没踩油门、电机在出力时当负荷用（自适应巡航自己加速时油门是 0，只能靠它）；踩着油门时不看。</li>
 *   <li><b>车重、车速、阻力</b> → 巡航要的负荷（{@link #cruiseLoad}）：踩着油门（或自适应巡航）时负荷不低于它 —— 高速时轻踩着
 *       发动机也在出力，不像滑行。只是兜底，车速不是首要的。</li>
 *   <li><b>踩油门的快慢</b> → 急踩：负荷和转速起得更快、转速冲一下、进气声和高阶次猛一下，模拟换挡时直接降到该在的挡
 *       （可以连降几挡；慢慢踩到地板附近也一样，像自动挡的强制降挡开关）；
 *       缓踩没有这些，平顺地往上走。</li>
 *   <li><b>松油门的快慢</b> → 急松（D 挡 15 km/h 以上）：排气那一路轻微的「突突」，几百毫秒淡掉；不做像枪声的放炮。</li>
 *   <li><b>减速度</b> → 模拟换挡时重刹、回收越猛，降挡越早，降挡带补油的「轰」一下。</li>
 *   <li><b>车速</b> → 转速的一部分（连续）或挡位（模拟换挡）；电平随车速略升（盖过胎噪风噪）。</li>
 *   <li><b>刹车、动力流</b> → 回收或踩刹车时负荷归零（单踏板时油门轻踩到 5–12 % 还在回收，只看油门分不出来）。</li>
 *   <li><b>档位</b>：D 照两种走法；倒挡固定在倒挡齿比上、永远不升挡（上限 {@link #REVERSE_MAX_RPM}）；P / N 空挡，转速只跟油门
 *       （上限 {@link #FREE_REV_MAX_RPM}，只在车内）。美式 V8 的空挡照真车（{@link #realNeutral}）：松开后按摩擦、泵气落，
 *       {@link #NEUTRAL_FUEL_RPM} 以下恢复供油、低负荷地转着落，落到怠速时电控稳一下。</li>
 *   <li><b>伪挡位</b>（项目所有者 2026-10-21）：连续走法下油门踩到 {@link #PSEUDO_ON_PCT} 以上（或一脚到底），也按挡位走锯齿，
 *       和模拟换挡一样升挡；油门回到 {@link #PSEUDO_OFF_PCT} 以下、踩刹车或回收时回到连续。</li>
 *   <li><b>升挡</b>（两种走法一样）：先断扭（合成器那边燃烧减弱、电平掉一点），转速停一下再按 S 形落到新挡位，
 *       动力回来时「吼」一下；每次换挡给合成器一个记号（{@link #shiftSerial}），它据此放换挡的「咚」。</li>
 *   <li><b>挂着挡就是硬连着</b>（项目所有者 2026-10-24：除了 1 挡起步离合器半接触，发动机、变速箱、车轮是硬链接的）：
 *       起步时离合器 / 液力变矩器打滑，转速由油门撑着；挡位转速超过起步转速就接合（{@link #COUPLE_OVER}），之后转速 = 车速 × 齿比，
 *       油门只改负荷、不改转速（原来一直按打滑算，挂着挡踩油门转速比挡位高 200–970 rpm、一松就掉回去）。连续走法照旧（像无级变速）。</li>
 *   <li><b>换挡积极性</b>（{@link Input#eager}，项目所有者 2026-10-24：标准模式也出声，和运动模式只差升降挡的积极性）：
 *       标准模式照原来的换挡；运动模式轻踩时升挡点高 {@link #EAGER_UP_RPM}（踩到底一样）、松油门保持挡位、减速时早一点降挡。
 *       手动挡不管哪种模式松油门都不升挡。两种都：油门正往下踩时不升挡（{@link #TIP_IN_RATE}），踩着油门降挡留出往上拉的余地。</li>
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
        /** 加速度（m/s²）：正 = 加速，负 = 减速（{@link DriveInputs}）。只在没踩油门时当负荷用（自适应巡航），刹车降挡也看它。 */
        float accel;
        /** 踩着刹车（深度超过 {@link #BRAKE_ON}，或踏板 = 1）。 */
        boolean braking;
        Gear gear = Gear.UNKNOWN;
        Flow flow = Flow.UNKNOWN;
        RpmMode mode = RpmMode.CONTINUOUS;
        /**
         * 换挡积极性（0–1，项目所有者 2026-10-24）：0 = 标准模式（原来的换挡），1 = 运动模式 —— 升挡点高一些、松油门不升挡、
         * 减速时早一点降挡（{@link #EAGER_UP_RPM} 等）。别的都一样。
         */
        float eager;
        /** 运动模式的踏板（0 标准、1 运动，{@link #SPORT_PEDAL_EXP}）：车机在运动模式下踏板更灵，同样的油门开度出的力更大。 */
        float sportPedal;
    }

    // ---- 负荷
    static final float THROTTLE_DEADBAND = 3f;
    static final float THROTTLE_FULL = 85f;
    static final float LOAD_CURVE = 0.8f;
    /** 刹车行程超过这个（%）算踩着。 */
    static final float BRAKE_ON = 3f;
    /** 加速度到这个（m/s²）算车出满了力：7X 后驱 0–100 km/h 约 5.8 s（平均 4.8 m/s²），四驱约 3.8 s。 */
    static final float ACCEL_FULL = 5f;
    /** 加速度换算的负荷打几折（只在没踩油门、自适应巡航自己加速时用）。 */
    static final float ACCEL_LOAD_WEIGHT = 0.8f;
    static final float EFFORT_SMOOTH_SEC = 0.2f;
    /**
     * 运动模式的踏板（项目所有者 2026-10-24：运动模式下油门开度不大就有足够的实车加速度，模拟里只觉得速度轻轻松松就上去了）：
     * 运动模式踩 p % 当标准模式踩 100 × (p / 100)^它 % —— 20 → 30、40 → 50、60 → 68，踩到底一样。车机两种模式的踏板曲线
     * 没有数据，先按估的，听了再调。
     */
    static final float SPORT_PEDAL_EXP = 0.75f;
    /** 负荷过了这个（0–1）声音开始厚一点（{@link #push}），踩到底是 {@link #SURGE_MAX}。 */
    static final float SURGE_FROM_LOAD = 0.5f;
    static final float SURGE_MAX = 0.8f;

    // ---- 巡航要的负荷（{@link #cruiseLoad}）：车重、车速、阻力 —— 只是兜底
    /** 车重（kg）：7X 整备 2280–2475 kg，加一个人。 */
    static final float CAR_MASS_KG = 2400f;
    static final float ROLLING_COEF = 0.011f;
    /** 风阻系数 × 迎风面积（m²）：公开资料 Cd 0.247，车宽 1.93 m、高 1.66 m，迎风面积按约 2.7 m² 估。 */
    static final float DRAG_AREA_M2 = 0.67f;
    static final float AIR_DENSITY = 1.2f;
    /** 发动机到车轮的效率。 */
    static final float DRIVELINE_EFF = 0.85f;
    /**
     * 虚拟发动机各转速的最大扭矩（N·m）：机械增压 6.2 V8，照参考那台的公开数据（881 N·m @ 4800 rpm、527 kW @ 6000 rpm），
     * 低转按机械增压的样子早早起来；中间按转速线性插值。
     */
    static final float[] TORQUE_RPM = {1000f, 2000f, 3000f, 4800f, 6000f, 6500f};
    static final float[] TORQUE_NM = {600f, 760f, 840f, 881f, 839f, 790f};

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

    // ---- 挂着挡：起步打滑、接合（项目所有者 2026-10-24：除了 1 挡起步离合器半接触，发动机、变速箱、车轮是硬链接的）
    /** 打滑到接合用多久（秒）：挡位转速超过起步转速以后，转速在这么久里收到挡位转速上。 */
    static final float COUPLE_SEC = 0.3f;
    /**
     * 挡位转速超过起步转速多少（起步转速 ÷ 它）开始接合：这时打滑的转速（{@link #slipRpm}）只比挡位转速高约 1 %，接合时听不出往下掉
     * （2026-10-24：按挡位转速追上就接合、打滑按三次方的软最大值算时，接合那一下掉 20 %，手动挡 40 % 油门起步掉约 470 rpm）。
     */
    static final float COUPLE_OVER = 0.75f;
    /** 车速到这里（km/h）一定接合了（兜底，平时用不上）；行驶中接入时这么快就是接合着的。 */
    static final float COUPLE_KMH = 60f;

    // ---- 换挡积极性（{@link Input#eager}：0 标准模式、1 运动模式）
    /**
     * 运动模式升挡点抬高多少（rpm，松着、轻踩时满额，越往下踩越少，踩到底和标准模式一样都在快到上限处升）：轻踩巡航时比标准模式
     * 低一挡（100 km/h 轻踩：标准六挡约 2000 rpm，运动五挡约 2500）。
     */
    static final float EAGER_UP_RPM = 600f;
    /** 运动模式减速时降挡点抬高多少（按升挡点的比例）：转速掉到约 1700 rpm 就降，标准模式约 1150。 */
    static final float EAGER_DOWN_RAISE = 0.1f;
    /** 积极性变过去用多久（秒）：行驶中切换驾驶模式，挡位照常一挡一挡跟过去。 */
    static final float EAGER_SEC = 1f;
    /** 积极性超过这个、或者手动挡：松着油门不升挡（保持挡位、用发动机拖着）；只有快到上限才升。 */
    static final float EAGER_HOLD = 0.5f;
    /**
     * 油门正往下踩（踩得比这快，%/s）时不升挡：要的负荷还在涨，等踩稳了再看。保持挡位以后再踩油门，换挡负荷已经落下来了，
     * 不等它就会先升一挡、紧接着又降回来（2026-10-24 试听里见到的）。
     */
    static final float TIP_IN_RATE = 20f;

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

    // ---- 空挡照真车（{@link #realNeutral}，美式 V8；项目所有者 2026-10-23 给的参考视频：停着在 P 挡轰油门）
    /**
     * 松开油门，转速每秒落 {@code NEUTRAL_DECEL + rpm / NEUTRAL_DECEL_TAU}（rpm/s）：节气门关着，摩擦、泵气拖着曲轴、飞轮、
     * 增压器一起慢下来，转得越快拖得越狠。参考：从 4600 rpm 起每秒落约 1700 rpm，2000 rpm 附近约 1250，2.5 秒落到怠速
     * （原来按 0.4 秒的时间常数、每秒最多 4000 rpm 落，不到 2 秒，前半段太快）。离要去的转速近了按 {@link #NEUTRAL_LAND_SEC} 落稳。
     */
    static final float NEUTRAL_DECEL = 1000f;
    static final float NEUTRAL_DECEL_TAU = 6.5f;
    static final float NEUTRAL_LAND_SEC = 0.2f;
    /**
     * 空挡收油：这个转速（rpm）以上才断油，再高 {@link #NEUTRAL_CUT_SPAN} 全断。参考：松开时（4300 rpm 上下）车内一下轻了 10 dB、
     * 阶次几乎没了；落到 3300 rpm 慢慢回来，2600–2900 rpm 已经和轻踩着稳在那个转速一样响 —— 恢复供油，活塞低负荷地转着往下落
     * （项目所有者：松开油门发动机也在持续出声）。挂着挡滑行照旧（{@link #OVERRUN_FROM_RPM}）。
     */
    static final float NEUTRAL_FUEL_RPM = 2600f;
    static final float NEUTRAL_CUT_SPAN = 900f;
    /**
     * 落到怠速时电控稳一下（多给一点气、点火提前）：先落到怠速，再按 {@link #CATCH_RISE_SEC} 冲上 {@link #CATCH_FLARE_RPM}，
     * 再按 {@link #CATCH_FALL_SEC} 落回怠速，那一下的负荷 {@link #CATCH_LOAD}（项目所有者：降到一定转速后稍微发力，稳定在一个转速上）。
     * 参考：落到约 815 rpm，0.6 秒后到 920，约 2 秒回到 810。从怠速以上 {@link #CATCH_ARM_RPM} 往下落才算（轻轻动一下油门不算），
     * 离怠速 {@link #CATCH_FROM_RPM} 以内开始；中途踩油门就交还油门。
     */
    static final float CATCH_FLARE_RPM = 120f;
    static final float CATCH_RISE_SEC = 0.6f;
    static final float CATCH_FALL_SEC = 0.6f;
    static final float CATCH_LOAD = 0.08f;
    static final float CATCH_ARM_RPM = 600f;
    static final float CATCH_FROM_RPM = 30f;

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
    /** 挂着挡：开始接合了（挡位转速追上了起步转速，或者车速够了）；接合到几成（0 打滑 … 1 硬连着）。车停下重来。 */
    private boolean coupling;
    private float coupled;
    /** 此刻的换挡积极性（{@link Input#eager} 平滑过来的）。 */
    private float eager;
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
    // ---- 空挡照真车
    private final boolean realNeutral;
    /** 空挡里从怠速以上 {@link #CATCH_ARM_RPM} 往下落着（落到怠速时稳一下）。 */
    private boolean catchArmed;
    /** 稳一下走了多久（秒）；负的 = 没在稳。 */
    private float catchSec = -1f;

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
        this(profile, gearbox, crank, pseudoGears, false);
    }

    /**
     * @param realNeutral 空挡（P / N）照真车（美式 V8，项目所有者 2026-10-23）：松油门后按摩擦、泵气落转速，
     *                    {@link #NEUTRAL_FUEL_RPM} 以下恢复供油低负荷地转着往下落，落到怠速时电控稳一下；不照的话按
     *                    {@link SoundProfile#rpmFallSec} 平滑地落，断油到怠速附近
     */
    VirtualEngine(SoundProfile profile, Gearbox gearbox, boolean crank, boolean pseudoGears, boolean realNeutral) {
        this.p = profile;
        this.gearbox = gearbox;
        this.crank = crank;
        this.pseudoAllowed = pseudoGears;
        this.realNeutral = realNeutral;
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
        catchArmed = false;
        catchSec = -1f;
        float v = speedOf(in);
        speedKmh = v;
        gear = in.gear;
        gearIndex = 0;
        gearPicked = true;
        eager = clamp01(in.eager);
        // 车在走时接上：够快就是接合着的（混动车行驶中起动发动机，离合器同步了才合上）
        coupling = v >= COUPLE_KMH;
        coupled = coupling ? 1f : 0f;
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
            gearIndex = pickGear(p, gearbox, v, shiftLoad, SHIFT_GUARD, eager);
            if (!easing && gearIndex > 0
                    && v * gearbox.rpmPerKmh[gearIndex - 1] < upshiftRpm(p, 1f, eager) * SHIFT_GUARD) {
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
        eager += (clamp01(in.eager) - eager) * follow(dt, EAGER_SEC);
        effortEnv += (push(in) - effortEnv) * follow(dt, EFFORT_SMOOTH_SEC);
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

        // 负荷：急踩时起得快，踩刹车时落得快；踩着油门（或自适应巡航电机在出力）时不低于巡航要的那一截（车重、车速、阻力）
        float target = loadTarget(in);
        if (in.gear == Gear.D && !in.braking && in.flow != Flow.REGEN && (target > 0f || in.flow == Flow.DRIVE)) {
            target = Math.max(target, cruiseLoad(v, rpm));
        }
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
            updateCoupling(dt, v);
        } else {
            gearIndex = 0;
            gearPicked = false;
            shiftLeftSec = 0f;
            upLeftSec = 0f;
            coupling = false;
            coupled = 0f;
        }
        updateCatch(dt, target, in.gear);
        float goal = rpmTarget(v, in);
        if (upLeftSec > 0f) {
            stepUpshift(dt, goal, in.gear);
        } else {
            cut = 0f;
            moveRpm(dt, goal, shifting, in.gear);
        }
        // 看这一刻该有的负荷（不等平滑）：一松油门就断油；空挡照真车时只在高转断
        float overrunTarget = target >= OVERRUN_LOAD ? 0f
                : neutral(in.gear) ? clamp01((rpm - NEUTRAL_FUEL_RPM) / NEUTRAL_CUT_SPAN)
                : clamp01((rpm - p.idleRpm - OVERRUN_FROM_RPM) / OVERRUN_RPM_SPAN);
        overrun += (overrunTarget - overrun) * follow(dt, OVERRUN_SEC);
    }

    /**
     * 起步打滑 → 接合（项目所有者 2026-10-24）：车停下（{@link #SHIFT_RESET_KMH} 以下）离合器分开、变矩器打滑；挡位转速超过
     * 起步转速（{@link #COUPLE_OVER}，或者车速到了 {@link #COUPLE_KMH}）开始接合，{@link #COUPLE_SEC} 里收到挡位转速上，
     * 之后一直硬连着，直到再停下来。
     */
    private void updateCoupling(float dt, float v) {
        if (v < SHIFT_RESET_KMH) {
            coupling = false;
            coupled = 0f;
            return;
        }
        if (!coupling && (v * gearbox.rpmPerKmh[gearIndex] * COUPLE_OVER >= launchRpm(load) || v >= COUPLE_KMH)) {
            coupling = true;
        }
        if (coupling) {
            coupled += (1f - coupled) * follow(dt, COUPLE_SEC);
        }
    }

    /** 空挡照真车：从高处松油门落下来，落到怠速附近时电控稳一下（{@link #catchShape}）；踩油门、挂挡就不管了。 */
    private void updateCatch(float dt, float target, Gear g) {
        if (!neutral(g) || target >= OVERRUN_LOAD) {
            catchArmed = false;
            catchSec = -1f;
            return;
        }
        if (catchSec >= 0f) {
            catchSec += dt;
            if (catchSec > CATCH_RISE_SEC + 8f * CATCH_FALL_SEC) {
                catchSec = -1f;
            }
        }
        if (rpm > p.idleRpm + CATCH_ARM_RPM) {
            catchArmed = true;
        } else if (catchArmed && rpm < p.idleRpm + CATCH_FROM_RPM) {
            catchArmed = false;
            catchSec = 0f;
        }
    }

    /** 稳一下此刻冲到几成（0–1）：{@link #CATCH_RISE_SEC} 里 S 形起来，再按 {@link #CATCH_FALL_SEC} 落回去；没在稳是 0。 */
    private float catchShape() {
        if (catchSec < 0f) {
            return 0f;
        }
        return catchSec < CATCH_RISE_SEC ? smooth(catchSec / CATCH_RISE_SEC)
                : (float) Math.exp(-(catchSec - CATCH_RISE_SEC) / CATCH_FALL_SEC);
    }

    /** 此刻按空挡照真车走（{@link #realNeutral}，P、N、读不到挡位）。 */
    private boolean neutral(Gear g) {
        return realNeutral && g != Gear.D && g != Gear.R;
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
     * 运动模式（{@link #eager}）升挡点、减速时的降挡点都高一些，松着油门不升挡；手动挡松着油门也不升挡（快到上限除外）。
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
            gearIndex = pickGear(p, gearbox, v, shiftLoad, SHIFT_GUARD, eager);
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
            int to = pickGear(p, gearbox, v, shiftLoad, KICKDOWN_ROOM, eager);
            if (to < gearIndex) {
                downshiftTo(to, false);
                return;
            }
        }
        // 记住的负荷只管「先别升挡」；降挡平时看此刻的负荷，刹车 / 回收（补油降挡）才按记住的量
        float up = upshiftRpm(p, shiftLoad, eager);
        float downRatio = p.shiftDownRatio + EAGER_DOWN_RAISE * eager + STAB_DOWNSHIFT_RAISE * stabEnv
                + (easing ? DECEL_DOWNSHIFT_RAISE * decel : 0f);
        float down = upshiftRpm(p, easing ? shiftLoad : load, eager) * downRatio;
        float inGear = v * gearbox.rpmPerKmh[gearIndex];
        boolean slowing = easing && decel > NO_UPSHIFT_DECEL;
        // 松着油门保持挡位（运动模式；手动挡开车的人也不会松油门就升挡），快到上限才升。看踩着的量（不等负荷慢慢落）：
        // 巡航时转速就在升挡点下面一点，松开那一下换挡负荷先往下落，按平滑后的负荷判就会抢先升一挡
        boolean holding = loadTarget(in) < OVERRUN_LOAD && (eager > EAGER_HOLD || gearbox.manual())
                && inGear < upshiftRpm(p, 1f, eager) * SHIFT_GUARD;
        boolean pressing = in.pedalRate > TIP_IN_RATE;
        if (gearIndex < last && inGear >= up && !slowing && !holding && !pressing) {
            gearIndex++;
            shiftLeftSec = gearbox.upshiftSec;
            upLeftSec = gearbox.upshiftSec;
            upFromRpm = rpm;
            barkPending = true;
            shiftSerial++;
            shiftUp = true;
        } else if (gearIndex > 0 && inGear < down
                && v * gearbox.rpmPerKmh[gearIndex - 1] < up * (easing ? SHIFT_GUARD : KICKDOWN_ROOM)) {
            // 踩着油门降挡也留出往上拉的余地（和一脚到底一样）：不然一降就在升挡点底下，紧接着又升回去
            // （2026-10-24：急踩 70 % 时三挡 → 二挡 → 一挡 → 0.25 秒后又升二挡）
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
                    // 起步打滑时油门撑着转速（急踩还冲一下）；接合以后就是挡位转速（{@link #updateCoupling}）
                    float inGear = v * gearbox.rpmPerKmh[gearIndex];
                    float slipping = slipRpm(launchRpm(load), inGear)
                            + STAB_FLARE_RPM * SHIFT_FLARE_SHARE * stabEnv * (1f - speedCurve(p, v));
                    base = slipping + (inGear - slipping) * coupled;
                } else {
                    boolean easing = in.braking || in.flow == Flow.REGEN;
                    base = continuousRpm(p, v, load)
                            + STAB_FLARE_RPM * stabEnv * (1f - 0.5f * speedCurve(p, v))
                            + (easing ? DECEL_HOLD_RPM * decel * speedCurve(p, v) : 0f);
                }
                break;
            default:
                // P、N、读不到：空挡，转速只跟油门（照真车时落到怠速那一下电控稳一下）
                base = p.idleRpm + (FREE_REV_MAX_RPM - p.idleRpm) * load + CATCH_FLARE_RPM * catchShape();
                break;
        }
        return base + blip;
    }

    private void moveRpm(float dt, float target, boolean shifting, Gear g) {
        float cap = capFor(g);
        target = clamp(target, p.idleRpm, cap);
        boolean free = neutral(g);
        float riseTau = p.rpmRiseSec * (1f - STAB_RISE_CUT * stabEnv);
        float tau = shifting ? p.shiftSec : (target > rpm ? riseTau : (free ? NEUTRAL_LAND_SEC : p.rpmFallSec));
        float next = rpm + (target - rpm) * follow(dt, tau);
        if (!shifting) {
            float up = p.rpmRisePerSec * (1f + stabEnv) * dt;
            // 空挡照真车：落得多快看摩擦、泵气
            float down = (free ? NEUTRAL_DECEL + rpm / NEUTRAL_DECEL_TAU : p.rpmFallPerSec) * dt;
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

    /**
     * 同上，按驾驶模式的踏板（{@code sport} 0 标准 … 1 运动，{@link #SPORT_PEDAL_EXP}）：运动模式同样的开度当踩得深一些。
     */
    static float demand(float throttlePct, float sport) {
        if (!(throttlePct > THROTTLE_DEADBAND)) {
            return 0f;
        }
        float p = Math.min(throttlePct, 100f);
        float deeper = 100f * (float) Math.pow(p / 100f, SPORT_PEDAL_EXP);
        return demand(p + (deeper - p) * clamp01(sport));
    }

    /**
     * 加速度 → 车真正出的力（0–1）：只在没踩油门、D / R 挡、电机在出力时算 —— 自适应巡航自己加速（油门是 0）只能靠它；踩着油门时
     * 负荷只看油门（项目所有者 2026-10-24：车速、加速度都是滞后的）。下坡滑行加速不算。
     */
    static float effort(Input in) {
        if (in.braking || in.flow == Flow.REGEN || (in.gear != Gear.D && in.gear != Gear.R) || demand(in.throttlePct) > 0f) {
            return 0f;
        }
        return in.flow == Flow.DRIVE ? clamp01(in.accel / ACCEL_FULL) : 0f;
    }

    /**
     * 该有的负荷：踩刹车、回收时 0；空挡只看油门（照标准模式的踏板）；否则看油门（按驾驶模式的踏板），没踩油门、自适应巡航自己
     * 加速时看加速度（{@link #effort}）。巡航要的那一截（{@link #cruiseLoad}）由 {@link #step} 另外兜底。
     */
    static float loadTarget(Input in) {
        if (in.braking || in.flow == Flow.REGEN) {
            return 0f;
        }
        if (in.gear != Gear.D && in.gear != Gear.R) {
            return demand(in.throttlePct);
        }
        return Math.max(demand(in.throttlePct, in.sportPedal), ACCEL_LOAD_WEIGHT * effort(in));
    }

    /**
     * 猛加速时声音厚一点（{@link #LEVEL_SURGE_DB}、{@link #BRIGHT_SURGE_DB}）有多少（0–1）：踩着油门时按油门，负荷过了
     * {@link #SURGE_FROM_LOAD} 才有、踩到底 {@link #SURGE_MAX}（原来按加速度，晚几百毫秒）；没踩油门时按自适应巡航的加速度。
     */
    static float push(Input in) {
        if (in.braking || in.flow == Flow.REGEN || (in.gear != Gear.D && in.gear != Gear.R)) {
            return 0f;
        }
        float d = demand(in.throttlePct, in.sportPedal);
        return Math.max(effort(in), SURGE_MAX * clamp01((d - SURGE_FROM_LOAD) / (1f - SURGE_FROM_LOAD)));
    }

    /**
     * 巡航要的负荷（0–1）：车重、车速下滚动阻力和风阻要的功率（{@link #CAR_MASS_KG} 等）÷ 虚拟发动机此刻转速下最多出的功率
     * （{@link #TORQUE_NM}，乘传动效率）。只是兜底（项目所有者 2026-10-24：负荷按油门开度、踩的快慢模拟，车重、车速、阻力配合，
     * 车速绝不是首要的）：高速时轻踩着、或者自适应巡航稳着车速，发动机也在出力。100 km/h 六挡约 0.11，130 km/h 七挡约 0.19，
     * 160 km/h 八挡约 0.3；60 km/h 以下几乎是 0。
     */
    static float cruiseLoad(float speedKmh, float rpm) {
        float v = Math.max(speedKmh, 0f) / 3.6f;
        float force = ROLLING_COEF * CAR_MASS_KG * 9.81f + 0.5f * AIR_DENSITY * DRAG_AREA_M2 * v * v;
        float available = DRIVELINE_EFF * maxTorque(rpm) * rpm * (float) (2.0 * Math.PI / 60.0);
        return available > 0f ? clamp01(force * v / available) : 0f;
    }

    /** 虚拟发动机这个转速的最大扭矩（N·m，{@link #TORQUE_NM} 线性插值，两头照最边上的）。 */
    static float maxTorque(float rpm) {
        if (rpm <= TORQUE_RPM[0]) {
            return TORQUE_NM[0];
        }
        for (int k = 1; k < TORQUE_RPM.length; k++) {
            if (rpm <= TORQUE_RPM[k]) {
                float f = (rpm - TORQUE_RPM[k - 1]) / (TORQUE_RPM[k] - TORQUE_RPM[k - 1]);
                return TORQUE_NM[k - 1] + (TORQUE_NM[k] - TORQUE_NM[k - 1]) * f;
            }
        }
        return TORQUE_NM[TORQUE_NM.length - 1];
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
     * 起步打滑时的转速：起步打滑的转速（{@code launch}，跟着油门）和挡位转速（车速 × 齿比）平滑地合在一起（三次方的软最大值）——
     * 低速时打滑的转速撑着，车速上来后跟着挡位走，中间不出平台、也不往下掉。接合以后不用它（{@link #updateCoupling}）；倒挡一直用它。
     */
    static float gearedRpm(float launch, float inGear) {
        double a = Math.max(0f, launch);
        double b = Math.max(0f, inGear);
        return (float) Math.cbrt(a * a * a + b * b * b);
    }

    /**
     * D 挡起步打滑时的转速：起步转速和挡位转速的软最大值，按八次方 —— 低速时起步转速撑着，挡位转速超过它以后很快就贴上去
     * （交叉处高 9 %，超过三分之一时只高 1 %，{@link #COUPLE_OVER}），接合时不往下掉。倒挡还按 {@link #gearedRpm}。
     */
    static float slipRpm(float launch, float inGear) {
        double a = Math.max(0f, launch);
        double b = Math.max(0f, inGear);
        double a2 = a * a;
        double b2 = b * b;
        double a8 = a2 * a2 * a2 * a2;
        double b8 = b2 * b2 * b2 * b2;
        return (float) Math.pow(a8 + b8, 0.125);
    }

    /**
     * 升挡点：负荷越大越晚升，按平方 —— 轻踩、中等油门早升，不在低挡拖着；只有重踩、地板油才拉到快到上限
     * （项目所有者 2026-10-22）。离上限留 300 rpm。
     */
    static float upshiftRpm(SoundProfile p, float load) {
        return upshiftRpm(p, load, 0f);
    }

    /** 同上，换挡积极性 {@code eager}（0 标准 … 1 运动）：运动模式轻踩时抬高 {@link #EAGER_UP_RPM}，踩到底不抬。 */
    static float upshiftRpm(SoundProfile p, float load, float eager) {
        float l = clamp01(load);
        float l2 = l * l;
        return Math.min(p.shiftUpBaseRpm + EAGER_UP_RPM * clamp01(eager) * (1f - l2) + p.shiftUpLoadRpm * l2, p.maxRpm - 300f);
    }

    /** 按车速和负荷直接选挡：转速还在升挡点以下的最低一挡。 */
    static int pickGear(SoundProfile p, Gearbox gearbox, float speedKmh, float load) {
        return pickGear(p, gearbox, speedKmh, load, SHIFT_GUARD);
    }

    /** 同上，转速在升挡点的 {@code room} 倍以下（一脚到底时留出往上拉的余地，{@link #KICKDOWN_ROOM}）。 */
    static int pickGear(SoundProfile p, Gearbox gearbox, float speedKmh, float load, float room) {
        return pickGear(p, gearbox, speedKmh, load, room, 0f);
    }

    /** 同上，按换挡积极性 {@code eager}（{@link #upshiftRpm(SoundProfile, float, float)}）。 */
    static int pickGear(SoundProfile p, Gearbox gearbox, float speedKmh, float load, float room, float eager) {
        float up = upshiftRpm(p, load, eager) * room;
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
     * 合成器用的负荷：降挡补油、空挡落到怠速时电控稳一下的那一下也算进去。行驶中起动、电机带着转的那一段是 0（节气门关着，
     * 点着那一下才是一个清楚的「轰」）；油门要的负荷只拿来定带到多少转。
     */
    float voiceLoad() {
        return phase == Phase.JOINING ? 0f : Math.max(Math.max(load, blipLoad), CATCH_LOAD * catchShape());
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

    /** 此刻的换挡积极性（0 标准 … 1 运动）。 */
    float eager() {
        return eager;
    }

    /** 挂着挡接合到几成（0 起步打滑 … 1 硬连着）。 */
    float coupled() {
        return coupled;
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
