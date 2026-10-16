package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * 每一台相机的来历（项目所有者 2026-10-15 批准，2.11.5 第 D1、D3 项）：车机重新初始化以来开过几台、眼下那一台是第几台；
 * 退出、装新版不清（存盘、读回来），车机重启、开机时长从头来过才清；读回来之前记下的并进去；最后一次关跨进程留着。
 */
public class CameraHistoryTest {

    private static final long WALL = 1_760_000_000_000L;
    private static final long ELAPSED = 5_000_000L;

    /** 10-14 21:01:55 相机 2 回来了，之后一共开了 5 台（含一次救援重开）：第 5 台。重置以后再开从 1 数，之前那一台的编号不变。 */
    @Test
    public void countsDevicesSinceTheLastReset() {
        CameraHistory h = new CameraHistory();
        assertNull("从没见过它", h.camera("2"));
        h.opened("2", WALL);
        CameraHistory.Camera c = h.camera("2");
        assertEquals(1, c.opens);
        assertNull("记录开始以来没见过重置：从记录开始数", c.reset);
        assertEquals(WALL, c.sinceWall);

        h.reset("2", CameraHistory.Reset.CAME_BACK, WALL + 1_000, ELAPSED + 1_000);
        for (int i = 0; i < 5; i++) {
            assertEquals(i + 1, h.opened("2", WALL + 2_000 + i));
        }
        c = h.camera("2");
        assertEquals(5, c.opens);
        assertEquals(5, c.lastOrdinal);
        assertEquals(CameraHistory.Reset.CAME_BACK, c.lastReset);
        assertEquals(WALL + 1_000, c.lastResetWall);
        assertEquals(ELAPSED + 1_000, c.resetElapsed);

        // 车机醒来时自己用了 1 秒：从头数；开着的那一台（之前开的）编号不变
        h.reset("2", CameraHistory.Reset.TAKEN, WALL + 9_000, ELAPSED + 9_000);
        c = h.camera("2");
        assertEquals(0, c.opens);
        assertEquals(5, c.lastOrdinal);
        assertEquals(CameraHistory.Reset.CAME_BACK, c.lastReset);
        assertEquals(1, h.opened("2", WALL + 10_000));
        assertEquals(CameraHistory.Reset.TAKEN, h.camera("2").lastReset);
    }

    /** 退出、装新版：存下、读回来，接着数；最后一次关跨进程留着（「离上次关」）。 */
    @Test
    public void survivesAProcessRestartInTheSameBoot() {
        CameraHistory before = new CameraHistory();
        before.reset("2", CameraHistory.Reset.CAME_BACK, WALL, ELAPSED);
        before.opened("2", WALL + 1);
        before.opened("2", WALL + 2);
        before.closed("2", WALL + 3, ELAPSED + 60_000, 125, "exit");
        before.closed("1", WALL + 4, ELAPSED + 60_100, 7_822, "dark release");
        String text = before.encode(ELAPSED + 60_200);

        CameraHistory after = new CameraHistory();
        after.restore(text, false, WALL + 100_000, ELAPSED + 100_000);
        assertTrue(after.restored());
        CameraHistory.Camera c = after.camera("2");
        assertEquals(2, c.opens);
        assertEquals(CameraHistory.Reset.CAME_BACK, c.reset);
        assertEquals(WALL, c.resetWall);
        assertEquals(ELAPSED, c.resetElapsed);
        assertEquals(ELAPSED + 60_000, c.closedElapsed);
        assertEquals(125, c.closeMs);
        assertEquals("exit", c.closeWhy);
        assertEquals("空格换成下划线", "dark_release", after.camera("1").closeWhy);
        assertEquals("开机以来没变：照旧接着数", 3, after.opened("2", WALL + 100_001));
        assertEquals(Arrays.asList("2", "1"), after.ids());
        assertEquals("存了再读一样", after.encode(1), roundTrip(after).encode(1));
    }

    /** 车机重启过：每一台从开机那一刻重新数，上一次开机里的关不算；之后第一次见到的相机也从开机数。 */
    @Test
    public void aRebootStartsEveryCameraOver() {
        CameraHistory before = new CameraHistory();
        before.reset("2", CameraHistory.Reset.TAKEN, WALL, ELAPSED);
        before.opened("2", WALL + 1);
        before.closed("2", WALL + 2, ELAPSED + 10, 100, "exit");
        String text = before.encode(ELAPSED + 20);

        CameraHistory after = new CameraHistory();
        long bootWall = WALL + 600_000;
        after.restore(text, true, bootWall + 40_000, 40_000);
        CameraHistory.Camera c = after.camera("2");
        assertEquals(CameraHistory.Reset.BOOT, c.reset);
        assertEquals(bootWall, c.resetWall);
        assertEquals(0, c.resetElapsed);
        assertEquals(0, c.opens);
        assertEquals("上一次开机里的关不算", -1, c.closedElapsed);
        assertEquals(-1, c.closeMs);
        after.opened("0", bootWall + 41_000);
        assertEquals("开机以后才第一次见到的也从开机数", CameraHistory.Reset.BOOT, after.camera("0").reset);
        assertEquals(CameraHistory.Reset.BOOT, after.camera("0").lastReset);

        // 同一次开机里的下一个进程：没再重启，之后第一次见到的相机照旧从开机数
        CameraHistory next = new CameraHistory();
        next.restore(after.encode(50_000), false, bootWall + 60_000, 60_000);
        next.opened("1", bootWall + 61_000);
        assertEquals(CameraHistory.Reset.BOOT, next.camera("1").reset);
        assertEquals(bootWall, next.camera("1").resetWall);
    }

    /** 开机时长比存的时候还短：从头来过了，算车机重启（黑匣子没判出来也一样）。 */
    @Test
    public void elapsedGoingBackwardsIsAReboot() {
        CameraHistory before = new CameraHistory();
        before.reset("2", CameraHistory.Reset.CAME_BACK, WALL, ELAPSED);
        before.opened("2", WALL + 1);
        CameraHistory after = new CameraHistory();
        after.restore(before.encode(ELAPSED + 5), false, WALL + 9_000_000, 30_000);
        assertEquals(CameraHistory.Reset.BOOT, after.camera("2").reset);
        assertEquals(0, after.camera("2").opens);
    }

    /** 读回来之前（进程刚起来那几毫秒）记下的并进去：只开过的接在后面；重置过的照它。 */
    @Test
    public void whatHappenedBeforeTheRestoreIsMergedIn() {
        CameraHistory before = new CameraHistory();
        before.reset("2", CameraHistory.Reset.CAME_BACK, WALL, ELAPSED);
        for (int i = 0; i < 4; i++) {
            before.opened("2", WALL + i);
        }
        before.reset("0", CameraHistory.Reset.TAKEN, WALL, ELAPSED);
        before.opened("0", WALL + 5);
        String text = before.encode(ELAPSED + 100);

        CameraHistory after = new CameraHistory();
        after.opened("2", WALL + 200);
        after.closed("2", WALL + 201, ELAPSED + 201, 90, "unwanted");
        after.reset("0", CameraHistory.Reset.TAKEN, WALL + 300, ELAPSED + 300);
        after.restore(text, false, WALL + 400, ELAPSED + 400);
        CameraHistory.Camera surround = after.camera("2");
        assertEquals("存下的 4 台之后又开了 1 台", 5, surround.opens);
        assertEquals(5, surround.lastOrdinal);
        assertEquals(CameraHistory.Reset.CAME_BACK, surround.lastReset);
        assertEquals(ELAPSED + 201, surround.closedElapsed);
        CameraHistory.Camera front = after.camera("0");
        assertEquals("读回来之前重置过：存下的台数不算", 0, front.opens);
        assertEquals(WALL + 300, front.resetWall);
        assertEquals("之前开的那一台编号不变", 1, front.lastOrdinal);
    }

    /** 读不懂的跳过：别的版本、坏了的数、认不出的名字只扔那一台。 */
    @Test
    public void unreadableTextIsSkipped() {
        CameraHistory h = new CameraHistory();
        h.restore("h9 e=1\nc=2 n=3\n", false, WALL, ELAPSED);
        assertNull("不是这一版：整份不要", h.camera("2"));
        h = new CameraHistory();
        h.restore("h1 e=100\nc=2 n=x\nc=1 n=2 r=TELEPORT\nc=0 n=4 r=TAKEN future=1\ngarbage\n", false, WALL, ELAPSED);
        assertNull(h.camera("2"));
        assertNull(h.camera("1"));
        assertEquals(4, h.camera("0").opens);
        h = new CameraHistory();
        h.restore(null, false, WALL, ELAPSED);
        assertTrue(h.restored());
        assertFalse(h.ids().contains("2"));
        assertEquals(0, h.opened(null, WALL));
    }

    private static CameraHistory roundTrip(CameraHistory h) {
        CameraHistory back = new CameraHistory();
        back.restore(h.encode(ELAPSED + 100_000), false, WALL + 200_000, ELAPSED + 200_000);
        return back;
    }
}
