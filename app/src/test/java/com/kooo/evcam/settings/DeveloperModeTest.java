package com.kooo.evcam.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * {@link DeveloperMode} 的单元测试。
 *
 * <p>重点是<b>默认关着</b>和<b>密码不对就打不开</b>：这道门后面是些没做完的
 * 和排查用的选项，误开了会让人以为它们是正常功能。</p>
 *
 * <p>打开、关闭要写配置（1.42.0 起开发者模式是存着的），这里没有 Context，
 * 那一半在车上验证；这里只管密码和默认状态。</p>
 */
public class DeveloperModeTest {

    @Test
    public void startsLocked() {
        assertFalse(DeveloperMode.isUnlocked());
    }

    @Test
    public void onlyTheRightPasswordIsAccepted() {
        assertTrue(DeveloperMode.isPassword("6651"));
        assertFalse(DeveloperMode.isPassword("0000"));
        assertFalse(DeveloperMode.isPassword(""));
        assertFalse(DeveloperMode.isPassword(null));
    }

    /** 密码不对时根本不碰存储，所以不需要 Context，也不会打开。 */
    @Test
    public void aWrongPasswordChangesNothing() {
        assertFalse(DeveloperMode.unlock(null, "0000"));
        assertFalse(DeveloperMode.unlock(null, null));
        assertFalse(DeveloperMode.isUnlocked());
    }
}
