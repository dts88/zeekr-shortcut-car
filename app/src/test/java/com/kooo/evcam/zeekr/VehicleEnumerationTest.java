package com.kooo.evcam.zeekr;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link VehicleEnumeration} 里那个关键词判断的单元测试。
 *
 * <p>这个判断只影响<b>排序</b>，不过滤任何东西 —— 每个板块都把「不像的」也列了出来。
 * 所以它错了不会让人漏掉信号，但排得准能省很多翻报告的时间。</p>
 */
public class VehicleEnumerationTest {

    @Test
    public void obviousVehicleNamesAreRecognised() {
        String[] shouldHit = {
                "vehicle.gear", "car_service", "ecarxcar_service", "zeekr.avm",
                "persist.vendor.door.status", "sys.speed", "TurnSignal",
                "com.ecarx.hvac", "reverse_camera", "adas_state", "brake.pedal",
        };
        for (String name : shouldHit) {
            assertTrue("应当识别为车辆相关: " + name,
                    VehicleEnumeration.looksVehicleRelated(name));
        }
    }

    @Test
    public void unrelatedNamesAreNotFlagged() {
        String[] shouldMiss = {
                "wifi.enabled", "ro.build.version.sdk", "bluetooth_name",
                "media.volume", "input_method",
        };
        for (String name : shouldMiss) {
            assertFalse("不该识别为车辆相关: " + name,
                    VehicleEnumeration.looksVehicleRelated(name));
        }
    }

    @Test
    public void matchingIsCaseInsensitive() {
        assertTrue(VehicleEnumeration.looksVehicleRelated("VEHICLE_SPEED"));
        assertTrue(VehicleEnumeration.looksVehicleRelated("Car.Door"));
        assertTrue(VehicleEnumeration.looksVehicleRelated("ZeekR"));
    }

    @Test
    public void nullIsSurvivable() {
        assertFalse(VehicleEnumeration.looksVehicleRelated(null));
    }
}
