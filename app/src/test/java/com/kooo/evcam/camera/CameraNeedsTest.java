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
        assertEquals("(none)", needs.describe());
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

    /** 拍照在后台也要开相机：只有它登记着，也算有人要；存完注销，没人了才关。 */
    @Test
    public void aPhotoAloneKeepsItOpenUntilItIsSaved() {
        needs.claim(CameraNeeds.Holder.PHOTO);
        assertTrue(needs.heldByAnyoneExcept(CameraNeeds.Holder.PREVIEW));
        needs.release(CameraNeeds.Holder.PHOTO);
        assertFalse(needs.heldByAnyone());
    }

    @Test
    public void recordingAloneKeepsItOpen() {
        needs.claim(CameraNeeds.Holder.RECORDING);
        assertTrue(needs.heldByAnyoneExcept(CameraNeeds.Holder.PREVIEW));
    }


    @Test
    public void describeListsEveryone() {
        needs.claim(CameraNeeds.Holder.PREVIEW);
        needs.claim(CameraNeeds.Holder.RECORDING);
        String text = needs.describe();
        assertTrue(text, text.contains("PREVIEW"));
        assertTrue(text, text.contains("RECORDING"));
    }

    // ================================================================= 车机要睡（2.11，阶段 3 加；阶段 6 由协调器写）

    /** 「车机要睡」是一项事实，不是谁要相机：写了、清了都不动登记表。 */
    @Test
    public void theHeadUnitSleepsFactIsNotAHolder() {
        assertFalse(needs.headUnitSleeps());
        needs.claim(CameraNeeds.Holder.RECORDING);
        needs.setHeadUnitSleeps(true);
        assertTrue(needs.headUnitSleeps());
        assertTrue("登记表不变", needs.isHeld(CameraNeeds.Holder.RECORDING));
        needs.setHeadUnitSleeps(false);
        assertFalse(needs.headUnitSleeps());
        assertTrue(needs.isHeld(CameraNeeds.Holder.RECORDING));
    }

    /** 变了才叫监听者（调度据此当场置「正在按次序关」）；再写一遍同样的不叫。 */
    @Test
    public void writingTheHeadUnitSleepsFactWakesTheListenerOnlyWhenItChanges() {
        final int[] calls = {0};
        needs.setListener(() -> calls[0]++);
        needs.setHeadUnitSleeps(true);
        assertEquals(1, calls[0]);
        needs.setHeadUnitSleeps(true);
        assertEquals("同样的再写一遍不叫", 1, calls[0]);
        needs.setHeadUnitSleeps(false);
        assertEquals("亮屏清掉也叫", 2, calls[0]);
    }

    /** 换车型时两份管理器并存：旧的那份晚 release，摘不掉新那份的监听。 */
    @Test
    public void clearingTheListenerOnlyClearsItsOwn() {
        final int[] older = {0};
        final int[] newer = {0};
        CameraNeeds.Listener old = () -> older[0]++;
        CameraNeeds.Listener fresh = () -> newer[0]++;
        needs.setListener(old);
        needs.setListener(fresh);
        needs.clearListener(old);
        needs.claim(CameraNeeds.Holder.PREVIEW);
        assertEquals("新那份还在听", 1, newer[0]);
        assertEquals(0, older[0]);
        needs.clearListener(fresh);
        needs.release(CameraNeeds.Holder.PREVIEW);
        assertEquals("摘掉之后不再叫", 1, newer[0]);
    }
}
