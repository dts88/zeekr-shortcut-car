package com.kooo.evcam.overlay;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.util.HashSet;
import java.util.Set;

/**
 * {@link FloatingAction} 的单元测试。
 *
 * <p>钉两件事：认不出来的值回到「打开主界面」，以及那几个 key 一个都不能改
 * —— 它们写进了用户的配置，改一个字母就是把人家设好的动作悄悄换掉。</p>
 */
public class FloatingActionTest {

    @Test
    public void unknownValuesFallBackToOpeningTheApp() {
        assertEquals(FloatingAction.OPEN_APP, FloatingAction.fromKey(null));
        assertEquals(FloatingAction.OPEN_APP, FloatingAction.fromKey(""));
        assertEquals(FloatingAction.OPEN_APP, FloatingAction.fromKey("something_else"));
    }

    @Test
    public void everyKeyRoundTrips() {
        for (FloatingAction action : FloatingAction.values()) {
            assertEquals(action, FloatingAction.fromKey(action.key));
        }
    }

    /** 存下去的字符串是契约，写死在测试里，改动就会被拦住。 */
    @Test
    public void theStoredKeysAreFixed() {
        assertEquals("open_app", FloatingAction.OPEN_APP.key);
        assertEquals("toggle_recording", FloatingAction.TOGGLE_RECORDING.key);
        assertEquals("take_photo", FloatingAction.TAKE_PHOTO.key);
        assertEquals("toggle_mirror", FloatingAction.TOGGLE_MIRROR.key);
    }

    @Test
    public void keysAreUniqueAndInOrder() {
        String[] keys = FloatingAction.keys();
        assertEquals(FloatingAction.values().length, keys.length);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < keys.length; i++) {
            assertEquals(FloatingAction.values()[i].key, keys[i]);
            assertNotEquals("重复的 key: " + keys[i], false, seen.add(keys[i]));
        }
    }
}
