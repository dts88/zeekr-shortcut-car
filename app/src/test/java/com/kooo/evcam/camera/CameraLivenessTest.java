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

    /** 兜底的门槛必须明显宽于 SingleCamera 自己那套，否则两层会抢着动手。 */
    @Test
    public void thresholdStaysWellAboveTheInnerRecovery() {
        assertTrue("兜底门槛要留出会话重建的时间", CameraLiveness.STUCK_MS >= 6000L);
        assertTrue("两次重开之间要比门槛还长", CameraLiveness.RETRY_GAP_MS > CameraLiveness.STUCK_MS);
    }
}
