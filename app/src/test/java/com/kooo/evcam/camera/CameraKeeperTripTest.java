/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.camera;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * 「这一趟」（{@link CameraKeeper.Trip}，规格 1.2）：启动自动录制每一趟最多看一次、人停过的不再开；「这一趟要录」进程被结束了还在；
 * 新的一趟清零；写盘只在该写时写。
 */
public class CameraKeeperTripTest {

    /** 内存里的一份「盘」：新实例从同一份里读回来，就是进程被杀又拉回来。数一下写了几次、其中几次是同步写盘。 */
    private static final class MemoryStore implements CameraKeeper.Trip.Store {
        boolean used;
        boolean wanted;
        int writes;
        int durableWrites;

        @Override
        public boolean autoStartUsed() {
            return used;
        }

        @Override
        public boolean wanted() {
            return wanted;
        }

        @Override
        public void put(boolean autoStartUsed, boolean wanted, boolean durable) {
            this.used = autoStartUsed;
            this.wanted = wanted;
            writes++;
            if (durable) {
                durableWrites++;
            }
        }
    }

    /** 这一趟看过一次启动自动录制，就不再看 —— 切个日夜模式、主界面重建不是新的一趟。 */
    @Test
    public void autoStartIsLookedAtOncePerTrip() {
        CameraKeeper.Trip trip = new CameraKeeper.Trip(null);
        assertFalse(trip.autoStartUsed());
        trip.useAutoStart();
        assertTrue(trip.autoStartUsed());
        assertFalse(ChannelRules.autoStartMoment(trip.autoStartUsed(), true, false));
    }

    /** 人按了停止：这一趟里没有任何一条路可以再自动开起来，「这一趟要录」也撤。 */
    @Test
    public void nothingAutoStartsAfterTheUserPressedStop() {
        CameraKeeper.Trip trip = new CameraKeeper.Trip(null);
        trip.want(true);
        trip.userStopped();
        assertTrue(trip.autoStartUsed());
        assertFalse(trip.wanted());
    }

    /** 新的一趟：看没看过、停没停过清零；在录的这一次属于新的一趟，「这一趟要录」留着；没在录的撤。 */
    @Test
    public void aNewTripClearsTheChoices() {
        CameraKeeper.Trip trip = new CameraKeeper.Trip(null);
        trip.userStopped();
        trip.want(true);
        assertFalse("在录：不撤", trip.reset(true));
        assertFalse(trip.autoStartUsed());
        assertTrue(trip.wanted());
        trip.useAutoStart();
        assertTrue("没在录：撤掉一个立着的", trip.reset(false));
        assertFalse(trip.wanted());
        assertFalse(trip.autoStartUsed());
    }

    /** 规格 1.2：进程被杀又拉回来，「这一趟」的选择还在 —— 新实例从同一份存储里读回来。 */
    @Test
    public void choicesSurviveANewInstanceThroughTheStore() {
        MemoryStore store = new MemoryStore();
        CameraKeeper.Trip first = new CameraKeeper.Trip(store);
        first.want(true);
        first.useAutoStart();

        CameraKeeper.Trip second = new CameraKeeper.Trip(store);
        assertTrue("「这一趟要录」被车机结束进程之后还在", second.wanted());
        assertTrue(second.autoStartUsed());
    }

    /**
     * 只有「这一趟要录」变了、人按了停止才同步写盘（哨兵模式没开时车机下电 3–5 秒后就结束进程）；看过启动自动录制异步写；
     * 没变就不写。
     */
    @Test
    public void onlyWantedAndUserStopAreWrittenDurably() {
        MemoryStore store = new MemoryStore();
        CameraKeeper.Trip trip = new CameraKeeper.Trip(store);
        trip.useAutoStart();
        assertEquals("看过启动自动录制：异步写", 0, store.durableWrites);
        int writes = store.writes;
        trip.useAutoStart();
        assertEquals("没变就不写", writes, store.writes);

        assertTrue(trip.want(true));
        assertEquals("「这一趟要录」立起来：同步写", 1, store.durableWrites);
        writes = store.writes;
        assertFalse("没变", trip.want(true));
        assertEquals("没变就不写", writes, store.writes);

        trip.userStopped();
        assertEquals("人按了停止：同步写", 2, store.durableWrites);
        writes = store.writes;
        trip.userStopped();
        assertEquals("再按一次停止，没变就不写", writes, store.writes);

        trip.reset(false);
        assertEquals("「这一趟要录」本来就撤了：重置异步写", 2, store.durableWrites);
        writes = store.writes;
        trip.reset(false);
        assertEquals("已经是新的一趟，不写", writes, store.writes);
    }

    /** 手动停止之后进程被结束：新进程也不接。 */
    @Test
    public void stoppingByHandDropsWantedForTheNextProcessToo() {
        MemoryStore store = new MemoryStore();
        CameraKeeper.Trip trip = new CameraKeeper.Trip(store);
        trip.want(true);
        trip.userStopped();
        assertFalse(new CameraKeeper.Trip(store).wanted());
    }
}
