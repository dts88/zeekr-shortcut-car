package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「还有人要相机吗」。
 *
 * <p>答错一边是相机整夜开着（耗电发热），答错另一边是画面当场没了。
 * 所以每一条都钉住。</p>
 */
public class CameraNeedsTest {

    private final CameraNeeds needs = new CameraNeeds();

    @Test
    public void nobodyWantsItToBeginWith() {
        assertFalse(needs.heldByAnyone());
        assertEquals("没人要", needs.describe());
    }

    @Test
    public void oneClaimIsEnoughToKeepItOpen() {
        needs.claim(CameraNeeds.Holder.MIRROR);
        assertTrue(needs.heldByAnyone());
        needs.release(CameraNeeds.Holder.MIRROR);
        assertFalse(needs.heldByAnyone());
    }

    /** 重复登记只算一次，所以不必配对计数 —— 少一次注销不会把相机永远留着。 */
    @Test
    public void claimingTwiceStillReleasesOnce() {
        needs.claim(CameraNeeds.Holder.PREVIEW);
        needs.claim(CameraNeeds.Holder.PREVIEW);
        needs.release(CameraNeeds.Holder.PREVIEW);
        assertFalse(needs.heldByAnyone());
    }

    @Test
    public void releasingSomethingNeverClaimedIsHarmless() {
        needs.release(CameraNeeds.Holder.RECORDING);
        assertFalse(needs.heldByAnyone());
    }

    /**
     * 主界面退到后台时问的正是这一句：除了预览，还有谁要。
     *
     * <p>以前这里是四个分支各判各的，而熄屏那条路<b>根本不看后视镜</b> ——
     * 于是关掉相机，两秒后后视镜的看门狗又把它打开。</p>
     */
    @Test
    public void backgroundingTheScreenLeavesTheMirrorHolding() {
        needs.claim(CameraNeeds.Holder.PREVIEW);
        needs.claim(CameraNeeds.Holder.MIRROR);

        assertTrue("后视镜还要用，不能关", needs.heldByAnyoneExcept(CameraNeeds.Holder.PREVIEW));

        needs.release(CameraNeeds.Holder.MIRROR);
        assertFalse("后视镜收起来了，这时候才该关",
                needs.heldByAnyoneExcept(CameraNeeds.Holder.PREVIEW));
    }

    @Test
    public void recordingAloneKeepsItOpen() {
        needs.claim(CameraNeeds.Holder.RECORDING);
        assertTrue(needs.heldByAnyoneExcept(CameraNeeds.Holder.PREVIEW));
    }

    /** 补盲那几个窗口不走登记，是现问现答 —— 它们的开关由别处管着。 */
    @Test
    public void overlayWindowsAreAskedNotRegistered() {
        final boolean[] active = {true};
        needs.setOverlayProbe(() -> active[0]);

        assertTrue(needs.heldByAnyone());
        assertTrue(needs.heldByAnyoneExcept(CameraNeeds.Holder.PREVIEW));
        assertEquals("OVERLAY", needs.describe());

        active[0] = false;
        assertFalse(needs.heldByAnyone());
    }

    /** 问悬浮窗时炸了不能拖累整个判断 —— 宁可当成「没人要」，也不要卡在那里。 */
    @Test
    public void aBrokenProbeDoesNotBreakTheAnswer() {
        needs.setOverlayProbe(() -> {
            throw new IllegalStateException("服务没了");
        });
        assertFalse(needs.heldByAnyone());

        needs.claim(CameraNeeds.Holder.RECORDING);
        assertTrue("登记过的还是算数", needs.heldByAnyone());
    }

    @Test
    public void describeListsEveryone() {
        needs.claim(CameraNeeds.Holder.PREVIEW);
        needs.claim(CameraNeeds.Holder.RECORDING);
        String text = needs.describe();
        assertTrue(text, text.contains("PREVIEW"));
        assertTrue(text, text.contains("RECORDING"));
    }
}
