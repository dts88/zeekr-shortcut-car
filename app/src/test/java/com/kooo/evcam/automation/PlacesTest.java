/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** 地点：存取（{@link PlaceStore}）、在不在（{@link Whereabouts}）、误差（{@link PositionTrack}）、删了地点规则怎么改。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class PlacesTest {

    private static final Place HOME = Place.of("home", "家", 10d, 20d, 200);
    private static final Place WORK = Place.of("work", "公司", 10.02d, 20d, 600);

    // ================================================================= 存取

    @Test
    public void placesSurviveARoundTrip() {
        Place odd = Place.of("odd", "<b>%s</b>\n很长的名字很长的名字很长的名字很长的名字很长的名字", -33.8688d, 151.2093d, 350);
        List<Place> back = PlaceStore.parse(PlaceStore.format(Arrays.asList(HOME, WORK, odd)));
        assertEquals(Arrays.asList(HOME, WORK, odd), back);
        assertFalse("名称洗过：没有换行", back.get(2).name.contains("\n"));
        assertFalse(back.get(2).stepRadius());
        assertTrue("200 m 是分段里的一档", back.get(0).stepRadius());
    }

    @Test
    public void unreadablePlacesAreDropped() {
        String json = "[{\"id\":\"a\",\"name\":\"ok\",\"lat\":1,\"lon\":2,\"r\":600},"
                + "{\"id\":\"b\",\"lat\":91,\"lon\":2,\"r\":600},"
                + "{\"id\":\"c\",\"lat\":1,\"lon\":181,\"r\":600},"
                + "{\"id\":\"d\",\"lat\":1,\"lon\":2,\"r\":10},"
                + "{\"id\":\"e\",\"lon\":2,\"r\":600},"
                + "{\"id\":\"a\",\"lat\":1,\"lon\":2,\"r\":600},"
                + "{\"lat\":1,\"lon\":2,\"r\":600}]";
        List<Place> places = PlaceStore.parse(json);
        assertEquals(1, places.size());
        assertEquals("a", places.get(0).id);
        assertTrue(PlaceStore.parse("not json").isEmpty());
        assertTrue(PlaceStore.parse("").isEmpty());
    }

    @Test
    public void atMostTenPlaces() {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 12; i++) {
            json.append(i == 0 ? "" : ",").append("{\"id\":\"p").append(i).append("\",\"lat\":1,\"lon\":2,\"r\":600}");
        }
        assertEquals(PlaceStore.MAX, PlaceStore.parse(json.append(']').toString()).size());
    }

    @Test
    public void radiusIsClamped() {
        assertEquals(Place.RADIUS_MAX, HOME.withRadius(99_999).radius);
        assertEquals(Place.RADIUS_MIN, HOME.withRadius(1).radius);
        assertSame("坐标不在范围里不改", HOME, HOME.withCenter(95d, 20d));
    }

    // ================================================================= 在不在

    @Test
    public void distanceIsAboutRight() {
        // 纬度差 0.001° 约 111 m
        double d = Whereabouts.distance(10d, 20d, 10.001d, 20d);
        assertTrue(String.valueOf(d), d > 110 && d < 112);
    }

    @Test
    public void insideOutsideAndUnsure() {
        assertEquals(Whereabouts.Side.IN, Whereabouts.side(HOME, 10.0005d, 20d, 10, null));
        assertEquals(Whereabouts.Side.OUT, Whereabouts.side(HOME, 10.01d, 20d, 10, null));
        assertEquals("边上、误差盖过半径线：判不清", Whereabouts.Side.UNSURE, Whereabouts.side(HOME, 10.0018d, 20d, 10, null));
        assertEquals("没有定位：判不清", Whereabouts.Side.UNSURE,
                Whereabouts.side(HOME, 10d, 20d, Double.POSITIVE_INFINITY, null));
    }

    /** 在里面的，要一定出了 r + m 才算离开（m = 25 m）；不在的，一定进了 r 才算到达。 */
    @Test
    public void leavingNeedsTheMargin() {
        // 约 222 m：误差 10 m 时一定不在 r（200）里，但没一定出 r + m（225）
        assertEquals(Whereabouts.Side.IN, Whereabouts.side(HOME, 10.002d, 20d, 10, true));
        assertEquals(Whereabouts.Side.OUT, Whereabouts.side(HOME, 10.002d, 20d, 10, null));
        assertEquals(Whereabouts.Side.OUT, Whereabouts.side(HOME, 10.0025d, 20d, 10, true));
        assertEquals(25d, Whereabouts.margin(200), 0d);
        assertEquals(100d, Whereabouts.margin(5000), 0d);
        assertEquals(60d, Whereabouts.margin(600), 0d);
    }

    @Test
    public void whereIsTheNearestPlaceWeAreSurelyIn() {
        Place near = Place.of("near", "", 10.0001d, 20d, 1000);
        List<Place> places = Arrays.asList(near, HOME);
        assertSame(HOME, Whereabouts.where(places, 10d, 20d, 10));
        assertSame(Whereabouts.ELSEWHERE, Whereabouts.where(Collections.singletonList(HOME), 10.05d, 20d, 10));
        assertNull("判不清", Whereabouts.where(Collections.singletonList(HOME), 10.0018d, 20d, 10));
        assertSame(Whereabouts.ELSEWHERE, Whereabouts.where(Collections.emptyList(), 10d, 20d, 10));
    }

    // ================================================================= 误差

    @Test
    public void uncertaintyGrowsWithDistanceDrivenSinceTheFix() {
        PositionTrack t = new PositionTrack();
        assertTrue(Double.isInfinite(t.uncertainty(0)));
        t.update(10d, 20d, 8f, 0L, 36f, 0);
        assertEquals(8d, t.uncertainty(0), 0.001);
        // 36 km/h = 10 m/s，5 秒没有新的定位：多 50 m
        t.update(null, null, null, null, 36f, 5_000);
        assertEquals(58d, t.uncertainty(5_000), 0.001);
        assertEquals(68d, t.uncertainty(6_000), 0.001);
        // 新的定位：从零算起
        t.update(10.001d, 20d, 5f, 6_000L, 0f, 6_000);
        assertEquals(5d, t.uncertainty(60_000), 0.001);
    }

    @Test
    public void anOldFixWithoutSpeedIsUnknown() {
        PositionTrack t = new PositionTrack();
        t.update(10d, 20d, null, 0L, null, 0);
        assertEquals(PositionTrack.ACCURACY_UNKNOWN_M, t.uncertainty(1_000), 0.001);
        assertTrue(Double.isInfinite(t.uncertainty(PositionTrack.STALE_MS + 1)));
        t.clear();
        assertFalse(t.hasFix());
    }

    // ================================================================= 删地点

    @Test
    public void deletingAPlaceFixesTheRulesThatUseIt() {
        Rule both = new Rule("a", null, true, Trigger.gear("P"),
                Collections.singletonList(Condition.notInPlaces(Arrays.asList("home", "work"))),
                Collections.singletonList(Change.of(Setting.SENTRY, "on")), false);
        Rule onlyHome = new Rule("b", null, true, Trigger.gear("P"),
                Arrays.asList(Condition.batteryAtLeast(20), Condition.inPlaces(Collections.singletonList("home"))),
                Collections.singletonList(Change.of(Setting.SENTRY, "on")), false);
        Rule arrive = new Rule("c", null, true, Trigger.arrive("home"), Collections.emptyList(),
                Collections.singletonList(Change.of(Setting.REAR_CLIMATE, "on")), true);
        Rule other = new Rule("d", null, true, Trigger.getIn(), Collections.emptyList(),
                Collections.singletonList(Change.of(Setting.DRIVE_MODE, "comfort")), false);
        List<Rule> rules = Arrays.asList(both, onlyHome, arrive, other);
        assertEquals(3, PlaceStore.usedBy(rules, "home"));

        List<Rule> after = PlaceStore.withoutPlace(rules, "home");
        assertEquals("还有公司：只去掉家，照样开着", Collections.singletonList("work"), after.get(0).conditions.get(0).places);
        assertTrue(after.get(0).enabled);
        assertEquals("只剩家的条件去掉、这一条关掉", 1, after.get(1).conditions.size());
        assertFalse(after.get(1).enabled);
        assertFalse("触发是家的关掉", after.get(2).enabled);
        assertEquals(Trigger.arrive("home"), after.get(2).trigger);
        assertSame(other, after.get(3));
    }
}
