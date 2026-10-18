package com.kooo.evcam.settings;

import androidx.appcompat.app.AppCompatDelegate;
import org.junit.Test;
import static org.junit.Assert.*;

public class AppThemeTest {
    @Test public void explicitChoicesAndUnknownValuesMapToAppLocalModes() {
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, AppTheme.modeFor(AppTheme.LIGHT));
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, AppTheme.modeFor(AppTheme.DARK));
        for (String value : new String[]{AppTheme.SYSTEM, "", "unknown", null})
            assertEquals(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM, AppTheme.modeFor(value));
    }
    @Test public void pickerAndStoredDefaultUseTheSameThreeValues() {
        assertEquals("interface_theme", SettingsRegistry.THEME.key);
        assertEquals(AppTheme.SYSTEM, SettingsRegistry.THEME.defaultValue);
        assertArrayEquals(new String[]{AppTheme.SYSTEM, AppTheme.LIGHT, AppTheme.DARK}, SettingsRegistry.THEME.values());
        assertEquals(AppTheme.SYSTEM, SettingsRegistry.THEME.sanitize("unknown"));
    }
}
