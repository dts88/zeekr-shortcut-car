package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.profile.ProfileStore;

import org.junit.Test;

/**
 * 导出、导入设置的规矩（{@link SettingsBackup}，项目所有者 2026-10-19）：只认同一个大版本、同一个格式导出的文件；
 * 设置里的运行状态不导出、不导入，视频流配置整份带上。
 */
public class SettingsBackupTest {

    @Test
    public void onlyTheSameMajorVersionAndFormatImport() {
        int format = SettingsBackup.FORMAT;
        assertTrue(SettingsBackup.compatible(format, "2.14.0-alpha", "2.15.3-beta"));
        assertTrue("正式版和预发布版同一个大版本", SettingsBackup.compatible(format, "2.0.0", "2.14.0-alpha"));
        assertFalse("大版本升级后不导入", SettingsBackup.compatible(format, "2.14.0-alpha", "3.0.0"));
        assertFalse("降级也不导入", SettingsBackup.compatible(format, "3.1.0", "2.14.0"));
        assertFalse("格式不同不导入", SettingsBackup.compatible(format + 1, "2.14.0", "2.14.0"));
        assertFalse("认不出版本号不导入", SettingsBackup.compatible(format, "", "2.14.0"));
        assertFalse(SettingsBackup.compatible(format, null, "2.14.0"));
        assertFalse(SettingsBackup.compatible(format, "2.14.0", "unknown"));
    }

    @Test
    public void fileNamesCarryTheTimeAndSortByIt() {
        String name = SettingsBackup.fileName(0L);
        assertTrue(name, name.matches("zeekr_settings_\\d{8}_\\d{6}\\.json"));
        assertTrue(SettingsBackup.isSettingsFile(name));
        assertTrue("按名字排就是按时间排",
                SettingsBackup.fileName(1_000_000_000_000L).compareTo(SettingsBackup.fileName(2_000_000_000_000L)) < 0);
        assertFalse("诊断报告不是设置文件", SettingsBackup.isSettingsFile("zeekr_diagnostics_20261019_120000.json"));
        assertFalse(SettingsBackup.isSettingsFile("zeekr_settings_20261019_120000.txt"));
        assertFalse(SettingsBackup.isSettingsFile(null));
    }

    @Test
    public void runtimeStateIsNotASetting() {
        for (String key : new String[]{"first_launch", "language_chosen", "rail_side_chosen", "ui_left_for_screen_off",
                "last_detected_sd_path", "rearview_guide_seen", "photo_via_jpeg_reset_2_10_12", "slot_map_zeekr_7x"}) {
            assertTrue(key, AppConfig.isRuntimeState(key));
            assertFalse(key + " 不导出", SettingsBackup.carries(AppConfig.PREF_NAME, key));
        }
        for (String key : new String[]{"rearview_enabled", "language", "car_model", "license_plate",
                "zeekr_camera_override_front", "recording_floating_x", "a_key_added_later"}) {
            assertFalse(key, AppConfig.isRuntimeState(key));
            assertTrue(key + " 导出", SettingsBackup.carries(AppConfig.PREF_NAME, key));
        }
    }

    /** 视频流配置是整份设置：键名长得像运行状态也照带。 */
    @Test
    public void streamProfilesAreCarriedWhole() {
        assertTrue(SettingsBackup.carries(ProfileStore.FILE, "current"));
        assertTrue(SettingsBackup.carries(ProfileStore.FILE, "first_launch"));
    }

    @Test
    public void storeNamesMatchTheFilesTheAppUses() {
        assertEquals("app_config", AppConfig.PREF_NAME);
        assertEquals("profiles", ProfileStore.FILE);
    }
}
