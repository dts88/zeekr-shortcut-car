package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.app.usage.UsageEvents;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link CameraHolderSuspects} 的纯规则：窗口里哪些事件算嫌疑、怎么排、黑匣子那一行怎么写、汇总怎么数。
 *
 * <p>那一行只是线索，可一旦列错（窗口外的、我们自己的、一个应用来回切刷满一行），
 * 看日志的人就会被带偏。所以把规则钉在这里。</p>
 */
public class CameraHolderSuspectsTest {

    private static final long AT = 1_760_000_000_000L;
    private static final String OWN = "io.github.dts88.zeekrshortcut";
    private static final int FGS_START = UsageEvents.Event.FOREGROUND_SERVICE_START;
    private static final int FGS_STOP = UsageEvents.Event.FOREGROUND_SERVICE_STOP;
    private static final int RESUMED = UsageEvents.Event.ACTIVITY_RESUMED;
    private static final int PAUSED = UsageEvents.Event.ACTIVITY_PAUSED;
    /** 黑匣子里种类的叫法，和记黑匣子的那一句一致。 */
    private static final String[] NAMES = {"前台服务启动", "前台服务停止", "切到前台", "切到后台"};

    private static CameraHolderSuspects.Event event(String pkg, int type, long offsetMs) {
        return new CameraHolderSuspects.Event(pkg, type, AT + offsetMs);
    }

    @Test
    public void mapsTheFourEventKinds() {
        assertEquals(CameraHolderSuspects.FGS_START, CameraHolderSuspects.kindOf(FGS_START));
        assertEquals(CameraHolderSuspects.FGS_STOP, CameraHolderSuspects.kindOf(FGS_STOP));
        assertEquals(CameraHolderSuspects.TO_FOREGROUND, CameraHolderSuspects.kindOf(RESUMED));
        assertEquals(CameraHolderSuspects.TO_BACKGROUND, CameraHolderSuspects.kindOf(PAUSED));
        assertEquals(-1, CameraHolderSuspects.kindOf(UsageEvents.Event.CONFIGURATION_CHANGE));
        assertEquals(-1, CameraHolderSuspects.kindOf(UsageEvents.Event.ACTIVITY_STOPPED));
    }

    /** 窗口含两端：之前 10 秒、之后 3 秒；出了窗口的、我们自己的、不关心的种类都不算。 */
    @Test
    public void keepsOnlyOtherAppsInsideTheWindow() {
        List<CameraHolderSuspects.Event> events = Arrays.asList(
                event("com.too.early", FGS_START, -CameraHolderSuspects.BEFORE_MS - 1),
                event("com.edge.before", FGS_START, -CameraHolderSuspects.BEFORE_MS),
                event("com.edge.after", RESUMED, CameraHolderSuspects.AFTER_MS),
                event("com.too.late", RESUMED, CameraHolderSuspects.AFTER_MS + 1),
                event(OWN, FGS_START, -100),
                event("com.config", UsageEvents.Event.CONFIGURATION_CHANGE, 0));

        List<CameraHolderSuspects.Suspect> found = CameraHolderSuspects.find(events, AT, OWN);

        assertEquals(2, found.size());
        assertEquals("com.edge.after", found.get(0).pkg);
        assertEquals(CameraHolderSuspects.AFTER_MS, found.get(0).offsetMs);
        assertEquals("com.edge.before", found.get(1).pkg);
        assertEquals(-CameraHolderSuspects.BEFORE_MS, found.get(1).offsetMs);
    }

    /** 离那一刻越近越靠前；一样近的，之前的先列。 */
    @Test
    public void ranksByClosenessBeforeFirstOnTies() {
        List<CameraHolderSuspects.Event> events = Arrays.asList(
                event("com.far", FGS_START, -8_000),
                event("com.after", RESUMED, 400),
                event("com.before", FGS_START, -400),
                event("com.near", FGS_START, -100));

        List<CameraHolderSuspects.Suspect> found = CameraHolderSuspects.find(events, AT, OWN);

        assertEquals(Arrays.asList("com.near", "com.before", "com.after", "com.far"), packages(found));
    }

    /** 一个应用来回切前后台：同一种只留最近的一条；不同种类各留一条。 */
    @Test
    public void samePackageAndKindKeepsTheClosest() {
        List<CameraHolderSuspects.Event> events = Arrays.asList(
                event("com.flap", RESUMED, -9_000),
                event("com.flap", PAUSED, -6_000),
                event("com.flap", RESUMED, -2_000),
                event("com.flap", PAUSED, 1_500),
                event("com.flap", FGS_START, -2_500));

        List<CameraHolderSuspects.Suspect> found = CameraHolderSuspects.find(events, AT, OWN);

        assertEquals(3, found.size());
        assertEquals(CameraHolderSuspects.TO_BACKGROUND, found.get(0).kind);
        assertEquals(1_500, found.get(0).offsetMs);
        assertEquals(CameraHolderSuspects.TO_FOREGROUND, found.get(1).kind);
        assertEquals(-2_000, found.get(1).offsetMs);
        assertEquals(CameraHolderSuspects.FGS_START, found.get(2).kind);
    }

    @Test
    public void nothingInTheWindowFindsNothing() {
        assertTrue(CameraHolderSuspects.find(Collections.emptyList(), AT, OWN).isEmpty());
    }

    /** 黑匣子那一行的写法：包名、种类、带正负号的秒，「；」隔开。 */
    @Test
    public void describesOneLine() {
        List<CameraHolderSuspects.Suspect> found = CameraHolderSuspects.find(Arrays.asList(
                event("com.x.y", FGS_START, -1_200),
                event("com.a.b", RESUMED, 400)), AT, OWN);

        assertEquals("com.a.b 切到前台 +0.4 s；com.x.y 前台服务启动 -1.2 s",
                CameraHolderSuspects.describe(found, NAMES));
    }

    /** 列满 {@link CameraHolderSuspects#MAX_LISTED} 条，多的只写个数。 */
    @Test
    public void listsAtMostSixAndCountsTheRest() {
        List<CameraHolderSuspects.Event> events = new ArrayList<>();
        for (int i = 1; i <= CameraHolderSuspects.MAX_LISTED + 2; i++) {
            events.add(event("com.app" + i, FGS_STOP, -i * 100L));
        }
        String line = CameraHolderSuspects.describe(CameraHolderSuspects.find(events, AT, OWN), NAMES);

        assertTrue(line, line.startsWith("com.app1 前台服务停止 -0.1 s；"));
        assertTrue(line, line.contains("com.app6 前台服务停止 -0.6 s"));
        assertTrue(line, !line.contains("com.app7"));
        assertTrue(line, line.endsWith("；…+2"));
    }

    @Test
    public void secondsCarryTheirSign() {
        assertEquals("-1.2 s", CameraHolderSuspects.seconds(-1_200));
        assertEquals("+0.4 s", CameraHolderSuspects.seconds(400));
        assertEquals("+0.0 s", CameraHolderSuspects.seconds(0));
        assertEquals("-10.0 s", CameraHolderSuspects.seconds(-10_000));
    }

    /** 汇总：一次查询里同一个应用只算一次；次数多的在前；应用个数有上限，超出的只计数。 */
    @Test
    public void tallyCountsOncePerLookupAndStaysBounded() {
        CameraHolderSuspects.Tally tally = new CameraHolderSuspects.Tally();
        tally.add(CameraHolderSuspects.find(Arrays.asList(
                event("com.b", FGS_START, -1_000),
                event("com.b", RESUMED, -900),
                event("com.a", RESUMED, 200)), AT, OWN));
        tally.add(CameraHolderSuspects.find(Collections.singletonList(
                event("com.b", FGS_STOP, 100)), AT, OWN));
        tally.add(Collections.emptyList());

        assertEquals(3, tally.lookups());
        List<Map.Entry<String, Integer>> ranked = tally.ranked();
        assertEquals("com.b", ranked.get(0).getKey());
        assertEquals(Integer.valueOf(2), ranked.get(0).getValue());
        assertEquals("com.a", ranked.get(1).getKey());
        assertEquals(Integer.valueOf(1), ranked.get(1).getValue());

        CameraHolderSuspects.Tally full = new CameraHolderSuspects.Tally();
        for (int i = 0; i < CameraHolderSuspects.MAX_PACKAGES_PER_CAMERA + 3; i++) {
            full.add(Collections.singletonList(
                    new CameraHolderSuspects.Suspect("com.p" + i, CameraHolderSuspects.FGS_START, 0)));
        }
        assertEquals(CameraHolderSuspects.MAX_PACKAGES_PER_CAMERA, full.ranked().size());
        assertEquals(3, full.dropped());
    }

    private static List<String> packages(List<CameraHolderSuspects.Suspect> found) {
        List<String> out = new ArrayList<>();
        for (CameraHolderSuspects.Suspect s : found) {
            out.add(s.pkg);
        }
        return out;
    }
}
