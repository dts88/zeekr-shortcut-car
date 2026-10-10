package com.kooo.evcam.recording;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 什么时候可以自己开始录制。
 *
 * <p>每一条都对应一次真实的误开：回放看完切回来就开录、切个日夜模式就开录、
 * 按了停止之后过 30 秒又自己开起来。</p>
 */
public class RecordingIntentTest {

    private final RecordingIntent intent = new RecordingIntent();

    /** 开关关着，什么都不该发生。 */
    @Test
    public void nothingHappensWhenTheSettingIsOff() {
        assertFalse(intent.shouldAutoStart(false));
        intent.noteRecordingStarted();
        assertFalse(intent.shouldRestore(false));
    }

    @Test
    public void autoStartsOncePerLaunch() {
        assertTrue(intent.shouldAutoStart(true));
        intent.noteAutoStarted();
        assertFalse("一趟只自动开一次", intent.shouldAutoStart(true));
    }

    /**
     * 主界面重建（切日夜模式、换语言、关掉再打开）不是新的一趟。
     *
     * <p>以前「已经开过」这个标记是界面的字段，界面一换就归零，于是又开一次。</p>
     */
    @Test
    public void rebuildingTheScreenIsNotANewLaunch() {
        intent.noteAutoStarted();
        intent.noteRecordingStarted();
        // 界面重建：换成新的 Activity，但 RecordingIntent 是进程级的，还是这一份
        assertFalse(intent.shouldAutoStart(true));
    }

    /** 用户按了停止之后，这一趟里没有任何一条路可以再自动开起来。 */
    @Test
    public void nothingAutoStartsAfterTheUserPressedStop() {
        intent.noteAutoStarted();
        intent.noteRecordingStarted();
        intent.noteUserStopped();

        assertFalse("重建界面也不许再自动开", intent.shouldAutoStart(true));
        assertFalse("定时检查也不许接回去", intent.shouldRestore(true));
    }

    /** 用户又自己按了开始，「停过」就作废了 —— 之后意外停了还是该接回去。 */
    @Test
    public void startingAgainByHandClearsTheStopRecord() {
        intent.noteUserStopped();
        intent.noteUserStarted();
        intent.noteRecordingStarted();
        assertTrue(intent.shouldRestore(true));
    }

    /**
     * 从来没录起来过就别自己开。
     *
     * <p>「打开视频回放再切回主界面」就落在这里：这一趟一帧都没录过，
     * 没有任何东西需要「恢复」。</p>
     */
    @Test
    public void doesNotRestoreSomethingThatNeverRan() {
        assertFalse(intent.shouldRestore(true));
        intent.noteAutoStarted();
        assertFalse("自动开过但没录起来（比如没插 U 盘），也不该反复重试",
                intent.shouldRestore(true));
    }

    @Test
    public void restoresRecordingThatStoppedOnItsOwn() {
        intent.noteAutoStarted();
        intent.noteRecordingStarted();
        assertTrue(intent.shouldRestore(true));
    }

    @Test
    public void resetStartsAFreshLaunch() {
        intent.noteAutoStarted();
        intent.noteUserStopped();
        intent.reset();
        assertTrue(intent.shouldAutoStart(true));
        assertFalse(intent.stoppedByUser());
    }

    /** 内存里的一份「盘」：新实例从同一份里读回来，就是进程被杀又拉回来。数一下写了几次。 */
    private static final class MemoryStore implements RecordingIntent.Store {
        final java.util.Map<String, Boolean> saved = new java.util.HashMap<>();
        int writes;

        @Override
        public boolean get(String key, boolean fallback) {
            return saved.containsKey(key) ? saved.get(key) : fallback;
        }

        @Override
        public void put(java.util.Map<String, Boolean> values) {
            saved.putAll(values);
            writes++;
        }
    }

    /** 规格 1.2：进程被杀又拉回来，「这一趟」的选择还在 —— 新实例从同一份存储里读回来。 */
    @Test
    public void choicesSurviveANewInstanceThroughTheStore() {
        MemoryStore store = new MemoryStore();
        RecordingIntent first = new RecordingIntent();
        first.attach(store);
        first.noteRecordingStarted();
        first.noteUserStopped();

        RecordingIntent second = new RecordingIntent();
        second.attach(store);
        assertTrue(second.stoppedByUser());
        assertFalse(second.shouldRestore(true));
        assertFalse(second.shouldAutoStart(true));
    }

    /**
     * 「这一趟要录」被车机结束进程之后还在（2026-10-10：哨兵模式没开时熄屏停录、等亮屏接，熄屏 3–5 秒后进程被结束，
     * 以前「亮屏接着录」只记在内存里，新进程不接）。
     */
    @Test
    public void recordingWantedSurvivesTheProcessBeingKilled() {
        MemoryStore store = new MemoryStore();
        RecordingIntent first = new RecordingIntent();
        first.attach(store);
        assertFalse(first.recordingWanted());
        assertTrue("立起来算变了", first.noteRecordingWanted(true));

        RecordingIntent second = new RecordingIntent();
        second.attach(store);
        assertTrue(second.recordingWanted());
    }

    /** 立、撤只在变了的时候落盘：在录、在等、接回时一遍遍立，不该一遍遍写盘（写的是 commit，同步的）。 */
    @Test
    public void recordingWantedIsWrittenOnlyWhenItChanges() {
        MemoryStore store = new MemoryStore();
        intent.attach(store);
        assertTrue(intent.noteRecordingWanted(true));
        int writes = store.writes;
        assertFalse("没变", intent.noteRecordingWanted(true));
        assertEquals("没变就不写", writes, store.writes);
        assertTrue(intent.noteRecordingWanted(false));
        assertEquals(writes + 1, store.writes);
        assertFalse(store.saved.get("recordingWanted"));
    }

    /** 手动停止：这一趟里没有任何一条路可以再自动开起来 —— 进程被杀了也不接。 */
    @Test
    public void stoppingByHandDropsRecordingWanted() {
        MemoryStore store = new MemoryStore();
        intent.attach(store);
        intent.noteRecordingWanted(true);
        intent.noteUserStopped();
        assertFalse(intent.recordingWanted());

        RecordingIntent afterKill = new RecordingIntent();
        afterKill.attach(store);
        assertFalse(afterKill.recordingWanted());
    }

    /** 车机真正开机、人点开主界面、退出（reset）是新的一趟：被杀之前要录的不再接。 */
    @Test
    public void aFreshLaunchDropsRecordingWanted() {
        MemoryStore store = new MemoryStore();
        intent.attach(store);
        intent.noteRecordingWanted(true);
        intent.reset();
        assertFalse(intent.recordingWanted());

        RecordingIntent afterKill = new RecordingIntent();
        afterKill.attach(store);
        assertFalse(afterKill.recordingWanted());
    }

    /** 「这一趟要录」和别的选择互不牵连：立起来、撤掉，自动开过没有、人停过没有都不变。 */
    @Test
    public void recordingWantedLeavesTheOtherChoicesAlone() {
        intent.noteAutoStarted();
        intent.noteRecordingStarted();
        intent.noteRecordingWanted(true);
        intent.noteRecordingWanted(false);
        assertFalse(intent.shouldAutoStart(true));
        assertTrue(intent.shouldRestore(true));
        assertFalse(intent.stoppedByUser());
    }
}
