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

/** 灵动贴能选的动作（{@link SmartButtonAction}）：不使用 + 悬浮按钮的全部 + 驾驶模式三项，认不出的当不使用。 */
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
        assertEquals(1 + FloatingAction.values().length + 3, all.size());
    }

    @Test
    public void driveModesWriteTheThreeKnownValues() {
        assertEquals(Signal.DRIVE_MODE_COMFORT, SmartButtonAction.fromKey("drive_mode_comfort").driveMode);
        assertEquals(Signal.DRIVE_MODE_STANDARD, SmartButtonAction.fromKey("drive_mode_standard").driveMode);
        assertEquals(Signal.DRIVE_MODE_SPORT, SmartButtonAction.fromKey("drive_mode_sport").driveMode);
        assertNull(SmartButtonAction.fromKey("drive_mode_sport").floating);
        assertNotNull(SmartButtonAction.fromKey("lock_footage").floating);
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
