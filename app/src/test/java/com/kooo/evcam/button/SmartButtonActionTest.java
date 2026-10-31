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

/** 灵动贴能选的动作（{@link SmartButtonAction}）：不使用 + 悬浮按钮的全部 + 驾驶模式三项 + 手排模式四项，认不出的当不使用。 */
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
        assertEquals(1 + FloatingAction.values().length + 3 + 4, all.size());
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
