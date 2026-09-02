package com.kooo.evcam.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link LocalNetwork} 的单元测试。
 *
 * <p>要钉住的是排序：车机开热点时，两块网卡可能同时有地址，
 * 而只有 AP 那块是手机连得上的。挑错了的表现是「扫了码打不开」，
 * 在车上很难判断是网络问题还是应用问题。</p>
 */
public class LocalNetworkTest {

    private static LocalNetwork.Endpoint at(String iface, String ip) {
        return new LocalNetwork.Endpoint(iface, ip, LocalNetwork.classify(iface));
    }

    @Test
    public void hotspotInterfacesAreRecognised() {
        assertEquals(LocalNetwork.Kind.HOTSPOT, LocalNetwork.classify("ap0"));
        assertEquals(LocalNetwork.Kind.HOTSPOT, LocalNetwork.classify("softap0"));
        assertEquals(LocalNetwork.Kind.HOTSPOT, LocalNetwork.classify("p2p-wlan0-0"));
    }

    @Test
    public void wifiClientInterfacesAreRecognised() {
        assertEquals(LocalNetwork.Kind.WIFI, LocalNetwork.classify("wlan0"));
        assertEquals(LocalNetwork.Kind.WIFI, LocalNetwork.classify("wlan1"));
    }

    @Test
    public void usbTetheringIsItsOwnKind() {
        assertEquals(LocalNetwork.Kind.TETHER, LocalNetwork.classify("rndis0"));
        assertEquals(LocalNetwork.Kind.TETHER, LocalNetwork.classify("usb0"));
    }

    @Test
    public void anythingElseIsOther() {
        assertEquals(LocalNetwork.Kind.OTHER, LocalNetwork.classify("eth0"));
        assertEquals(LocalNetwork.Kind.OTHER, LocalNetwork.classify("dummy0"));
        assertEquals(LocalNetwork.Kind.OTHER, LocalNetwork.classify(null));
    }

    /** 车机自己开热点时，AP 那块网卡的地址才是手机连得上的。 */
    @Test
    public void hotspotComesBeforeWifiClient() {
        List<LocalNetwork.Endpoint> found = Arrays.asList(
                at("wlan0", "192.168.1.23"),
                at("ap0", "192.168.43.1"));
        assertEquals("192.168.43.1", LocalNetwork.preferred(found).address);
    }

    /** 车机连的是手机热点时，只有 wlan0 有地址，那就是它。 */
    @Test
    public void wifiClientIsUsedWhenThereIsNoHotspot() {
        List<LocalNetwork.Endpoint> found = Arrays.asList(
                at("eth0", "10.0.0.5"),
                at("wlan0", "192.168.43.117"));
        assertEquals("192.168.43.117", LocalNetwork.preferred(found).address);
    }

    /** 排序不能删东西 —— 猜错时得让人自己挑一个试。 */
    @Test
    public void sortingKeepsEveryCandidate() {
        List<LocalNetwork.Endpoint> found = Arrays.asList(
                at("eth0", "10.0.0.5"),
                at("wlan0", "192.168.1.23"),
                at("ap0", "192.168.43.1"),
                at("rndis0", "192.168.42.129"));
        List<LocalNetwork.Endpoint> ordered = LocalNetwork.sorted(found);
        assertEquals(4, ordered.size());
        assertEquals(LocalNetwork.Kind.HOTSPOT, ordered.get(0).kind);
        assertEquals(LocalNetwork.Kind.WIFI, ordered.get(1).kind);
        assertEquals(LocalNetwork.Kind.TETHER, ordered.get(2).kind);
        assertEquals(LocalNetwork.Kind.OTHER, ordered.get(3).kind);
    }

    @Test
    public void noAddressesMeansNoPreferredOne() {
        assertNull(LocalNetwork.preferred(new ArrayList<>()));
    }
}
