package com.kooo.evcam.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 虚拟发动机（项目所有者 2026-10-21 定的走法，docs/local/drive-sound-plan.md §2、§9）。
 *
 * <p>算错的表现：地板油一路顶在红线上；急踩和缓踩听起来一样；松油门、刹车时乱升挡；倒挡升挡；
 * 停着切到运动模式没有启动声、或者启动声之后一直是起动机的声音；行驶中接入时突然冒出来、降的那一挡马上又升回去；
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
        assertEquals(1f, e.fade(), 0f);
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
    public void joiningWhileMovingFadesInLikeAnOpeningValve() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 60f, 25f);
        e.ignite(in);
        assertEquals(VirtualEngine.Phase.JOINING, e.phase());
        assertEquals(0f, e.fade(), 0f);
        assertEquals(VirtualEngine.JOIN_TONE_FROM_HZ, e.toneHz(), 0f);
        float steady = VirtualEngine.continuousRpm(P, 60f, VirtualEngine.demand(25f));
        assertTrue("连续走法补一点转速，表示接上了", e.rpm() > steady + 300f);
        run(e, in, 0.4f);
        assertTrue(e.fade() > 0.4f && e.fade() < 0.6f);
        assertTrue("阀门在打开", e.toneHz() > 2000f && e.toneHz() < VirtualEngine.TONE_OPEN_HZ);
        run(e, in, 0.45f);
        assertEquals(VirtualEngine.Phase.RUNNING, e.phase());
        assertEquals(1f, e.fade(), 0f);
        assertEquals(VirtualEngine.TONE_OPEN_HZ, e.toneHz(), 0f);
    }

    @Test
    public void joiningWhileBrakingOnlyFadesIn() {
        VirtualEngine e = new VirtualEngine(P);
        VirtualEngine.Input in = input(VirtualEngine.Gear.D, 60f, 0f);
        in.braking = true;
        e.ignite(in);
        assertEquals("不补转速", VirtualEngine.continuousRpm(P, 60f, 0f), e.rpm(), 1f);
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
    public void reverseIsSixDecibelsQuieter() {
        VirtualEngine r = new VirtualEngine(P);
        VirtualEngine n = new VirtualEngine(P);
        VirtualEngine.Input inR = input(VirtualEngine.Gear.R, 5f, 30f);
        VirtualEngine.Input inN = input(VirtualEngine.Gear.N, 5f, 30f);
        r.ignite(inR);
        n.ignite(inN);
        run(r, inR, 3f);
        run(n, inN, 3f);
        assertEquals(VirtualEngine.REVERSE_DB, r.levelDb() - n.levelDb(), 0.01f);
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
