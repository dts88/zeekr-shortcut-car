package com.kooo.evcam.sound;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 车速还原（{@link SpeedTrack}）。算错的表现：转速跟着车速一级一级地跳；匀加速时加速度一跳一跳（像急加速、急刹车）；
 * 停下来以后还一直当在减速；车机更新得慢一点，台阶又回来了。
 */
public class SpeedTrackTest {

    /** 和声音线程一样的一块：256 帧 / 48 kHz。 */
    private static final float DT = 256f / 48_000f;

    /** 车机每 {@code updateMs} 刷新一次车速（真的车速按 {@code accel} m/s² 从 {@code v0} 变，不低于 0），每 20 ms 读一次。 */
    private static final class Drive {
        final SpeedTrack track = new SpeedTrack();
        final long updateMs;
        float shown;
        long lastUpdate = Long.MIN_VALUE;
        long nextPoll;
        double t;
        float maxStep;
        private float prev = Float.NaN;

        Drive(long updateMs) {
            this.updateMs = updateMs;
        }

        /** 跑 {@code seconds} 秒：车速 = speedAt(t)。 */
        void run(float seconds, java.util.function.DoubleUnaryOperator speedAt) {
            int blocks = Math.round(seconds / DT);
            for (int b = 0; b < blocks; b++) {
                t += DT;
                long now = Math.round(t * 1000.0);
                if (lastUpdate == Long.MIN_VALUE || now - lastUpdate >= updateMs) {
                    lastUpdate = now;
                    shown = (float) speedAt.applyAsDouble(t);
                }
                while (nextPoll <= now) {
                    track.sample(shown, nextPoll);
                    nextPoll += 20L;
                }
                track.step(DT, now);
                float v = track.speed();
                if (!Float.isNaN(prev)) {
                    maxStep = Math.max(maxStep, Math.abs(v - prev));
                }
                prev = v;
            }
        }
    }

    @Test
    public void aStaircaseBecomesASmoothRampAndASteadyAcceleration() {
        Drive d = new Drive(200L);
        d.run(3f, t -> 2.5 * 3.6 * t);
        assertEquals("匀加速：加速度稳在 2.5 附近", 2.5f, d.track.accel(), 0.3f);
        assertTrue("车速不再一级一级地跳: " + d.maxStep, d.maxStep < 0.4f);
        assertEquals("车速跟得上真的", 2.5f * 3.6f * 3f, d.track.speed(), 1.5f);
        float minAccel = Float.MAX_VALUE;
        float maxAccel = -Float.MAX_VALUE;
        for (int i = 0; i < 300; i++) {
            d.run(DT, t -> 2.5 * 3.6 * t);
            minAccel = Math.min(minAccel, d.track.accel());
            maxAccel = Math.max(maxAccel, d.track.accel());
        }
        assertTrue("不一跳一跳: " + minAccel + "…" + maxAccel, maxAccel - minAccel < 0.4f);
    }

    @Test
    public void aSteadySpeedHasNoAcceleration() {
        Drive d = new Drive(200L);
        d.run(2f, t -> 60.0);
        assertEquals(60f, d.track.speed(), 1e-3f);
        assertEquals(0f, d.track.accel(), 1e-3f);
    }

    @Test
    public void stoppingEndsAtZeroAndTheDecelerationFades() {
        Drive d = new Drive(200L);
        d.run(1f, t -> 36.0);
        // 4 m/s² 刹停：2.5 s 停住
        double from = d.t;
        float lowest = Float.MAX_VALUE;
        for (int i = 0; i < Math.round(2.5f / DT); i++) {
            d.run(DT, t -> Math.max(0.0, 36.0 - 4.0 * 3.6 * (t - from)));
            lowest = Math.min(lowest, d.track.speed());
        }
        assertTrue("刹车时减速度出来了: " + d.track.accel(), d.track.accel() < -3f);
        d.run(1.5f, t -> 0.0);
        assertTrue("车速不会是负的: " + lowest, lowest >= 0f);
        assertEquals(0f, d.track.speed(), 0.01f);
        assertEquals("停住以后不再当在减速", 0f, d.track.accel(), 0.1f);
    }

    @Test
    public void readingsThatOnlyComeOnChangeStillLetTheAccelerationFall() {
        // 只在变了的时候推来：加速到 36 以后一直不来了
        SpeedTrack s = new SpeedTrack();
        long now = 0L;
        for (int k = 0; k <= 20; k++) {
            s.sample(1.8f * k, 200L * k);
            for (int i = 0; i < 37; i++) {
                now = 200L * k + Math.round(i * DT * 1000f);
                s.step(DT, now);
            }
        }
        assertEquals(2.5f, s.accel(), 0.3f);
        for (int i = 0; i < Math.round(2f / DT); i++) {
            now += Math.round(DT * 1000f);
            s.step(DT, now);
        }
        assertEquals("读数不来了：车速停在最后那个值附近", 36f, s.speed(), 0.5f);
        assertEquals("加速度回到 0", 0f, s.accel(), 0.1f);
    }

    @Test
    public void slowUpdatesStretchTheHoldInsteadOfBringingBackTheSteps() {
        Drive d = new Drive(500L);
        d.run(5f, t -> 1.5 * 3.6 * t);
        assertEquals("量出来车机 0.5 s 才更新一次", 500f, d.track.everyMs(), 60f);
        float minAccel = Float.MAX_VALUE;
        float maxAccel = -Float.MAX_VALUE;
        for (int i = 0; i < 400; i++) {
            d.run(DT, t -> 1.5 * 3.6 * t);
            minAccel = Math.min(minAccel, d.track.accel());
            maxAccel = Math.max(maxAccel, d.track.accel());
        }
        assertTrue("没当成一会儿加速一会儿不加速: " + minAccel + "…" + maxAccel, minAccel > 0.8f && maxAccel < 2.2f);
    }

    @Test
    public void missingReadingsGiveNaNAndStartOver() {
        SpeedTrack s = new SpeedTrack();
        s.sample(50f, 0L);
        s.step(DT, 5L);
        s.sample(Float.NaN, 20L);
        s.step(DT, 25L);
        assertTrue(Float.isNaN(s.speed()));
        assertEquals(0f, s.accel(), 0f);
        s.sample(-7f, 40L);
        s.step(DT, 45L);
        assertEquals("倒车也按快慢算", 7f, s.speed(), 0f);
    }
}
