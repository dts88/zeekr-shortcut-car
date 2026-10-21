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
 * </ul>
 *
 * <h3>开始工作（{@link #ignite}）</h3>
 *
 * <p>车停着：启动声 —— 起动机转动、点着、转速冲到 {@link #START_FLARE_RPM}、回落到怠速（{@link Phase#STARTING}）。
 * 车在走：开阀门式接入 —— 按此刻的车速、油门算好转速，{@link #JOIN_SEC} 里从闷到亮淡入；模拟换挡顺势降一挡、连续补一点转速，
 * 踩着刹车或在回收时只淡入（{@link Phase#JOINING}）。之后一直转着：停着就是怠速（项目所有者 2026-10-21 选的）。</p>
 */
final class VirtualEngine {

    /** 转速怎么跟车走（项目所有者 2026-10-21：两种都做，上车听了再定）。 */
    enum RpmMode { CONTINUOUS, SHIFTS }

    enum Gear { P, R, N, D, UNKNOWN }

    /** 动力流（{@code Signal.POWER_FLOW}）。 */
    enum Flow { DRIVE, REGEN, IDLE, NOT_READY, UNKNOWN }

    enum Phase { STARTING, JOINING, RUNNING }

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
    /** 倒挡整体低多少：倒车雷达要听清。 */
    static final float REVERSE_DB = -6f;
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
    /** 油门踩到这里（%，地板附近）也算一脚到底：像自动挡的强制降挡开关，踩得再慢也直接降到该在的挡。 */
    static final float KICKDOWN_PEDAL_PCT = 90f;
    /** 减速度超过这个（0–1）时不升挡：刹车、回收减速时运动模式不升挡。 */
    static final float NO_UPSHIFT_DECEL = 0.1f;

    // ---- 启动
    static final float START_CRANK_SEC = 0.6f;
    static final float START_CATCH_SEC = 0.3f;
    static final float START_SETTLE_SEC = 1.3f;
    static final float START_CRANK_RPM = 250f;
    static final float START_CRANK_LOAD = 0.12f;
    static final float START_FLARE_RPM = 2000f;
    static final float START_CRANK_TAU = 0.15f;
    static final float START_CATCH_TAU = 0.08f;
    static final float START_SETTLE_TAU = 0.35f;
    static final float STARTER_SEC = 0.04f;
    /** 车速到这个（km/h）就不算停着：开始工作时走接入，启动途中车动了就结束启动。 */
    static final float START_LEAVE_KMH = 2f;
    /** 启动的回落段里油门踩过这个负荷，交还正常走法。 */
    static final float START_TAKEOVER_LOAD = 0.1f;

    // ---- 接入（开阀门）
    static final float JOIN_SEC = 0.8f;
    static final float JOIN_TONE_FROM_HZ = 400f;
    static final float TONE_OPEN_HZ = 20000f;
    static final float JOIN_BLIP_RPM = 600f;

    // ---- 收油（断油、发动机被车拖着转）：给合成器
    /** 该有的负荷（{@link #loadTarget}）低于这个算收油。 */
    static final float OVERRUN_LOAD = 0.08f;
    /** 转速高出怠速这么多（rpm）开始算，再高 {@link #OVERRUN_RPM_SPAN} 算满。 */
    static final float OVERRUN_FROM_RPM = 150f;
    static final float OVERRUN_RPM_SPAN = 800f;
    static final float OVERRUN_SEC = 0.15f;

    /** 一块最多按多长推进（秒）：线程被耽搁了也不一步跳太远。 */
    static final float MAX_DT = 0.1f;

    private final SoundProfile p;
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
    private float shiftDipDb;
    private float fade = 1f;
    private float toneHz = TONE_OPEN_HZ;
    private float starter;
    private float overrun;
    private float speedKmh;
    private Gear gear = Gear.UNKNOWN;

    VirtualEngine(SoundProfile profile) {
        this.p = profile;
        this.rpm = profile.idleRpm;
    }

    /** 开始工作：车停着放启动声，车在走就开阀门接入。 */
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
        shiftDipDb = 0f;
        shiftLoad = 0f;
        phaseSec = 0f;
        starter = 0f;
        overrun = 0f;
        float v = speedOf(in);
        speedKmh = v;
        gear = in.gear;
        if (v < START_LEAVE_KMH) {
            phase = Phase.STARTING;
            rpm = 0f;
            load = 0f;
            fade = 1f;
            toneHz = TONE_OPEN_HZ;
            gearIndex = 0;
            gearPicked = true;
            return;
        }
        phase = Phase.JOINING;
        fade = 0f;
        toneHz = JOIN_TONE_FROM_HZ;
        load = loadTarget(in);
        shiftLoad = load;
        boolean easing = in.braking || in.flow == Flow.REGEN;
        gearIndex = 0;
        gearPicked = true;
        if (in.gear == Gear.D && in.mode == RpmMode.SHIFTS) {
            gearIndex = pickGear(p, v, load);
            if (!easing && gearIndex > 0
                    && v * p.gearRpmPerKmh[gearIndex - 1] < upshiftRpm(p, 1f) * SHIFT_GUARD) {
                // 顺势降一挡：表示运动模式接上了；换挡看的负荷记成满的，这一挡保持一会儿
                gearIndex--;
                shiftLoad = 1f;
                blip = DOWNSHIFT_BLIP_RPM;
                blipLoad = DOWNSHIFT_BLIP_LOAD;
            }
        } else if (in.gear == Gear.D && !easing) {
            blip = JOIN_BLIP_RPM;
        }
        rpm = clamp(rpmTarget(v, in), p.idleRpm, capFor(in.gear));
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

        if (phase == Phase.STARTING) {
            stepStart(dt, v, in);
            return;
        }
        if (phase == Phase.JOINING) {
            fade = Math.min(1f, phaseSec / JOIN_SEC);
            toneHz = JOIN_TONE_FROM_HZ * (float) Math.pow(TONE_OPEN_HZ / JOIN_TONE_FROM_HZ, fade);
            if (phaseSec >= JOIN_SEC) {
                phase = Phase.RUNNING;
                fade = 1f;
                toneHz = TONE_OPEN_HZ;
            }
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
        boolean shifting = false;
        if (in.gear == Gear.D && in.mode == RpmMode.SHIFTS) {
            updateGear(dt, v, in);
            shifting = shiftLeftSec > 0f;
        } else {
            gearIndex = 0;
            gearPicked = false;
            shiftLeftSec = 0f;
            shiftDipDb = 0f;
        }
        moveRpm(dt, rpmTarget(v, in), shifting, in.gear);
        // 看这一刻该有的负荷（不等平滑）：一松油门就断油
        float overrunTarget = target < OVERRUN_LOAD
                ? clamp01((rpm - p.idleRpm - OVERRUN_FROM_RPM) / OVERRUN_RPM_SPAN) : 0f;
        overrun += (overrunTarget - overrun) * follow(dt, OVERRUN_SEC);
    }

    /** 启动：转动 → 点着冲高 → 回落到怠速。回落段里踩了油门、或者车动了，交还正常走法。 */
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
            gearPicked = false;
            if (rpm < p.idleRpm) {
                rpm = p.idleRpm;
            }
        }
    }

    /**
     * 模拟换挡：升挡点随换挡负荷（最近的负荷慢慢回落），降挡点随急踩、重刹抬高；刹车、回收减速时不升挡；
     * 猛踩直接降到该在的挡（一脚到底可以连降几挡），重刹一挡一挡地降；降挡带补油，升挡音量掉一下。
     */
    private void updateGear(float dt, float v, Input in) {
        if (shiftLeftSec > 0f) {
            shiftLeftSec = Math.max(0f, shiftLeftSec - dt);
            if (shiftLeftSec == 0f) {
                shiftDipDb = 0f;
            }
        }
        if (v < SHIFT_RESET_KMH) {
            gearIndex = 0;
            gearPicked = true;
            return;
        }
        if (!gearPicked) {
            // 刚从别的挡、别的走法过来：按此刻的车速和负荷直接选，不一挡一挡地升
            gearIndex = pickGear(p, v, shiftLoad);
            gearPicked = true;
            return;
        }
        if (shiftLeftSec > 0f) {
            return;
        }
        int last = p.gearRpmPerKmh.length - 1;
        boolean easing = in.braking || in.flow == Flow.REGEN;
        if (kickdown && !easing) {
            // 一脚到底：直接降到此刻车速下该在的挡（转速还在升挡点以下的最低一挡），可以连降几挡
            int to = pickGear(p, v, shiftLoad);
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
        float inGear = v * p.gearRpmPerKmh[gearIndex];
        boolean slowing = easing && decel > NO_UPSHIFT_DECEL;
        if (gearIndex < last && inGear >= up && !slowing) {
            gearIndex++;
            shiftLeftSec = p.shiftSec * SHIFT_SETTLE;
            shiftDipDb = p.shiftDipDb;
        } else if (gearIndex > 0 && inGear < down
                && v * p.gearRpmPerKmh[gearIndex - 1] < up * SHIFT_GUARD) {
            downshiftTo(gearIndex - 1, easing);
        }
    }

    /** 降到 {@code to} 挡：转速跟着冲上去，带一点补油（刹车 / 回收时那一下更响）。 */
    private void downshiftTo(int to, boolean easing) {
        gearIndex = to;
        shiftLeftSec = p.shiftSec * SHIFT_SETTLE;
        shiftDipDb = 0f;
        blip = Math.max(blip, DOWNSHIFT_BLIP_RPM);
        blipLoad = Math.max(blipLoad, easing ? DOWNSHIFT_BLIP_LOAD : DOWNSHIFT_BLIP_LOAD * 0.5f);
    }

    /** 此刻该去的转速（不改状态）。 */
    private float rpmTarget(float v, Input in) {
        float base;
        switch (in.gear) {
            case R:
                base = Math.min(gearedRpm(launchRpm(load), v * p.reverseRpmPerKmh), REVERSE_MAX_RPM);
                break;
            case D:
                if (in.mode == RpmMode.SHIFTS) {
                    base = gearedRpm(launchRpm(load), v * p.gearRpmPerKmh[gearIndex])
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

    /** 升挡点：负荷越大越晚升（按 1.5 次方：轻油门早升，地板油拉到快到上限），离上限留 300 rpm。 */
    static float upshiftRpm(SoundProfile p, float load) {
        float l = clamp01(load);
        return Math.min(p.shiftUpBaseRpm + p.shiftUpLoadRpm * l * (float) Math.sqrt(l), p.maxRpm - 300f);
    }

    /** 按车速和负荷直接选挡：转速还在升挡点以下的最低一挡。 */
    static int pickGear(SoundProfile p, float speedKmh, float load) {
        float up = upshiftRpm(p, load) * SHIFT_GUARD;
        for (int g = 0; g < p.gearRpmPerKmh.length; g++) {
            if (speedKmh * p.gearRpmPerKmh[g] < up) {
                return g;
            }
        }
        return p.gearRpmPerKmh.length - 1;
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

    /** 合成器用的负荷：降挡补油那一下也算进去。 */
    float voiceLoad() {
        return Math.max(load, blipLoad);
    }

    /** 电平（dB，相对满负荷）：负荷、车速、真加速、急踩、巡航收敛、换挡、倒挡都在里面；声浪自己的音量另加。 */
    float levelDb() {
        float s = speedCurve(p, speedKmh);
        float l = voiceLoad();
        float db = LEVEL_FLOOR_DB + LEVEL_LOAD_DB * (float) Math.pow(l, LEVEL_LOAD_CURVE) + LEVEL_SPEED_DB * s
                + LEVEL_SURGE_DB * effortEnv + LEVEL_STAB_DB * stabEnv - cruiseCutDb + shiftDipDb;
        if (gear == Gear.R) {
            db += REVERSE_DB;
        }
        return db;
    }

    /** 开始工作时的淡入（0–1）：接入时 {@link #JOIN_SEC} 里从 0 到 1，启动时一开始就是 1。 */
    float fade() {
        return fade;
    }

    /** 阀门：接入时低通从 {@link #JOIN_TONE_FROM_HZ} 打开到 {@link #TONE_OPEN_HZ}。 */
    float toneHz() {
        return toneHz;
    }

    /** 起动机那一层有多响（0–1）。 */
    float starter() {
        return starter;
    }

    /** 高阶次多加的 dB（急踩、猛加速时更亮）。 */
    float brightDb() {
        return BRIGHT_STAB_DB * stabEnv + BRIGHT_SURGE_DB * effortEnv;
    }

    /** 进气声多加的 dB（急踩时猛一下）。 */
    float intakeBoostDb() {
        return INTAKE_STAB_DB * stabEnv;
    }

    /** 急松油门的突突（0–1）。 */
    float burble() {
        return liftEnv;
    }

    /**
     * 收油时发动机被车拖着转（断油）多少（0–1）：负荷几乎没有、转速在怠速之上 —— 滑行、刹车、空挡收油回落。
     * 启动途中是 0。
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
}
