package com.kooo.evcam.sound;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;

/**
 * 车机上的喇叭：按地址找输出设备（{@code AudioDeviceInfo.getAddress()} 就是车机的 BUS 名）。回放出声、模拟声浪共用这一处。
 *
 * <p>车上的事实（Lab findings §2.43，2026-10-20；CarPiano 在 7X 上是同样的结论）：App Lab 容器里不 {@code setPreferredDevice}
 * 就没声；车内的 {@link #CABIN_MEDIA} 按媒体用途放听得到。找不到（不是这台车）时返回 null，由用的人决定交给系统还是不出声。</p>
 */
public final class CarSpeakers {

    /** 车内的媒体喇叭（12 声道），跟车机的媒体音量走。 */
    public static final String CABIN_MEDIA = "BUS00_MEDIA";
    /**
     * 车外喇叭：车上只有一个（单声道），车机给了两路 —— 这一路和 {@link #OUTSIDE_PLAYBACK}（Lab findings §2.43，2026-10-20：
     * 各 1 个声道，项目所有者听着是同一个喇叭）。CarPiano 在 7X 上按媒体用途往这一路放有声、往另一路「指定得上、没声」，
     * Lab 两路都有声。用哪一路由「模拟声浪」页的「车外线路」选（项目所有者 2026-10-24）。车外喇叭在车机里是另一个音量组：
     * 音量键（媒体那一组）调不到它（项目所有者 2026-10-24 实车），系统音量不一定管得到车外（CarPiano 自己做软件增益）。
     */
    public static final String OUTSIDE_NOTIFY = "BUS12_OUTER_NOTIFY";
    /** 车外喇叭的另一路（同一个喇叭）。 */
    public static final String OUTSIDE_PLAYBACK = "BUS11_OUTER_SPEAKER_PLAYBACK";

    private CarSpeakers() {
    }

    /** 车内的媒体喇叭；找不到是 null。 */
    public static AudioDeviceInfo cabin(Context context) {
        return find(context, CABIN_MEDIA);
    }

    /** 「模拟声浪」页的「车外线路」存的值（{@code AppConfig.DRIVE_SOUND_ROUTE_*}）对应的地址：bus11 → BUS11，别的都按 BUS12。 */
    static String exteriorAddress(String route) {
        return "bus11".equals(route) ? OUTSIDE_PLAYBACK : OUTSIDE_NOTIFY;
    }

    /** 按地址找一个 BUS 输出；找不到是 null。 */
    static AudioDeviceInfo find(Context context, String address) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) {
            return null;
        }
        AudioDeviceInfo[] devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
        int[] types = new int[devices.length];
        String[] addresses = new String[devices.length];
        for (int i = 0; i < devices.length; i++) {
            types[i] = devices[i].getType();
            addresses[i] = devices[i].getAddress();
        }
        int index = indexOf(types, addresses, address);
        return index < 0 ? null : devices[index];
    }

    /**
     * 哪一个是要找的：类型是 BUS、地址一字不差。纯函数（{@code CarSpeakersTest}）。
     *
     * @return 下标；没有是 -1
     */
    static int indexOf(int[] types, String[] addresses, String address) {
        if (address == null) {
            return -1;
        }
        for (int i = 0; i < types.length && i < addresses.length; i++) {
            if (types[i] == AudioDeviceInfo.TYPE_BUS && address.equals(addresses[i])) {
                return i;
            }
        }
        return -1;
    }
}
