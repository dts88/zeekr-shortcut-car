package com.kooo.evcam.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/**
 * {@link DeveloperMode} 的单元测试。
 *
 * <p>重点是<b>默认关着</b>和<b>密码不对就打不开</b>：这道门后面是些没做完的
 * 和排查用的选项，误开了会让人以为它们是正常功能。</p>
 */
public class DeveloperModeTest {

    @After
    public void tearDown() {
        DeveloperMode.lock();
    }

    @Test
    public void startsLocked() {
        assertFalse(DeveloperMode.isUnlocked());
    }

    @Test
    public void theRightPasswordUnlocksIt() {
        assertTrue(DeveloperMode.unlock("6651"));
        assertTrue(DeveloperMode.isUnlocked());
    }

    @Test
    public void aWrongPasswordChangesNothing() {
        assertFalse(DeveloperMode.unlock("0000"));
        assertFalse(DeveloperMode.isUnlocked());
        assertFalse(DeveloperMode.unlock(""));
        assertFalse(DeveloperMode.unlock(null));
        assertFalse(DeveloperMode.isUnlocked());
    }

    /** 解锁之后还能手动关回去 —— 重启应用也是同样的效果。 */
    @Test
    public void itCanBeLockedAgain() {
        DeveloperMode.unlock("6651");
        DeveloperMode.lock();
        assertFalse(DeveloperMode.isUnlocked());
    }
}
