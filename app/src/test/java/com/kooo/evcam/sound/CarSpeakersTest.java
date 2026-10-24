package com.kooo.evcam.sound;

import static org.junit.Assert.assertEquals;

import android.media.AudioDeviceInfo;

import org.junit.Test;

/**
 * 按地址找车机的喇叭：类型是 BUS、地址一字不差。
 *
 * <p>算错的表现：回放、声浪走错喇叭（比如放到车外那一路），或者容器里没指定设备、一点声都没有。</p>
 */
public class CarSpeakersTest {

    private static final int BUS = AudioDeviceInfo.TYPE_BUS;
    private static final int SPEAKER = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER;

    @Test
    public void findsTheBusWithTheExactAddress() {
        int[] types = {BUS, BUS, BUS};
        String[] addresses = {"BUS12_OUTER_NOTIFY", "BUS00_MEDIA", "BUS01_SYS_NOTIFICATION"};
        assertEquals(1, CarSpeakers.indexOf(types, addresses, CarSpeakers.CABIN_MEDIA));
    }

    @Test
    public void ignoresOtherDeviceTypesAndNearMisses() {
        int[] types = {SPEAKER, BUS, BUS};
        String[] addresses = {"BUS00_MEDIA", "bus00_media", "BUS00_MEDIA_2"};
        assertEquals("不是 BUS、大小写不同、多了后缀都不算", -1,
                CarSpeakers.indexOf(types, addresses, CarSpeakers.CABIN_MEDIA));
    }

    @Test
    public void theExteriorSpeakerPrefersItsNotifyRoute() {
        // 车外只有一个喇叭，车机给了两路（Lab §2.43）：先用 BUS12（CarPiano 按媒体用途放有声的那一路），没有再用 BUS11
        int[] types = {BUS, BUS, BUS};
        assertEquals(CarSpeakers.OUTSIDE_NOTIFY, CarSpeakers.outsideAddress(types,
                new String[] {"BUS11_OUTER_SPEAKER_PLAYBACK", "BUS00_MEDIA", "BUS12_OUTER_NOTIFY"}));
        assertEquals(CarSpeakers.OUTSIDE_PLAYBACK, CarSpeakers.outsideAddress(types,
                new String[] {"BUS11_OUTER_SPEAKER_PLAYBACK", "BUS00_MEDIA", "BUS01_SYS_NOTIFICATION"}));
        assertEquals("没有车外喇叭（不是这台车）", null, CarSpeakers.outsideAddress(types,
                new String[] {"BUS00_MEDIA", "BUS01_SYS_NOTIFICATION", "BUS17_FDHU_REAR_NOTIFICATION"}));
    }

    @Test
    public void handlesMissingAddresses() {
        assertEquals(-1, CarSpeakers.indexOf(new int[]{BUS}, new String[]{null}, CarSpeakers.CABIN_MEDIA));
        assertEquals(-1, CarSpeakers.indexOf(new int[]{BUS}, new String[]{"BUS00_MEDIA"}, null));
        assertEquals(-1, CarSpeakers.indexOf(new int[0], new String[0], CarSpeakers.CABIN_MEDIA));
    }
}
