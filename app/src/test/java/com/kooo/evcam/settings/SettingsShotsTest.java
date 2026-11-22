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
            "screen_recording", "screen_storage", "screen_rearview", "screen_side_view", "screen_floating",
            "screen_general", "screen_update",
    };

    @Test
    public void everySectionByDay() {
        shoot(Shots.DAY, "day");
    }

    @Test
    public void everySectionByDayInEnglish() {
        shoot(Shots.DAY_EN, "day-en");
    }

    /**
     * 从右往左的语言：设置这类文字界面跟着镜像 —— 左栏到了右边（行尾的 › 翻过来看图）；
     * ① 标题区不镜像，菜单键还在左上、标题紧跟着（项目所有者 2026-11-19；ui-spec §十七）。
     */
    @Test
    public void everySectionRightToLeft() {
        rightToLeft(shoot(Shots.DAY_RTL, "day-rtl"));
    }

    /** 阿拉伯语：同上，文字是阿拉伯文、数字 0–9。 */
    @Test
    public void everySectionInArabic() {
        rightToLeft(shoot(Shots.DAY_AR, "day-ar"));
    }

    private static void rightToLeft(ShotActivity activity) {
        int[] headers = new int[2];
        int[] detail = new int[2];
        activity.findViewById(R.id.settings_headers).getLocationInWindow(headers);
        activity.findViewById(R.id.settings_detail).getLocationInWindow(detail);
        assertTrue("左栏到了右边：" + headers[0] + " / " + detail[0], headers[0] > detail[0]);
        int[] nav = new int[2];
        int[] title = new int[2];
        activity.findViewById(R.id.settings_nav).getLocationInWindow(nav);
        activity.findViewById(R.id.settings_title).getLocationInWindow(title);
        assertTrue("菜单键在左上：" + nav[0], nav[0] < headers[0] / 4);
        assertTrue("标题紧跟在菜单键右边：" + nav[0] + " / " + title[0], nav[0] < title[0]);
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
        shoot(Shots.DAY, "day-on");
    }

    @Test
    public void everySectionWithEverythingOnInEnglish() {
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setRearViewEnabled(true);
        config.setSideViewEnabled(true);
        config.setDriveSoundEnabled(true);
        shoot(Shots.DAY_EN, "day-on-en");
    }

    /**
     * 「按键」页里绑定的灵动贴（项目所有者 2026-11-14「名称 / 位置」）：起了名字的显示名字、默认名（带短编号）挪到下面那一行；
     * 没起的照旧是默认名。存着的名字再怪（这里直接往存储里塞一段带标签、格式符、换行、方向控制符的长字，像是从改过的
     * 备份里来的），显示出来也只是最多 20 个字的一行字。地址是随便编的。
     */
    @Test
    public void namedSmartButtons() {
        namedSmartButtons(Shots.DAY, "");
    }

    @Test
    public void namedSmartButtonsInEnglish() {
        namedSmartButtons(Shots.DAY_EN, "-en");
    }

    @Test
    public void knobRows() {
        knobRows(Shots.DAY, "");
    }

    @Test
    public void knobRowsInEnglish() {
        knobRows(Shots.DAY_EN, "-en");
    }

    private void knobRows(String qualifiers, String suffix) {
        android.content.Context app = RuntimeEnvironment.getApplication();
        AppConfig config = new AppConfig(app);
        String button = "00:11:22:33:44:55";
        String knob = "66:77:88:99:AA:BB";
        config.addAccessory(com.kooo.evcam.button.Accessory.BUTTON, button);
        config.addAccessory(com.kooo.evcam.button.Accessory.KNOB, knob);

        RuntimeEnvironment.setQualifiers(qualifiers);
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

        assertNotNull(section.findPreference("cat_smart_buttons"));
        assertNotNull(section.findPreference("pref_smart_buttons"));
        assertNotNull(section.findPreference("pref_smart_button_bind"));
        org.junit.Assert.assertNull(section.findPreference("cat_x_knobs"));
        org.junit.Assert.assertNull(section.findPreference("pref_x_knobs"));
        Preference row = section.findPreference("x_knob." + knob);
        assertNotNull(row);
        assertEquals(activity.getString(R.string.x_knob_name, com.kooo.evcam.button.SmartButtonAd.shortId(knob)),
                String.valueOf(row.getTitle()));
        String summary = String.valueOf(row.getSummary());
        assertTrue(summary.contains(activity.getString(R.string.knob_fn_volume)));
        assertNotNull(section.findPreference("smart_button." + button));

        section.scrollToPreference("x_knob." + knob);
        File file = Shots.save(activity, "settings-floating-day-knob" + suffix);
        assertTrue(file.length() > 10_000);
        Preference status = section.findPreference("x_knob." + knob + "/status");
        assertNotNull(status);
        assertFalse(status.isVisible());

        Preference buttonRow = section.findPreference("smart_button." + button);
        buttonRow.getOnPreferenceClickListener().onPreferenceClick(buttonRow);
        Shots.settle();
        assertFalse(section.findPreference("smart_button." + button + "/status").isVisible());
        assertTrue(section.findPreference("smart_button." + button + "/name").isVisible());
        assertTrue(section.findPreference("smart_button." + button + "/2").isVisible());
        section.scrollToPreference("smart_button." + button + "/2");
        Shots.settle();
        File buttonOpen = Shots.save(activity, "settings-floating-day-button-open" + suffix);
        assertTrue(buttonOpen.length() > 10_000);
        buttonRow.getOnPreferenceClickListener().onPreferenceClick(buttonRow);
        Shots.settle();
        assertFalse(section.findPreference("smart_button." + button + "/name").isVisible());

        row.getOnPreferenceClickListener().onPreferenceClick(row);
        Shots.settle();
        assertTrue(status.isVisible());
        assertTrue(String.valueOf(status.getSummary()).contains(activity.getString(R.string.x_knob_mode_normal)));
        Preference turnRow = section.findPreference("x_knob." + knob + "/turn");
        Preference homeRow = section.findPreference("x_knob." + knob + "/home");
        assertTrue(turnRow.isVisible());
        assertFalse(homeRow.isVisible());
        section.scrollToPreference("x_knob." + knob + "/turn");
        Shots.settle();
        File open = Shots.save(activity, "settings-floating-day-knob-open" + suffix);
        assertTrue(open.length() > 10_000);

        turnRow.getOnPreferenceClickListener().onPreferenceClick(turnRow);
        Shots.settle();
        android.app.Dialog turn = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertNotNull(turn);
        assertEquals(1 + com.kooo.evcam.button.KnobTurn.groups().indexOf(com.kooo.evcam.button.ButtonAction.Group.MEDIA),
                ((androidx.appcompat.app.AlertDialog) turn).getListView().getCheckedItemPosition());
        File turnFile = Shots.save(turn, "settings-floating-day-knob-turn" + suffix);
        turn.dismiss();
        assertTrue(turnFile.length() > 10_000);

        CardItemPreference exclusive = section.findPreference("x_knob." + knob + "/exclusive");
        assertNotNull(exclusive);
        assertFalse(exclusive.isChecked());
        exclusive.onClick();
        Shots.settle();
        assertTrue(config.isKnobExclusive(knob));
        assertFalse(turnRow.isVisible());
        assertTrue(homeRow.isVisible());
        section.scrollToPreference("x_knob." + knob + "/home");
        Shots.settle();
        File openExclusive = Shots.save(activity, "settings-floating-day-knob-open-exclusive" + suffix);
        assertTrue(openExclusive.length() > 10_000);

        homeRow.getOnPreferenceClickListener().onPreferenceClick(homeRow);
        Shots.settle();
        android.app.Dialog home = org.robolectric.shadows.ShadowDialog.getLatestDialog();
        org.junit.Assert.assertNotSame(turn, home);
        File homeFile = Shots.save(home, "settings-floating-day-knob-home" + suffix);
        android.util.SparseBooleanArray picks = ((androidx.appcompat.app.AlertDialog) home).getListView()
                .getCheckedItemPositions();
        java.util.List<com.kooo.evcam.button.KnobTurn> catalog = new java.util.ArrayList<>(
                java.util.Arrays.asList(com.kooo.evcam.button.KnobTurn.values()));
        catalog.remove(com.kooo.evcam.button.KnobTurn.NONE);
        for (int i = 0; i < 8; i++) {
            assertEquals(catalog.get(i).key, com.kooo.evcam.button.KnobTurn.home().contains(catalog.get(i)),
                    picks.get(i));
        }
        home.dismiss();
        assertTrue(homeFile.length() > 10_000);

        row.getOnPreferenceClickListener().onPreferenceClick(row);
        Shots.settle();
        assertFalse(status.isVisible());
        assertFalse(homeRow.isVisible());
        config.setKnobExclusive(knob, false);
    }

    private void namedSmartButtons(String qualifiers, String suffix) {
        android.content.Context app = RuntimeEnvironment.getApplication();
        AppConfig config = new AppConfig(app);
        String first = "00:11:22:33:44:55";
        String second = "66:77:88:99:AA:BB";
        String third = "CC:DD:EE:FF:00:11";
        config.addAccessory(com.kooo.evcam.button.Accessory.BUTTON, first);
        config.addAccessory(com.kooo.evcam.button.Accessory.BUTTON, second);
        config.addAccessory(com.kooo.evcam.button.Accessory.BUTTON, third);
        config.setAccessoryName(com.kooo.evcam.button.Accessory.BUTTON, first, "方向盘左");
        app.getSharedPreferences(AppConfig.PREF_NAME, android.content.Context.MODE_PRIVATE).edit()
                .putString("smart_button_name." + second, "<b>%s</b>\n\n\u202E" + "很长的名字".repeat(30)).commit();

        RuntimeEnvironment.setQualifiers(qualifiers);
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
        File file = Shots.save(activity, "settings-floating-day-names" + suffix);
        assertTrue(file + " 是空的", file.length() > 10_000);
    }

    /**
     * 「通用」的「模拟声浪」一组：听感、转速变化方式、变速箱所有人都有（4.0.0 收进过开发者选项，项目所有者 2026-11-13
     * 上车后要回来）；听感、变速箱跟着声音风格出现。这一组在第一屏下面，滚到它另出一张图。
     */
    @Test
    public void driveSoundRowsAreThereForEveryone() {
        driveSoundRows(Shots.DAY, "");
    }

    @Test
    public void driveSoundRowsInEnglish() {
        driveSoundRows(Shots.DAY_EN, "-en");
    }

    private void driveSoundRows(String qualifiers, String suffix) {
        assertFalse("这一条看的是开发者选项关着时", DeveloperMode.isUnlocked());
        new AppConfig(RuntimeEnvironment.getApplication()).setDriveSoundEnabled(true);
        RuntimeEnvironment.setQualifiers(qualifiers);
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
        File file = Shots.save(activity, "settings-general-day-sound" + suffix);
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

    /**
     * 测试环境里没有外置存储目录，系统查「所有文件访问权限」的那一句会抛（{@code Environment.isExternalStorageManager}
     * 取第一个外置目录）：这里照「没给」答。
     */
    @org.robolectric.annotation.Implements(android.os.Environment.class)
    public static class NoAllFilesAccess extends org.robolectric.shadows.ShadowEnvironment {
        @org.robolectric.annotation.Implementation
        protected static boolean isExternalStorageManager() {
            return false;
        }
    }

    /**
     * 开发者选项 → 权限设置：「忽略电池优化」灰着、点不动（项目所有者 2026-11-21「变灰」）—— 停车时应用交给车机处置，
     * 不再带人去给这一条；名字和状态那一行照旧。别的几条不变：没给的能点。
     */
    @Test
    @Config(shadows = NoAllFilesAccess.class)
    public void batteryOptimisationRowIsGreyedOut() {
        permissionsPage(Shots.DAY, "day");
    }

    @Test
    @Config(shadows = NoAllFilesAccess.class)
    public void batteryOptimisationRowIsGreyedOutByNight() {
        permissionsPage(Shots.NIGHT, "night");
    }

    @Test
    @Config(shadows = NoAllFilesAccess.class)
    public void batteryOptimisationRowIsGreyedOutInEnglish() {
        permissionsPage(Shots.DAY_EN, "day-en");
    }

    private void permissionsPage(String qualifiers, String tag) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SettingsShellFragment shell = new SettingsShellFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(activity.container, shell).commitNow();
        Shots.settle();
        shell.openDetail(new PermissionsPreferenceFragment(), activity.getString(R.string.dev_permissions_title));
        Shots.settle();
        PermissionsPreferenceFragment page = (PermissionsPreferenceFragment)
                shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
        assertNotNull(page);

        Preference battery = permissionRow(page, activity.getString(R.string.dev_perm_battery));
        assertNotNull("少了「忽略电池优化」那一行", battery);
        assertFalse("灰着", battery.isEnabled());
        assertFalse("点不动", battery.isSelectable());
        org.junit.Assert.assertNull("不带人去系统设置", battery.getOnPreferenceClickListener());
        assertEquals("状态那一行照旧", activity.getString(R.string.dev_perm_not_granted,
                activity.getString(R.string.dev_perm_battery_why)), String.valueOf(battery.getSummary()));
        Preference overlay = permissionRow(page, activity.getString(R.string.dev_perm_overlay));
        assertNotNull(overlay);
        assertTrue("别的没给的照旧能点", overlay.isEnabled() && overlay.isSelectable());
        assertNotNull(overlay.getOnPreferenceClickListener());
        // 回到前台重建过的行也是别的设置页那种卡片：能点的行尾 ›，点不动的（灰着的、已给的）没有
        assertEquals(R.layout.pref_row_nav, overlay.getLayoutResource());
        assertEquals(R.layout.pref_row_info, battery.getLayoutResource());

        // 这一页回到前台时把各行拿掉重建一遍（授权是去系统设置里点的），新的一行是淡入的：先排一遍，等淡入放完再画。
        // 那一行在最下面，滚到它
        Shots.save(activity, "settings-permissions-" + tag);
        page.scrollToPreference(battery);
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
        File file = Shots.save(activity, "settings-permissions-" + tag);
        assertTrue(file + " 是空的", file.length() > 10_000);
    }

    /** 权限设置页的行没有 key，照名字找。 */
    private static Preference permissionRow(PermissionsPreferenceFragment page, String title) {
        androidx.preference.PreferenceScreen screen = page.getPreferenceScreen();
        for (int i = 0; i < screen.getPreferenceCount(); i++) {
            androidx.preference.PreferenceGroup group = (androidx.preference.PreferenceGroup) screen.getPreference(i);
            for (int j = 0; j < group.getPreferenceCount(); j++) {
                Preference row = group.getPreference(j);
                if (title.equals(String.valueOf(row.getTitle()))) {
                    return row;
                }
            }
        }
        return null;
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

    private ShotActivity shoot(String qualifiers, String tag) {
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
        return activity;
    }
}
