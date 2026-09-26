package com.kooo.evcam.blackbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 黑匣子导出时的截断。
 *
 * <p>这份时间线是用来推断「系统到底听没听我们的」的，所以<b>不能从半行开始</b> ——
 * 半行会让人把两条记录读成一条，而报告里最不该出现的就是「看起来像真的」的假象。</p>
 */
public class BlackBoxTest {

    private static final String LOG =
            "09-20 10:00:00  进程启动\n"
                    + "09-20 10:00:01  服务 onCreate\n"
                    + "09-20 10:01:00  心跳\n"
                    + "09-20 10:02:00  心跳\n";

    /** 刚轮换过：上一份接在前面，时间线不断。 */
    @Test
    public void theRotatedFileComesFirst() {
        assertEquals("a\nb\n", BlackBox.joined("a\n", "b\n"));
        assertEquals("上一份没以换行结尾也不能把两条粘成一行",
                "a\nb\n", BlackBox.joined("a", "b\n"));
        assertEquals("b\n", BlackBox.joined("", "b\n"));
        assertEquals("a\n", BlackBox.joined("a\n", ""));
    }

    @Test
    public void shortLogComesBackWhole() {
        assertEquals(LOG, BlackBox.tail(LOG, 10_000));
    }

    @Test
    public void keepsOnlyWholeLinesFromTheEnd() {
        String cut = BlackBox.tail(LOG, 40);
        assertTrue("要带一句说明，免得看的人以为这就是全部", cut.startsWith("…"));
        for (String line : cut.split("\n")) {
            assertTrue("每一行要么是说明，要么是完整的一条记录: " + line,
                    line.startsWith("…") || line.startsWith("09-20 "));
        }
        assertTrue("留下的该是最后那几条", cut.endsWith("10:02:00  心跳\n"));
    }

    /** 整个文件就一行（还没换行）时不能崩，也不能返回空。 */
    @Test
    public void survivesOneHugeLine() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            huge.append("0123456789");
        }
        String cut = BlackBox.tail(huge.toString(), 50);
        assertTrue(cut.startsWith("…"));
        assertTrue(cut.length() > 50);
    }

    @Test
    public void handlesNothing() {
        assertEquals("", BlackBox.tail(null, 100));
        assertEquals("abc", BlackBox.tail("abc", 0));
    }

    /** 广播 action 在汇总行里只写短名，否则一行放不下几个。 */
    @Test
    public void shortensBroadcastActions() {
        assertEquals("SCREEN_ON", BlackBox.shortAction("android.intent.action.SCREEN_ON"));
        assertEquals("ACTION_KEEP_ALIVE", BlackBox.shortAction("com.kooo.evcam.ACTION_KEEP_ALIVE"));
        assertEquals("plain", BlackBox.shortAction("plain"));
        assertEquals("null", BlackBox.shortAction(null));
    }
}
