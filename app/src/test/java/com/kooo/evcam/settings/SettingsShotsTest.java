/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.preference.Preference;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.screens.ShotActivity;
import com.kooo.evcam.screens.Shots;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;

/** 设置的每一页画成图片：白天、夜间各一套，另加一套可选功能全开的（{@link Shots}）。 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsShotsTest {

    private static final String[] SECTIONS = {
            "screen_recording", "screen_alerts", "screen_storage", "screen_rearview",
            "screen_side_view", "screen_floating", "screen_general", "screen_update",
    };

    @Test
    public void everySectionByDay() {
        shoot(Shots.DAY, "day");
    }

    @Test
    public void everySectionByNight() {
        shoot(Shots.NIGHT, "night");
    }

    /** 可选的功能都打开：关着时只有一行的那几页，展开以后是什么样。 */
    @Test
    public void everySectionWithEverythingOn() {
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setRearViewEnabled(true);
        config.setSideViewEnabled(true);
        config.setDriveSoundEnabled(true);
        // 停车警报开着时下面四组才出现：这张图就是用来看那一屏的
        config.setAlertsEnabled(true);
        config.setAlertsTelegramToken("123456789:AAHexampleTOKENvalue0123456789");
        config.setAlertsTelegramChatId("123456789");
        config.setAlertsClipSeconds(5);
        shoot(Shots.DAY, "day-on");
    }

    /** 同一张开关全开的图，英文：PR 里要用的那一张方便讨论。 */
    @Test
    public void englishSectionsWithEverythingOn() {
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setRearViewEnabled(true);
        config.setSideViewEnabled(true);
        config.setDriveSoundEnabled(true);
        config.setAlertsEnabled(true);
        config.setAlertsTelegramToken("123456789:AAHexampleTOKENvalue0123456789");
        config.setAlertsTelegramChatId("123456789");
        config.setAlertsOwnerId("123456789");
        config.setAlertsClipSeconds(10);
        config.setAlertsQuietEnabled(true);
        shoot(Shots.DAY_EN, "en-on");
    }

    /**
     * 「按键」页里绑定的灵动贴（项目所有者 2026-11-14「名称 / 位置」）：起了名字的显示名字、默认名（带短编号）挪到下面那一行；
     * 没起的照旧是默认名。存着的名字再怪（这里直接往存储里塞一段带标签、格式符、换行、方向控制符的长字，像是从改过的
     * 备份里来的），显示出来也只是最多 20 个字的一行字。地址是随便编的。
     */
    @Test
    public void namedSmartButtons() {
        android.content.Context app = RuntimeEnvironment.getApplication();
        AppConfig config = new AppConfig(app);
        String first = "00:11:22:33:44:55";
        String second = "66:77:88:99:AA:BB";
        String third = "CC:DD:EE:FF:00:11";
        config.addSmartButton(first);
        config.addSmartButton(second);
        config.addSmartButton(third);
        config.setSmartButtonName(first, "方向盘左");
        app.getSharedPreferences(AppConfig.PREF_NAME, android.content.Context.MODE_PRIVATE).edit()
                .putString("smart_button_name." + second, "<b>%s</b>\n\n\u202E" + "很长的名字".repeat(30)).commit();

        RuntimeEnvironment.setQualifiers(Shots.DAY);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SettingsShellFragment shell = new SettingsShellFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(activity.container, shell).commitNow();
        Shots.settle();
        shell.showSection("screen_floating");
        Shots.settle();
        SettingsPreferenceFragment section = (SettingsPreferenceFragment)
                shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
        assertNotNull(section);

        Preference named = section.findPreference("smart_button." + first);
        assertNotNull(named);
        assertEquals("起了名字的显示名字", "方向盘左", String.valueOf(named.getTitle()));
        String defaultName = activity.getString(R.string.smart_button_name,
                com.kooo.evcam.button.SmartButtonAd.shortId(first));
        assertTrue("默认名挪到下面那一行：" + named.getSummary(), String.valueOf(named.getSummary()).contains(defaultName));

        Preference odd = section.findPreference("smart_button." + second);
        assertNotNull(odd);
        String shown = String.valueOf(odd.getTitle());
        assertEquals("怪名字读出来是洗过的", com.kooo.evcam.button.DeviceLabel.clean(shown), shown);
        assertTrue(shown.codePointCount(0, shown.length()) <= com.kooo.evcam.button.DeviceLabel.MAX);
        assertFalse(shown.contains("\n"));
        assertTrue("尖括号、百分号只是字：" + shown, shown.startsWith("<b>%s</b> 很长的名字"));

        Preference plain = section.findPreference("smart_button." + third);
        assertNotNull(plain);
        assertEquals("没起名的照旧是默认名", activity.getString(R.string.smart_button_name,
                com.kooo.evcam.button.SmartButtonAd.shortId(third)), String.valueOf(plain.getTitle()));

        section.scrollToPreference("smart_button." + third);
        File file = Shots.save(activity, "settings-floating-day-names");
        assertTrue(file + " 是空的", file.length() > 10_000);
    }

    /**
     * 「通用」的「模拟声浪」一组：听感、转速变化方式、变速箱所有人都有（4.0.0 收进过开发者选项，项目所有者 2026-11-13
     * 上车后要回来）；听感、变速箱跟着声音风格出现。这一组在第一屏下面，滚到它另出一张图。
     */
    @Test
    public void driveSoundRowsAreThereForEveryone() {
        assertFalse("这一条看的是开发者选项关着时", DeveloperMode.isUnlocked());
        new AppConfig(RuntimeEnvironment.getApplication()).setDriveSoundEnabled(true);
        RuntimeEnvironment.setQualifiers(Shots.DAY);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SettingsShellFragment shell = new SettingsShellFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(activity.container, shell).commitNow();
        Shots.settle();
        shell.showSection("screen_general");
        Shots.settle();
        SettingsPreferenceFragment section = (SettingsPreferenceFragment)
                shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
        assertNotNull(section);
        for (String key : new String[] {"pref_drive_sound_style", "pref_drive_sound_view", "pref_drive_sound_rpm",
                "pref_drive_sound_gearbox", "pref_drive_sound_standard", "pref_drive_sound_volume",
                "pref_drive_sound_output"}) {
            assertTrue("美式 V8 时该有 " + key, shown(section, key));
        }
        section.scrollToPreference("pref_drive_sound_output");
        File file = Shots.save(activity, "settings-general-day-sound");
        assertTrue(file + " 是空的", file.length() > 10_000);

        // 超音速（旧版）：没有听感（只有美式 V8 分车内车外）、没有变速箱（固定 8 速）
        Preference style = section.findPreference("pref_drive_sound_style");
        assertNotNull(style);
        style.callChangeListener(AppConfig.DRIVE_SOUND_STYLE_JET_OLD);
        Shots.settle();
        assertFalse(shown(section, "pref_drive_sound_view"));
        assertFalse(shown(section, "pref_drive_sound_gearbox"));
        assertTrue(shown(section, "pref_drive_sound_rpm"));
    }

    /**
     * 开发者选项「文件维护」一组里的「录像回放：逐个读取片段时长」（项目所有者 2026-11-14：打开文件读时长作后备，
     * 放进开发者选项）：默认关。开发者选项在这里直接拨开（不走密码），看完拨回去。
     */
    @Test
    public void developerFileRows() throws Exception {
        java.lang.reflect.Field unlocked = DeveloperMode.class.getDeclaredField("unlocked");
        unlocked.setAccessible(true);
        unlocked.setBoolean(null, true);
        try {
            assertFalse("默认关", new AppConfig(RuntimeEnvironment.getApplication()).isPlaybackReadDurations());
            RuntimeEnvironment.setQualifiers(Shots.DAY);
            ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
            Shots.stillFrames(activity);
            SettingsShellFragment shell = new SettingsShellFragment();
            activity.getSupportFragmentManager().beginTransaction().replace(activity.container, shell).commitNow();
            Shots.settle();
            shell.showSection(SettingsShellFragment.DEVELOPER_SECTION);
            Shots.settle();
            SettingsPreferenceFragment section = (SettingsPreferenceFragment)
                    shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
            assertNotNull(section);
            androidx.preference.SwitchPreferenceCompat row = section.findPreference("pref_playback_read_durations");
            assertNotNull(row);
            assertTrue(row.isVisible());
            assertFalse(row.isChecked());
            section.scrollToPreference("pref_archive");
            File file = Shots.save(activity, "settings-developer-day-files");
            assertTrue(file + " 是空的", file.length() > 10_000);

            assertTrue(row.callChangeListener(true));
            assertTrue("拨开就存下了", new AppConfig(RuntimeEnvironment.getApplication()).isPlaybackReadDurations());
        } finally {
            unlocked.setBoolean(null, false);
        }
        assertFalse("开发者选项关着时按关算，存着的值不动",
                new AppConfig(RuntimeEnvironment.getApplication()).isPlaybackReadDurations());
    }

    private static boolean shown(SettingsPreferenceFragment section, String key) {
        Preference row = section.findPreference(key);
        assertNotNull("少了 " + key, row);
        return row.isVisible();
    }

    /** 状态能点（ui-spec §十三）：从主界面带着「去哪一行」打开设置，停在那一行所在的分区；已经开着就在里面换过去。 */
    @Test
    public void openingAtARowLandsOnItsSection() {
        RuntimeEnvironment.setQualifiers(Shots.DAY);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SettingsShellFragment shell = SettingsShellFragment.at("screen_storage", "pref_storage_location", null);
        activity.getSupportFragmentManager().beginTransaction().replace(activity.container, shell).commitNow();
        Shots.settle();
        assertEquals("screen_storage", shell.currentSection());

        shell.showRow("screen_recording", "pref_record_quality", null);
        Shots.settle();
        assertEquals("screen_recording", shell.currentSection());
    }

    private void shoot(String qualifiers, String tag) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SettingsShellFragment shell = new SettingsShellFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(activity.container, shell).commitNow();
        Shots.settle();
        for (String section : SECTIONS) {
            shell.showSection(section);
            File file = Shots.save(activity, "settings-" + section.substring("screen_".length()) + "-" + tag);
            assertTrue(file + " 是空的", file.length() > 10_000);
        }
    }
}
