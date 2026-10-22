package com.kooo.evcam.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 虚拟发动机（项目所有者 2026-10-21 定的走法，docs/local/drive-sound-plan.md §2、§9）。
 *
 * <p>算错的表现：地板油一路顶在红线上；急踩和缓踩听起来一样；松油门、刹车时乱升挡；倒挡升挡；
 * 停着切到运动模式没有启动声、或者启动声之后一直是起动机的声音；行驶中接入时不点火就冒出来、降的那一挡马上又升回去；
 * 切出运动模式时发动机不熄火、或者熄了火停不下来；
 * 自适应巡航自己加速时没声音；块大小一变声音的快慢就变。</p>
 */
public class VirtualEngineTest {

    private static final SoundProfile P = SoundProfile.V8;
    /** 和声音线程一样的一块：256 帧 / 48 kHz。 */
    private static final float DT = 256f / 48_000f;

    private static VirtualEngine.Input input(VirtualEngine.Gear gear, float kmh, float throttle) {
        VirtualEngine.Input in = new VirtualEngine.Input();
        in.gear = gear;
        in.speedKmh = kmh;
        in.throttlePct = throttle;
        in.flow = throttle > 3f ? VirtualEngine.Flow.DRIVE : VirtualEngine.Flow.IDLE;
        return in;
    }

    private static void run(VirtualEngine e, VirtualEngine.Input in, float seconds) {
        int steps = Math.round(seconds / DT);
        for (int i = 0; i < steps; i++) {
            e.step(DT, in);
        }
    }

    // ================================================================= 纯函数

    @Test
    public void throttleDemandHasADeadbandAndSaturates() {
        assertEquals(0f, VirtualEngine.demand(0f), 0f);
        assertEquals(0f, VirtualEngine.demand(3f), 0f);
        assertEquals(0f, VirtualEngine.demand(Float.NaN), 0f);
        assertEquals(1f, VirtualEngine.demand(85f), 1e-6f);
        assertEquals(1f, VirtualEngine.demand(100f), 0f);
        float last = 0f;
        for (int t = 0; t <= 100; t++) {
            float d = VirtualEngine.demand(t);
            assertTrue("油门越深负荷不会变小: " + t, d >= last);
            last = d;
        }
    }

    @Test
    public void continuousRpmIsMonotonicBoundedAndDoesNotPinAtTheLimit() {
        for (int v = 0; v <= 200; v += 5) {
            float previousLoad = 0f;
            for (int l = 0; l <= 20; l++) {
                float load = l / 20f;
                float rpm = VirtualEngine.continuousRpm(P, v, load);
                assertTrue(rpm >= P.idleRpm && rpm <= P.maxRpm);
                assertTrue("负荷越大转速不会变低", rpm >= previousLoad);
                previousLoad = rpm;
                if (v > 0) {
                    assertTrue("车速越快转速不会变低",
                            rpm >= VirtualEngine.continuousRpm(P, v - 5, load));
                }
            }
        }
        assertEquals(P.idleRpm, VirtualEngine.continuousRpm(P, 0f, 0f), 0f);
        assertTrue("100 km/h 地板油还没顶到上限",
                VirtualEngine.continuousRpm(P, 100f, 1f) < P.maxRpm - 500f);
        assertTrue("一踩油门先跳起来",
                VirtualEngine.continuousRpm(P, 0f, 1f) > P.idleRpm + 1500f);
    }

    @Test
    public void brakingAndRegenTakeTheLoadAway() {
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 60f, 50f);
        assertTrue(VirtualEngine.loadTarget(in) > 0.5f);
        in.braking = true;
        assertEquals(0f, VirtualEngine.loadTarget(in), 0f);
        in.braking = false;
        in.flow = VirtualEngine.Flow.REGEN;
        assertEquals("单踏板轻踩还在回收：没有负荷", 0f, VirtualEngine.loadTarget(in), 0f);
    }

    @Test
    public void accelerationCountsOnlyWhenTheMotorDrives() {
        // 自适应巡航自己加速：油门 0，电机在出力，2.5 m/s²
        VirtualEngine.Input acc = input(VirtualEngine.Gear.D, 80f, 0f);
        acc.flow = VirtualEngine.Flow.DRIVE;
        acc.accel = 2.5f;
        assertEquals(VirtualEngine.ACCEL_LOAD_WEIGHT * 0.5f, VirtualEngine.loadTarget(acc), 1e-6f);
        // 下坡滑行也在加速，但电机没出力
        acc.flow = VirtualEngine.Flow.IDLE;
        assertEquals(0f, VirtualEngine.loadTarget(acc), 0f);
        // 空挡只看油门
        VirtualEngine.Input neutral = input(VirtualEngine.Gear.N, 0f, 0f);
        neutral.accel = 3f;
        neutral.flow = VirtualEngine.Flow.DRIVE;
        assertEquals(0f, VirtualEngine.loadTarget(neutral), 0f);
    }

    // ================================================================= 启动、接入

    @Test
    public void startingFromStandstillCranksCatchesAndSettlesToIdle() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.P, 0f, 0f);
        e.ignite(in);
        assertEquals(VirtualEngine.Phase.STARTING, e.phase());
        run(e, in, 0.4f);
        assertTrue("起动机在转", e.starter() > 0.5f);
        assertTrue("还没点着", e.rpm() < 400f);
        run(e, in, 0.45f);
        assertTrue("点着了，转速冲上来: " + e.rpm(), e.rpm() > 1500f);
        assertTrue("起动机停了", e.starter() < 0.2f);
        run(e, in, 1.6f);
        assertEquals(VirtualEngine.Phase.RUNNING, e.phase());
        assertEquals("回落到怠速", P.idleRpm, e.rpm(), 120f);
        assertEquals("怠速的电平", VirtualEngine.LEVEL_FLOOR_DB, e.levelDb(), 1f);
        assertEquals(1f, e.fire(), 0f);
        assertEquals("点着一次", 1, e.catchSerial());
    }

    @Test
    public void withoutTheStarterItCatchesAtOnce() {
        // 「美式 V8」「超音速」不要起动机那一段（项目所有者 2026-10-21）：一开始就点着、冲上来，再回落到怠速
        VirtualEngine e = new VirtualEngine(P, false);
        VirtualEngine.Input in = input(VirtualEngine.Gear.P, 0f, 0f);
        e.ignite(in);
        assertEquals(VirtualEngine.Phase.STARTING, e.phase());
        assertEquals(1f, e.fire(), 0f);
        assertEquals(1, e.catchSerial());
        float peak = 0f;
        for (int i = 0; i < Math.round(0.35f / DT); i++) {
            e.step(DT, in);
            assertEquals("没有起动机", 0f, e.starter(), 0f);
            peak = Math.max(peak, e.rpm());
        }
        // 项目所有者 2026-10-22：体感再强 20 % 左右，冲到 2400 附近（原来 2000）
        assertTrue("一开始就点着、冲上来: " + peak, peak > 2200f && peak <= VirtualEngine.START_FLARE_RPM);
        run(e, in, 1.6f);
        assertEquals(VirtualEngine.Phase.RUNNING, e.phase());
        assertEquals("回落到怠速", P.idleRpm, e.rpm(), 120f);
        assertEquals("只点着一次", 1, e.catchSerial());
    }

    @Test
    public void theCatchIsBriefLouderAndBrighterThenSettles() {
        VirtualEngine e = new VirtualEngine(P, false);
        VirtualEngine.Input in = input(VirtualEngine.Gear.P, 0f, 0f);
        e.ignite(in);
        run(e, in, 0.25f);
        assertEquals("冲高那一段多冲满", VirtualEngine.SURGE_GAIN_DB, e.outputDb(), 0.01f);
        assertTrue(e.brightDb() >= VirtualEngine.SURGE_BRIGHT_DB - 0.01f);
        assertTrue(e.intakeBoostDb() >= VirtualEngine.SURGE_INTAKE_DB - 0.01f);
        run(e, in, 2.5f);
        assertEquals("之后淡掉", 0f, e.outputDb(), 0.01f);
        assertEquals(0f, e.brightDb(), 0.01f);
    }

    @Test
    public void pressingTheThrottleDuringTheSettleHandsBackAtOnce() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.P, 0f, 0f);
        e.ignite(in);
        run(e, in, 1.0f);
        assertEquals(VirtualEngine.Phase.STARTING, e.phase());
        in.throttlePct = 50f;
        e.step(DT, in);
        assertEquals(VirtualEngine.Phase.RUNNING, e.phase());
    }

    @Test
    public void joiningWhileMovingSpinsUpThenCatchesWithAFlare() {
        // 项目所有者 2026-10-22 问开阀门还是混动车行驶中起动发动机：做后者（项目所有者同意）—— 电机带起来、点着、补油冲一下
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 60f, 25f);
        e.ignite(in);
        assertEquals(VirtualEngine.Phase.JOINING, e.phase());
        assertEquals("还没点着", 0f, e.fire(), 0f);
        assertEquals("发动机本来停着", 0f, e.rpm(), 0f);
        run(e, in, 0.15f);
        assertEquals(VirtualEngine.Phase.JOINING, e.phase());
        assertEquals(0f, e.fire(), 0f);
        assertEquals("带着转时不算断油", 0f, e.overrun(), 0f);
        assertTrue("电机把它带起来了: " + e.rpm(), e.rpm() > 0.8f * VirtualEngine.continuousRpm(P, 60f, 0f));
        run(e, in, 0.08f);
        assertEquals(VirtualEngine.Phase.RUNNING, e.phase());
        assertEquals(1f, e.fire(), 0f);
        assertEquals("点着一次", 1, e.catchSerial());
        assertTrue("点着那一下多冲一点", e.outputDb() - VirtualEngine.SPEED_GAIN_DB * VirtualEngine.speedCurve(P, 60f) > 1f);
        float steady = VirtualEngine.continuousRpm(P, 60f, VirtualEngine.demand(25f));
        float peak = 0f;
        for (int i = 0; i < Math.round(0.5f / DT); i++) {
            e.step(DT, in);
            peak = Math.max(peak, e.rpm());
        }
        assertTrue("点着时补油冲一下: " + peak + " vs " + steady, peak > steady + 200f);
        run(e, in, 1.5f);
        assertEquals("回到该有的转速", steady, e.rpm(), 150f);
    }

    @Test
    public void joiningWhileBrakingCatchesWithoutAFlare() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 60f, 0f);
        in.braking = true;
        e.ignite(in);
        float peak = 0f;
        for (int i = 0; i < Math.round(1f / DT); i++) {
            e.step(DT, in);
            peak = Math.max(peak, e.rpm());
        }
        assertEquals(VirtualEngine.Phase.RUNNING, e.phase());
        assertEquals(1f, e.fire(), 0f);
        assertTrue("不补转速: " + peak, peak < VirtualEngine.continuousRpm(P, 60f, 0f) + 50f);
    }

    @Test
    public void joiningWithShiftsDropsAGearAndHoldsIt() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 70f, 25f);
        in.mode = VirtualEngine.RpmMode.SHIFTS;
        int natural = VirtualEngine.pickGear(P, 70f, VirtualEngine.demand(25f));
        e.ignite(in);
        assertEquals("顺势降一挡", natural - 1, e.gearIndex());
        run(e, in, 1f);
        assertEquals("这一挡保持住，不马上升回去", natural - 1, e.gearIndex());
        // 接着加速：挡位跟着升上去
        for (int i = 0; i < 1000; i++) {
            in.speedKmh = 70f + 25f * i / 1000f;
            e.step(DT, in);
        }
        assertTrue(e.gearIndex() >= natural);
    }

    // ================================================================= 熄火

    /** 跑到停住、放完（最多 {@code limit} 秒）；返回 {停住的时刻, 放完的时刻, 往回升了几次}（秒；没到是 −1）。 */
    private static float[] runDown(VirtualEngine e, VirtualEngine.Input in, float limit) {
        float stoppedAt = -1f;
        float t = 0f;
        float prev = e.rpm();
        int rises = 0;
        while (!e.done() && t < limit) {
            e.step(DT, in);
            t += DT;
            assertTrue("转速不会是负的", e.rpm() >= 0f);
            assertEquals("熄着火", 0f, e.fire(), 0f);
            if (e.rpm() > prev + 1f) {
                rises++;
            }
            prev = e.rpm();
            if (stoppedAt < 0f && e.rpm() <= 0f) {
                stoppedAt = t;
            }
        }
        return new float[] {stoppedAt, e.done() ? t : -1f, rises};
    }

    @Test
    public void aParkedShutdownRunsDownLikeARealEngine() {
        // 项目所有者 2026-10-22：停着切出运动模式要有熄火的效果，照车主偏好的那台机械增压 V8 —— 怠速约 0.7 秒停住，最后几圈一顿一顿
        VirtualEngine e = new VirtualEngine(P, false);
        VirtualEngine.Input in = input(VirtualEngine.Gear.P, 0f, 0f);
        e.ignite(in);
        run(e, in, 3f);
        assertEquals(P.idleRpm, e.rpm(), 50f);
        e.shutdown(in);
        assertEquals(VirtualEngine.Phase.STOPPING, e.phase());
        assertEquals(0f, e.fire(), 0f);
        assertFalse(e.done());
        float[] r = runDown(e, in, 3f);
        assertTrue("怠速约 0.7 秒停住: " + r[0], r[0] > 0.5f && r[0] < 0.95f);
        assertTrue("停住以后再留一会儿才放完: " + r[1], r[1] - r[0] >= VirtualEngine.STOP_TAIL_SEC - DT);
        assertTrue("最后几圈一顿一顿: " + r[2], r[2] >= 2f);
        assertEquals(0f, e.overrun(), 0f);
    }

    @Test
    public void stoppingWhileMovingIsQuickerAndQuiet() {
        // 车在走：像混动车行驶中停发动机，电机帮着拖停
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 60f, 25f);
        e.ignite(in);
        run(e, in, 3f);
        float from = e.rpm();
        e.shutdown(in);
        float[] r = runDown(e, in, 3f);
        // 停着时从同样的转速落下来要多久（同一条式子，按停着的参数）
        float parked = VirtualEngine.STOP_TAU * (float) Math.log(1.0 + from / (VirtualEngine.STOP_DECEL * VirtualEngine.STOP_TAU));
        assertTrue("从 " + from + " rpm 停住: " + r[0] + " s，停着要 " + parked + " s", r[0] > 0f && r[0] < 0.8f * parked);
        assertTrue(e.done());
    }

    @Test
    public void aShutdownCanCutAStartAndIsNotRestarted() {
        // 刚切进运动模式又切出去：还在起动机带着转就熄火
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.P, 0f, 0f);
        e.ignite(in);
        run(e, in, 0.3f);
        e.shutdown(in);
        assertEquals(0f, e.starter(), 0f);
        run(e, in, 0.2f);
        float rpm = e.rpm();
        e.shutdown(in);
        e.step(DT, in);
        assertTrue("再叫一遍不重来", e.rpm() <= rpm);
        float[] r = runDown(e, in, 2f);
        assertTrue(r[1] > 0f && r[1] < 1f);
    }

    // ================================================================= 挡位

    @Test
    public void reverseStaysInItsGearWhateverTheInputs() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.R, 0f, 0f);
        in.mode = VirtualEngine.RpmMode.SHIFTS;
        e.ignite(in);
        run(e, in, 2.5f);
        for (float throttle = 0f; throttle <= 100f; throttle += 10f) {
            for (float kmh = 0f; kmh <= 20f; kmh += 2f) {
                in.throttlePct = throttle;
                in.speedKmh = kmh;
                in.pedalRate = throttle * 5f;
                in.accel = 2f;
                run(e, in, 0.2f);
                assertEquals("倒挡不升挡", 0, e.gearIndex());
                assertTrue("倒挡上限", e.rpm() <= VirtualEngine.REVERSE_MAX_RPM);
            }
        }
        in.throttlePct = 0f;
        in.pedalRate = 0f;
        in.speedKmh = 20f;
        run(e, in, 3f);
        assertEquals("转速跟着倒挡齿比", 20f * P.reverseRpmPerKmh, e.rpm(), 20f * P.reverseRpmPerKmh * 0.05f);
        assertEquals(20f * VirtualEngine.REVERSE_WHINE_HZ_PER_KMH, e.whineHz(), 1e-3f);
        assertEquals("车速快了啸叫到头", 1f, e.whineAmp(), 0f);
    }

    @Test
    public void launchSlipBlendsIntoTheGearWithoutAPlateauOrADip() {
        float launch = P.idleRpm + (P.maxRpm - P.idleRpm) * P.pedalKick * 0.47f;
        assertEquals(launch, VirtualEngine.gearedRpm(launch, 0f), 0.01f);
        float last = launch;
        for (int kmh = 1; kmh <= 40; kmh++) {
            float rpm = VirtualEngine.gearedRpm(launch, kmh * P.gearRpmPerKmh[0]);
            assertTrue("起步时转速跟着车速一路往上: " + kmh, rpm > last);
            assertTrue(rpm >= launch - 0.01f && rpm >= kmh * P.gearRpmPerKmh[0] - 0.01f);
            last = rpm;
        }
        assertEquals("车速上来后就是挡位转速", 60f * P.gearRpmPerKmh[0],
                VirtualEngine.gearedRpm(launch, 60f * P.gearRpmPerKmh[0]), 60f * P.gearRpmPerKmh[0] * 0.02f);
    }

    @Test
    public void reverseIsNotTurnedDown() {
        // 项目所有者 2026-10-21：车机自己在倒挡压低音量，声浪不再另降
        VirtualEngine r = new VirtualEngine(P);
        VirtualEngine n = new VirtualEngine(P);
        VirtualEngine.Input inR = input(VirtualEngine.Gear.R, 5f, 30f);
        VirtualEngine.Input inN = input(VirtualEngine.Gear.N, 5f, 30f);
        r.ignite(inR);
        n.ignite(inN);
        run(r, inR, 3f);
        run(n, inN, 3f);
        assertEquals(n.outputDb(), r.outputDb(), 0f);
        assertEquals(n.levelDb(), r.levelDb(), 0.01f);
    }

    @Test
    public void speedMakesItALittleLouder() {
        // 项目所有者 2026-10-21：响度差压窄以后，高速还是稍响一点 —— 加在总音量上，不随电平一起被压窄
        VirtualEngine stopped = new VirtualEngine(P);
        VirtualEngine slow = new VirtualEngine(P);
        VirtualEngine fast = new VirtualEngine(P);
        VirtualEngine.Input inStopped = input(VirtualEngine.Gear.D, 0f, 0f);
        VirtualEngine.Input inSlow = input(VirtualEngine.Gear.D, 20f, 18f);
        VirtualEngine.Input inFast = input(VirtualEngine.Gear.D, 130f, 18f);
        stopped.ignite(inStopped);
        slow.ignite(inSlow);
        fast.ignite(inFast);
        run(stopped, inStopped, 3f);
        run(slow, inSlow, 3f);
        run(fast, inFast, 3f);
        assertEquals("点着那一下多冲的早淡掉了", 0f, stopped.outputDb(), 0.01f);
        float more = fast.outputDb() - slow.outputDb();
        assertTrue("高速比低速稍响: " + more, more > 1f && more < VirtualEngine.SPEED_GAIN_DB);
    }

    @Test
    public void parkAndNeutralRevFreelyUpToTheirLimit() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.P, 0f, 0f);
        e.ignite(in);
        run(e, in, 2.5f);
        in.throttlePct = 100f;
        in.pedalRate = 600f;
        run(e, in, 2f);
        assertTrue(e.rpm() > VirtualEngine.FREE_REV_MAX_RPM - 300f);
        assertTrue(e.rpm() <= VirtualEngine.FREE_REV_MAX_RPM);
        in.throttlePct = 0f;
        in.pedalRate = 0f;
        run(e, in, 3f);
        assertEquals("松开回怠速", P.idleRpm, e.rpm(), 100f);
    }

    @Test
    public void shiftsGoUpUnderLoadAndNeverUpWhileBraking() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 0f, 0f);
        in.mode = VirtualEngine.RpmMode.SHIFTS;
        e.ignite(in);
        run(e, in, 2.5f);
        in.throttlePct = 50f;
        in.flow = VirtualEngine.Flow.DRIVE;
        for (int i = 0; i < 2000; i++) {
            in.speedKmh = 120f * i / 2000f;
            e.step(DT, in);
        }
        int cruising = e.gearIndex();
        assertTrue("升过挡", cruising >= 2);
        // 重刹：120 → 20 km/h，减速 6 m/s²
        in.throttlePct = 0f;
        in.braking = true;
        in.flow = VirtualEngine.Flow.REGEN;
        in.accel = -6f;
        int highest = cruising;
        boolean blipped = false;
        for (int i = 0; i < 900; i++) {
            in.speedKmh = 120f - 100f * i / 900f;
            e.step(DT, in);
            assertTrue("刹车时不升挡", e.gearIndex() <= highest);
            highest = e.gearIndex();
            blipped |= e.voiceLoad() > e.load() + 0.1f;
        }
        assertTrue("一路降挡", e.gearIndex() < cruising);
        assertTrue("降挡带补油", blipped);
    }

    @Test
    public void continuousGoesThroughPseudoGearsOnlyNearTheFloor() {
        // 项目所有者 2026-10-21：连续走法全油门时也按挡位走锯齿；半油门还是连续的，松下来回到连续
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 0f, 0f);
        e.ignite(in);
        run(e, in, 2.5f);
        in.flow = VirtualEngine.Flow.DRIVE;
        in.throttlePct = 50f;
        for (int i = 0; i < 1000; i++) {
            in.speedKmh = 60f * i / 1000f;
            e.step(DT, in);
            assertFalse("半油门还是连续的", e.geared());
        }
        in.throttlePct = 100f;
        int serial = e.shiftSerial();
        float peak = 0f;
        for (int i = 0; i < 3000; i++) {
            in.speedKmh = 60f + 100f * i / 3000f;
            e.step(DT, in);
            assertTrue("踩到底按伪挡位走", e.pseudoGears() && e.geared());
            peak = Math.max(peak, e.rpm());
        }
        assertTrue("一路升挡", e.shiftSerial() - serial >= 2 && e.shiftUp());
        assertTrue("转到升挡点附近: " + peak, peak > VirtualEngine.upshiftRpm(P, 1f) - 200f);
        in.throttlePct = 40f;
        e.step(DT, in);
        assertFalse("松到半油门回到连续", e.geared());
        assertEquals(0, e.gearIndex());
    }

    @Test
    public void anUpshiftCutsTheDriveThenTheRpmFallsSmoothly() {
        // 升挡：一下子断扭（电平跌落由合成器按断扭自己加），转速先停住再只往下走到新挡位，断扭过去时「吼」一下，最后断扭归零
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 0f, 0f);
        in.mode = VirtualEngine.RpmMode.SHIFTS;
        e.ignite(in);
        run(e, in, 2.5f);
        in.throttlePct = 100f;
        in.flow = VirtualEngine.Flow.DRIVE;
        float v = 0f;
        int serial = e.shiftSerial();
        for (int i = 0; i < 5000 && e.shiftSerial() == serial; i++) {
            v += 0.02f;
            in.speedKmh = v;
            e.step(DT, in);
        }
        assertTrue("升挡了", e.shiftSerial() == serial + 1 && e.shiftUp());
        float from = e.rpm();
        assertEquals("断扭一下子到底", 1f, e.cut(), 0.05f);
        float last = from;
        float bark = 0f;
        int steps = (int) (VirtualEngine.UPSHIFT_SEC / DT);
        for (int k = 0; k < steps; k++) {
            v += 0.02f;
            in.speedKmh = v;
            e.step(DT, in);
            assertTrue("转速只往下走（新挡位跟着车速的那一点除外）", e.rpm() <= last + 5f);
            last = e.rpm();
            bark = Math.max(bark, e.brightDb());
        }
        assertTrue("落到新挡位: " + from + " → " + e.rpm(), e.rpm() < from - 800f);
        assertTrue("动力回来时吼一下: " + bark, bark > 2f);
        run(e, in, 0.02f);
        assertEquals("断扭过去了", 0f, e.cut(), 0f);
    }

    @Test
    public void aStabKicksDownSeveralGearsAtOnce() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 90f, 0f);
        in.mode = VirtualEngine.RpmMode.SHIFTS;
        in.braking = true;
        e.ignite(in);
        in.braking = false;
        in.throttlePct = 15f;
        in.flow = VirtualEngine.Flow.DRIVE;
        run(e, in, 6f);
        int before = e.gearIndex();
        in.throttlePct = 100f;
        in.pedalRate = 1000f;
        e.step(DT, in);
        e.step(DT, in);
        assertTrue("一脚到底连降几挡: " + before + " -> " + e.gearIndex(), e.gearIndex() <= before - 2);
    }

    /** 慢慢踩到地板附近也算一脚到底（像自动挡的强制降挡开关）：降到此刻车速下该在的挡，不只降一挡。 */
    @Test
    public void flooringItSlowlyStillKicksDown() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 90f, 0f);
        in.mode = VirtualEngine.RpmMode.SHIFTS;
        in.braking = true;
        e.ignite(in);
        in.braking = false;
        in.throttlePct = 15f;
        in.flow = VirtualEngine.Flow.DRIVE;
        run(e, in, 6f);
        int before = e.gearIndex();
        // 每秒多踩 34 %，2.5 秒从 15 % 踩到 100 %：慢，不算急踩
        in.pedalRate = 34f;
        for (int i = 0; i < Math.round(2.5f / DT); i++) {
            in.throttlePct = Math.min(100f, in.throttlePct + 34f * DT);
            e.step(DT, in);
        }
        in.pedalRate = 0f;
        run(e, in, 0.3f);
        assertEquals("踩到底降到该在的挡", VirtualEngine.pickGear(P, 90f, 1f), e.gearIndex());
        assertTrue("不只降一挡: " + before + " -> " + e.gearIndex(), e.gearIndex() <= before - 2);
    }

    @Test
    public void aStabSoundsDifferentFromASlowPress() {
        VirtualEngine stab = new VirtualEngine(P);
        VirtualEngine slow = new VirtualEngine(P);
        VirtualEngine.Input a = input(VirtualEngine.Gear.D, 50f, 15f);
        VirtualEngine.Input b = input(VirtualEngine.Gear.D, 50f, 15f);
        a.braking = true;
        b.braking = true;
        stab.ignite(a);
        slow.ignite(b);
        a.braking = false;
        b.braking = false;
        run(stab, a, 3f);
        run(slow, b, 3f);
        // 急踩：一下踩到 70 %；缓踩：40 %/s 往上加
        a.throttlePct = 70f;
        a.pedalRate = 800f;
        run(stab, a, 0.05f);
        a.pedalRate = 0f;
        run(stab, a, 0.25f);
        for (int i = 0; i < Math.round(0.3f / DT); i++) {
            b.throttlePct = Math.min(70f, b.throttlePct + 40f * DT);
            b.pedalRate = 40f;
            slow.step(DT, b);
        }
        assertTrue("急踩转速冲得更高", stab.rpm() > slow.rpm() + 500f);
        assertTrue("急踩更响", stab.levelDb() > slow.levelDb() + 3f);
        assertTrue("急踩更亮、进气声更猛", stab.brightDb() > 1f && stab.intakeBoostDb() > 2f);
        assertEquals("缓踩没有这些", 0f, slow.intakeBoostDb(), 0.01f);
    }

    @Test
    public void aQuickLiftBurblesOnlyWhenMoving() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 60f, 0f);
        in.pedalRate = -800f;
        in.braking = true;
        e.ignite(in);
        in.braking = false;
        e.step(DT, in);
        assertTrue(e.burble() > 0.5f);
        in.pedalRate = 0f;
        run(e, in, 3f);
        assertTrue("几百毫秒淡掉", e.burble() < 0.05f);
        VirtualEngine slow = new VirtualEngine(P);
        VirtualEngine.Input crawl = input(VirtualEngine.Gear.D, 8f, 0f);
        crawl.braking = true;
        slow.ignite(crawl);
        crawl.braking = false;
        crawl.pedalRate = -800f;
        slow.step(DT, crawl);
        assertEquals("低速没有突突", 0f, slow.burble(), 0f);
    }

    @Test
    public void coastingInGearIsOverrunButIdlingIsNot() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 60f, 30f);
        in.mode = VirtualEngine.RpmMode.SHIFTS;
        in.braking = true;
        e.ignite(in);
        in.braking = false;
        run(e, in, 2f);
        assertEquals("踩着油门不是收油", 0f, e.overrun(), 0.01f);
        in.throttlePct = 0f;
        in.flow = VirtualEngine.Flow.REGEN;
        run(e, in, 1.5f);
        assertTrue("挂着挡滑行：发动机被车拖着转 " + e.overrun() + " @ " + e.rpm(), e.overrun() > 0.8f);
        in.speedKmh = 0f;
        in.flow = VirtualEngine.Flow.IDLE;
        run(e, in, 3f);
        assertEquals("停下来怠速不算", 0f, e.overrun(), 0.02f);
    }

    @Test
    public void aFreeRevFallingBackIsOverrunAtOnce() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.P, 0f, 0f);
        e.ignite(in);
        run(e, in, 2.5f);
        assertEquals("启动、怠速都不是收油", 0f, e.overrun(), 0f);
        in.throttlePct = 80f;
        run(e, in, 1.5f);
        assertEquals(0f, e.overrun(), 0.01f);
        in.throttlePct = 0f;
        run(e, in, 0.3f);
        assertTrue("一松油门就断油（不等负荷慢慢落）" + e.overrun(), e.overrun() > 0.5f);
        run(e, in, 3f);
        assertEquals("回到怠速", 0f, e.overrun(), 0.02f);
    }

    @Test
    public void startingIsNeverOverrun() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.P, 0f, 0f);
        e.ignite(in);
        for (int i = 0; i < Math.round(2.2f / DT); i++) {
            e.step(DT, in);
            if (e.phase() == VirtualEngine.Phase.STARTING) {
                assertEquals("冲高后回落不是断油", 0f, e.overrun(), 0f);
            }
        }
    }

    @Test
    public void adaptiveCruiseAccelerationIsHeard() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 80f, 0f);
        in.braking = true;
        e.ignite(in);
        in.braking = false;
        in.flow = VirtualEngine.Flow.DRIVE;
        in.accel = 2.5f;
        run(e, in, 1f);
        assertEquals(0.4f, e.load(), 0.02f);
        in.flow = VirtualEngine.Flow.IDLE;
        run(e, in, 2f);
        assertTrue(e.load() < 0.02f);
    }

    @Test
    public void timingDoesNotDependOnTheBlockSize() {
        VirtualEngine coarse = new VirtualEngine(P);
        VirtualEngine fine = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 40f, 0f);
        in.braking = true;
        coarse.ignite(in);
        fine.ignite(in);
        in.braking = false;
        in.throttlePct = 60f;
        for (int i = 0; i < 60; i++) {
            coarse.step(DT, in);
            fine.step(DT / 2f, in);
            fine.step(DT / 2f, in);
        }
        assertEquals(coarse.load(), fine.load(), 0.01f);
        assertEquals(coarse.rpm(), fine.rpm(), coarse.rpm() * 0.02f);
    }

    @Test
    public void theLevelStaysInASaneRange() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 50f, 0f);
        e.ignite(in);
        // 先让电机带起来、点着（带起来那一段转速本来就在怠速以下）
        run(e, in, 0.3f);
        long seed = 12345L;
        for (int i = 0; i < 20_000; i++) {
            seed = seed * 6364136223846793005L + 1442695040888963407L;
            int r = (int) (seed >>> 33);
            if (i % 50 == 0) {
                in.throttlePct = (r & 127) % 101;
                in.speedKmh = ((r >> 7) & 255) % 181;
                in.pedalRate = ((r >> 15) & 1023) - 512;
                in.accel = (((r >> 25) & 63) - 32) / 4f;
                in.braking = (r & 0x40000) != 0;
                in.gear = VirtualEngine.Gear.values()[((r >> 3) & 7) % 4];
                in.mode = (r & 1) == 0 ? VirtualEngine.RpmMode.CONTINUOUS : VirtualEngine.RpmMode.SHIFTS;
                in.flow = VirtualEngine.Flow.values()[((r >> 5) & 7) % 5];
            }
            e.step(DT, in);
            assertTrue("电平 " + e.levelDb(), e.levelDb() > -36f && e.levelDb() < 10f);
            assertTrue("转速 " + e.rpm(), e.rpm() >= P.idleRpm && e.rpm() <= P.maxRpm);
            assertFalse(Float.isNaN(e.rpm()) || Float.isNaN(e.load()));
        }
    }
}
