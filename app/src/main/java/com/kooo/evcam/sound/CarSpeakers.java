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

    private CarSpeakers() {
    }

    /** 车内的媒体喇叭；找不到是 null。 */
    public static AudioDeviceInfo cabin(Context context) {
        return find(context, CABIN_MEDIA);
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
