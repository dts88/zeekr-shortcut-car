package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 相机卡死之后该不该救、救几次停手。
 *
 * <p>这一层是兜底，动手的方式是<b>把相机整个重开</b> —— 判错了就是无缘无故打断一次录制。
 * 所以门槛、间隔、停手都在这里钉死。</p>
 */
public class CameraLivenessTest {

    private final CameraLiveness.State state = new CameraLiveness.State();

    @Test
    public void quietWhileFramesKeepComing() {
        assertEquals(CameraLiveness.Action.NONE,
                CameraLiveness.step(state, true, 0, 1000));
        assertEquals(CameraLiveness.Action.NONE,
                CameraLiveness.step(state, true, CameraLiveness.STUCK_MS - 1, 2000));
        assertEquals(0, state.attempts());
    }

    /** 没人在用这一路（后视镜贴边收起、没在录、预览也不在）就不该救。 */
    @Test
    public void quietWhenNobodyWantsFrames() {
        assertEquals(CameraLiveness.Action.NONE,
                CameraLiveness.step(state, false, 10 * CameraLiveness.STUCK_MS, 1000));
        assertEquals(0, state.attempts());
    }

    @Test
    public void resetsOnceTheFramesHaveBeenGoneLongEnough() {
        assertEquals(CameraLiveness.Action.RESET,
                CameraLiveness.step(state, true, CameraLiveness.STUCK_MS, 10_000));
        assertEquals(1, state.attempts());
    }

    /** 重开要花时间，紧接着的几次检查里帧还是没有 —— 不能因此连着重开。 */
    @Test
    public void waitsBetweenAttempts() {
        CameraLiveness.step(state, true, CameraLiveness.STUCK_MS, 10_000);
        assertEquals(CameraLiveness.Action.NONE,
                CameraLiveness.step(state, true, CameraLiveness.STUCK_MS, 11_000));
        assertEquals(CameraLiveness.Action.NONE,
                CameraLiveness.step(state, true,
                        CameraLiveness.STUCK_MS, 10_000 + CameraLiveness.RETRY_GAP_MS - 1));
        assertEquals(CameraLiveness.Action.RESET,
                CameraLiveness.step(state, true,
                        CameraLiveness.STUCK_MS, 10_000 + CameraLiveness.RETRY_GAP_MS));
        assertEquals(2, state.attempts());
    }

    @Test
    public void givesUpAfterThreeTriesThenTriesAgainLater() {
        long now = 10_000;
        for (int i = 1; i <= CameraLiveness.MAX_ATTEMPTS; i++) {
            assertEquals("第 " + i + " 次该重开", CameraLiveness.Action.RESET,
                    CameraLiveness.step(state, true, CameraLiveness.STUCK_MS, now));
            now += CameraLiveness.RETRY_GAP_MS;
        }
        assertEquals(CameraLiveness.Action.GIVE_UP,
                CameraLiveness.step(state, true, CameraLiveness.STUCK_MS, now));
        assertTrue(state.gaveUp());

        // 停手期间一声不吭，不再刷日志也不再打扰相机
        long gaveUpAt = now;
        assertEquals(CameraLiveness.Action.NONE,
                CameraLiveness.step(state, true, CameraLiveness.STUCK_MS, gaveUpAt + 1000));
        assertEquals(CameraLiveness.Action.NONE,
                CameraLiveness.step(state, true, CameraLiveness.STUCK_MS,
                        gaveUpAt + CameraLiveness.COOL_OFF_MS - 1));

        // 一分钟之后再来一轮 —— 人可能刚回到车上，占着相机的那个应用可能已经退了
        assertEquals(CameraLiveness.Action.RESET,
                CameraLiveness.step(state, true, CameraLiveness.STUCK_MS,
                        gaveUpAt + CameraLiveness.COOL_OFF_MS));
        assertFalse(state.gaveUp());
        assertEquals(1, state.attempts());
    }

    /** 救活了就该彻底忘掉之前的次数，下一次卡住重新从第一次算起。 */
    @Test
    public void forgetsEverythingOnceFramesComeBack() {
        CameraLiveness.step(state, true, CameraLiveness.STUCK_MS, 10_000);
        CameraLiveness.step(state, true, CameraLiveness.STUCK_MS,
                10_000 + CameraLiveness.RETRY_GAP_MS);
        assertEquals(2, state.attempts());

        assertEquals(CameraLiveness.Action.NONE,
                CameraLiveness.step(state, true, 0, 40_000));
        assertEquals(0, state.attempts());

        assertEquals(CameraLiveness.Action.RESET,
                CameraLiveness.step(state, true, CameraLiveness.STUCK_MS, 50_000));
        assertEquals(1, state.attempts());
    }

    /**
     * 几轮都没用就彻底停手，不再每分钟去捶一次。
     *
     * <p>相机服务里留了僵死的占用记录时（实车遇到过：车机自己的 360 还能用，
     * 我们这边怎么都打不开，重启车机才好），重开是救不回来的。
     * 没有这一条的话，一夜下来会去捶几百次。</p>
     */
    @Test
    public void stopsForGoodAfterAFewRounds() {
        long now = 10_000;
        for (int cycle = 1; cycle <= CameraLiveness.MAX_CYCLES; cycle++) {
            for (int i = 0; i < CameraLiveness.MAX_ATTEMPTS; i++) {
                CameraLiveness.step(state, true, CameraLiveness.STUCK_MS, now);
                now += CameraLiveness.RETRY_GAP_MS;
            }
            CameraLiveness.Action action =
                    CameraLiveness.step(state, true, CameraLiveness.STUCK_MS, now);
            if (cycle < CameraLiveness.MAX_CYCLES) {
                assertEquals("第 " + cycle + " 轮该只是歇一会儿",
                        CameraLiveness.Action.GIVE_UP, action);
                now += CameraLiveness.COOL_OFF_MS;
            } else {
                assertEquals("最后一轮该彻底停手", CameraLiveness.Action.STOP, action);
            }
        }
        assertTrue(state.stopped());

        // 停手之后就是彻底安静，等多久都不再试
        assertEquals(CameraLiveness.Action.NONE,
                CameraLiveness.step(state, true, CameraLiveness.STUCK_MS,
                        now + 10 * CameraLiveness.COOL_OFF_MS));

        // 但相机真活过来了就重新算 —— 重启车机之后不该还记着仇
        assertEquals(CameraLiveness.Action.NONE,
                CameraLiveness.step(state, true, 0, now + 11 * CameraLiveness.COOL_OFF_MS));
        assertFalse(state.stopped());
        assertEquals(0, state.cycles());
        assertEquals(CameraLiveness.Action.RESET,
                CameraLiveness.step(state, true, CameraLiveness.STUCK_MS,
                        now + 12 * CameraLiveness.COOL_OFF_MS));
    }

    /** 兜底的门槛必须明显宽于 SingleCamera 自己那套，否则两层会抢着动手。 */
    @Test
    public void thresholdStaysWellAboveTheInnerRecovery() {
        assertTrue("兜底门槛要留出会话重建的时间", CameraLiveness.STUCK_MS >= 6000L);
        assertTrue("两次重开之间要比门槛还长", CameraLiveness.RETRY_GAP_MS > CameraLiveness.STUCK_MS);
    }
}
