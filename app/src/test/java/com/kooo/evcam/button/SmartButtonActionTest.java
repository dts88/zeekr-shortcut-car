package com.kooo.evcam.button;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.overlay.FloatingAction;
import com.kooo.evcam.telemetry.Signal;

import org.junit.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 灵动贴能选的动作（{@link SmartButtonAction}）：不使用 + 悬浮按钮的全部 + 驾驶模式三项 + 手排模式四项 + 车控动作（2026-11-05），
 * 按组列出，认不出的当不使用。
 */
public class SmartButtonActionTest {

    @Test
    public void listsNoneThenEveryFloatingActionThenTheDriveModes() {
        List<SmartButtonAction> all = SmartButtonAction.all();
        assertSame(SmartButtonAction.NONE, all.get(0));
        Set<String> keys = new HashSet<>();
        for (SmartButtonAction action : all) {
            assertTrue("重复的 key " + action.key, keys.add(action.key));
        }
        for (FloatingAction floating : FloatingAction.values()) {
            SmartButtonAction action = SmartButtonAction.fromKey(floating.key);
            assertSame(floating, action.floating);
            assertEquals(0, action.driveMode);
        }
        assertEquals(1 + FloatingAction.values().length + 3 + 4 + 36, all.size());
    }

    /** 「不使用」不在哪一组；别的都在一组里，按组的先后列出，每一组都有东西。 */
    @Test
    public void everyActionButNoneIsInAGroupListedInOrder() {
        List<SmartButtonAction> all = SmartButtonAction.all();
        assertNull(SmartButtonAction.NONE.group);
        int last = -1;
        for (SmartButtonAction action : all.subList(1, all.size())) {
            assertNotNull(action.key, action.group);
            assertTrue(action.key + " 排在前一组后面", action.group.ordinal() >= last);
            last = action.group.ordinal();
        }
        for (SmartButtonAction.Group group : SmartButtonAction.Group.values()) {
            assertTrue(group + " 是空的", !SmartButtonAction.of(group).isEmpty());
        }
        for (FloatingAction floating : FloatingAction.values()) {
            assertSame(SmartButtonAction.Group.APP, SmartButtonAction.fromKey(floating.key).group);
        }
    }

    /** 车控动作：写车的有要做的事，媒体、语音的有不碰车的那一样；车门、尾门不在里面。 */
    @Test
    public void theJiPlusActionsKnowWhatToDo() {
        String[] car = {"sport_one_touch", "volume_up", "volume_down", "steering_wheel_heat", "seat_vent_driver",
                "seat_vent_passenger", "seat_heat_driver", "seat_heat_passenger", "seat_heat_rear_left",
                "seat_heat_rear_right", "seat_heat_rear", "seat_massage_driver", "seat_massage_passenger", "seat_restore",
                "climate_power", "climate_circulation", "defrost_front", "defrost_rear", "central_lock", "mirror_fold",
                "charging_cap", "view_360", "welcome_light", "charging_light", "driving_light", "fragrance",
                "theater_driver", "theater_passenger", "camping", "sentry"};
        for (String key : car) {
            SmartButtonAction action = SmartButtonAction.fromKey(key);
            assertNotNull(key, action.car);
            assertNull(key, action.media);
            assertNull(key, action.floating);
        }
        String[] media = {"voice_assistant", "media_play_pause", "media_previous", "media_next", "media_mute"};
        for (String key : media) {
            SmartButtonAction action = SmartButtonAction.fromKey(key);
            assertNotNull(key, action.media);
            assertNull(key, action.car);
            assertSame(key, SmartButtonAction.Group.MEDIA, action.group);
        }
        assertEquals(35, car.length + media.length);
        assertSame(SmartButtonAction.NONE, SmartButtonAction.fromKey("door_passenger"));
        assertSame(SmartButtonAction.NONE, SmartButtonAction.fromKey("tailgate"));
    }

    @Test
    public void driveModesWriteTheThreeKnownValues() {
        assertEquals(Signal.DRIVE_MODE_COMFORT, SmartButtonAction.fromKey("drive_mode_comfort").driveMode);
        assertEquals(Signal.DRIVE_MODE_STANDARD, SmartButtonAction.fromKey("drive_mode_standard").driveMode);
        assertEquals(Signal.DRIVE_MODE_SPORT, SmartButtonAction.fromKey("drive_mode_sport").driveMode);
        assertNull(SmartButtonAction.fromKey("drive_mode_sport").floating);
        assertNotNull(SmartButtonAction.fromKey("lock_footage").floating);
    }

    @Test
    public void theManualShiftActionsOnlyStep() {
        assertEquals(1, SmartButtonAction.fromKey("drive_mode_up").driveModeStep);
        assertEquals(-1, SmartButtonAction.fromKey("drive_mode_down").driveModeStep);
        assertEquals(1, SmartButtonAction.fromKey("gear_up").gearStep);
        assertEquals(-1, SmartButtonAction.fromKey("gear_down").gearStep);
        for (String key : new String[] {"drive_mode_up", "drive_mode_down", "gear_up", "gear_down"}) {
            assertNull(key, SmartButtonAction.fromKey(key).floating);
            assertEquals(key, 0, SmartButtonAction.fromKey(key).driveMode);
        }
        assertEquals(0, SmartButtonAction.fromKey("gear_up").driveModeStep);
        assertEquals(0, SmartButtonAction.fromKey("drive_mode_up").gearStep);
        assertEquals(0, SmartButtonAction.fromKey("drive_mode_sport").driveModeStep);
        assertEquals(0, SmartButtonAction.NONE.gearStep);
    }

    /** 舒适、标准、运动一次走一级，到头还是它（不循环）。 */
    @Test
    public void driveModesStepOneAtATimeAndDoNotWrap() {
        assertEquals(Signal.DRIVE_MODE_STANDARD, SmartButtonAction.nextDriveMode(Signal.DRIVE_MODE_COMFORT, 1));
        assertEquals(Signal.DRIVE_MODE_SPORT, SmartButtonAction.nextDriveMode(Signal.DRIVE_MODE_STANDARD, 1));
        assertEquals(Signal.DRIVE_MODE_SPORT, SmartButtonAction.nextDriveMode(Signal.DRIVE_MODE_SPORT, 1));
        assertEquals(Signal.DRIVE_MODE_STANDARD, SmartButtonAction.nextDriveMode(Signal.DRIVE_MODE_SPORT, -1));
        assertEquals(Signal.DRIVE_MODE_COMFORT, SmartButtonAction.nextDriveMode(Signal.DRIVE_MODE_STANDARD, -1));
        assertEquals(Signal.DRIVE_MODE_COMFORT, SmartButtonAction.nextDriveMode(Signal.DRIVE_MODE_COMFORT, -1));
    }

    /** 雪地、读不到：不在这三种里，不切换。 */
    @Test
    public void otherDriveModesDoNotStep() {
        assertEquals(0, SmartButtonAction.nextDriveMode(Signal.DRIVE_MODE_SNOW, 1));
        assertEquals(0, SmartButtonAction.nextDriveMode(Signal.DRIVE_MODE_SNOW, -1));
        assertEquals(0, SmartButtonAction.nextDriveMode(null, 1));
    }

    /** 配置可以被手改：认不出的、空的都当「不使用」，按一下不做没选过的事。 */
    @Test
    public void unknownKeysDoNothing() {
        assertSame(SmartButtonAction.NONE, SmartButtonAction.fromKey(null));
        assertSame(SmartButtonAction.NONE, SmartButtonAction.fromKey(""));
        assertSame(SmartButtonAction.NONE, SmartButtonAction.fromKey("unlock_doors"));
        assertNull(SmartButtonAction.NONE.floating);
        assertEquals(0, SmartButtonAction.NONE.driveMode);
    }
}
