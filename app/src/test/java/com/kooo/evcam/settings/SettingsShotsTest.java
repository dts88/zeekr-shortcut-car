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
    public void everySectionByDayInEnglish() {
        shoot(Shots.DAY_EN, "day-en");
    }

    /** 从右往左的语言：设置这类文字界面跟着镜像 —— 左栏到了右边（行尾的 › 翻过来看图；ui-spec §十七）。 */
    @Test
    public void everySectionRightToLeft() {
        ShotActivity activity = shoot(Shots.DAY_RTL, "day-rtl");
        int[] headers = new int[2];
        int[] detail = new int[2];
        activity.findViewById(R.id.settings_headers).getLocationInWindow(headers);
        activity.findViewById(R.id.settings_detail).getLocationInWindow(detail);
        assertTrue("左栏到了右边：" + headers[0] + " / " + detail[0], headers[0] > detail[0]);
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

    @Test
    public void everySectionWithEverythingOnInEnglish() {
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
     * 「忘了开哨兵」那三行在「什么时候发」那一组里，排在安静时段之后；只跟着警报总开关走，
     * 换到 ntfy 也还在（它们不是 Telegram 独有的）。滚到它们另出一张图。
     *
     * <p>三种状态各开一屏新的设置页来验：总开关开着（在、位置对）、换成 ntfy（还在）、
     * 总开关关着（三行一起收起来）。</p>
     *
     * The three Forgot Sentry rows sit in the "When it fires" group after quiet hours, follow the master
     * switch only, and stay when the transport is ntfy. Each state is checked on a freshly opened page.
     */
    @Test
    public void forgotSentryRowsSitUnderWhenItFires() {
        SettingsPreferenceFragment on = alertsPage(true, com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM);
        androidx.preference.PreferenceGroup when = (androidx.preference.PreferenceGroup)
                on.findPreference("cat_alerts_when");
        assertNotNull("少了「什么时候发」那一组", when);
        for (String key : GUARD_ROWS) {
            Preference row = on.findPreference(key);
            assertNotNull("少了 " + key, row);
            assertTrue(key + " 该在「什么时候发」那一组里", row.getParent() == when);
            assertTrue(key + " 该看得见", row.isVisible());
            assertTrue(key + " 该排在安静时段之后",
                    indexIn(when, key) > indexIn(when, "pref_alerts_quiet_end"));
        }
        on.scrollToPreference("pref_alerts_guard_everywhere");
        File file = Shots.save(shotActivity, "settings-alerts-day-guard");
        assertTrue(file + " 是空的", file.length() > 10_000);

        // 换成 ntfy：两条出口的组互相换位置，但那三行不动 —— 它们说的和发到哪儿无关。
        // Switch to ntfy: the two transport blocks swap, the three rows stay — they are not exit-specific.
        SettingsPreferenceFragment ntfy = alertsPage(true, com.kooo.evcam.alarm.ntfy.NtfyGate.NTFY);
        assertTrue("ntfy 时「哪一组出现」照旧", ntfy.findPreference("cat_alerts_ntfy").isVisible());
        for (String key : GUARD_ROWS) {
            assertTrue("ntfy 时 " + key + " 还在", ntfy.findPreference(key).isVisible());
        }

        // 总开关关着：三行一起收起来。Master off: all three go with it.
        SettingsPreferenceFragment off = alertsPage(false, com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM);
        for (String key : GUARD_ROWS) {
            assertFalse("总开关关着时 " + key + " 该收起来", off.findPreference(key).isVisible());
        }
    }

    private static final String[] GUARD_ROWS =
            {"pref_alerts_guard", "pref_alerts_guard_home", "pref_alerts_guard_everywhere"};

    /**
     * 「警报附一张照片」关掉之后，下面三行跟着收起来。
     *
     * <p>排版（一张四宫格 / 每路一张）、那几路、掰直 —— 不拍照的时候三行都没有意义，留着只会让人以为
     * 自己在设置里能改到已经不發生的事。片段那两行不动：它是聊天里点出来的，跟这条警报带不带图无关。</p>
     *
     * <p>开关本身还在，而且跟着存着的值：关掉之后重新进来，它就该是关的。</p>
     *
     * With "send a picture with each alert" off, the three rows below it collapse: layout, which lanes,
     * and straightening all mean nothing when no picture is taken. The clip rows stay — those are asked
     * for from the chat. The switch itself stays and shows the stored value.
     */
    @Test
    public void thePictureSwitchCollapsesTheRowsBelowIt() {
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);
        SettingsPreferenceFragment on = alertsPage(true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, config -> {
                    config.setAlertsPictureEnabled(true);
                    config.setAlertsLayout(com.kooo.evcam.alarm.media.AlertPicture.layoutValue(
                            com.kooo.evcam.alarm.media.AlertPicture.Layout.SEPARATE));
                    config.setAlertsClipSeconds(5);
                });
        assertTrue("开着时该看得见", shown(on, "pref_alerts_send_picture"));
        assertTrue("排版该在", shown(on, "pref_alerts_picture"));
        assertTrue("每路一张时那几路该在", shown(on, "pref_alerts_cameras"));
        assertTrue("掰直该在", shown(on, "pref_alerts_straighten"));

        SettingsPreferenceFragment off = alertsPage(true,
                com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, config -> {
                    config.setAlertsPictureEnabled(false);
                    config.setAlertsClipSeconds(5);
                });
        androidx.preference.Preference switchRow = off.findPreference("pref_alerts_send_picture");
        assertNotNull("少了附图那一行", switchRow);
        assertTrue("开关本身要留着", switchRow.isVisible());
        assertFalse("存着关，开关就该是关的",
                ((androidx.preference.SwitchPreferenceCompat) switchRow).isChecked());
        assertFalse("不拍照就不该有排版", shown(off, "pref_alerts_picture"));
        assertFalse("不拍照就不该有那几路", shown(off, "pref_alerts_cameras"));
        assertFalse("不拍照就不该有掰直", shown(off, "pref_alerts_straighten"));
        assertTrue("片段是聊天里点的，跟这条不图无关", shown(off, "pref_alerts_clip"));

        // 收尾：这几项是存在同一份设置里的，别把后面的测试带跑。
        // Clean up: these live in the shared settings file.
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setAlertsPictureEnabled(true);
        config.setAlertsClipSeconds(0);
        config.setAlertsLayout(com.kooo.evcam.alarm.media.AlertPicture.layoutValue(
                com.kooo.evcam.alarm.media.AlertPicture.Layout.GRID));
    }

    /**
     * ntfy 的换话题那一行：走 ntfy 就在，走 Telegram 就不在。
     *
     * The ntfy topic row: present when ntfy is the exit, absent when Telegram is.
     */
    @Test
    public void ntfyTopicRowFollowsTheTransport() {
        android.content.Context context = RuntimeEnvironment.getApplication();
        try {
            DeveloperMode.lock(context);
            SettingsPreferenceFragment ntfy = alertsPage(true,
                    com.kooo.evcam.alarm.ntfy.NtfyGate.NTFY, config -> {
                        config.setAlertsNtfyPaid(false);
                    });
            assertTrue("换话题该看得见", shown(ntfy, "pref_alerts_ntfy_rotate"));
            assertTrue("免费档要把公开话题那句写出来", shown(ntfy, "pref_alerts_ntfy_public_warning"));

            // Telegram：那一组整个不出现，换话题也不在。
            //    Telegram: the whole block is gone, so the row does not show.
            SettingsPreferenceFragment telegram = alertsPage(true,
                    com.kooo.evcam.alarm.ntfy.NtfyGate.TELEGRAM, config -> {
                    });
            assertFalse("走 Telegram 时换话题不该出现", shown(telegram, "pref_alerts_ntfy_rotate"));
        } finally {
            DeveloperMode.lock(context);
        }
    }

    private ShotActivity shotActivity;

    /** 照着存好的状态开一屏新的「停车警报」。一条按存的这个状态绑出来的设置页。 */
    private SettingsPreferenceFragment alertsPage(boolean alertsOn, String transport) {
        return alertsPage(alertsOn, transport, config -> {
        });
    }

    /** 同上，但在建页之前改一点设置（付费档、命令开关）。The same page, with one tweak before it is built. */
    private SettingsPreferenceFragment alertsPage(boolean alertsOn, String transport,
                                                  java.util.function.Consumer<AppConfig> tweak) {
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setAlertsEnabled(alertsOn);
        config.setAlertsTransport(transport);
        config.setAlertsTelegramToken("123456789:AAHexampleTOKENvalue0123456789");
        config.setAlertsTelegramChatId("123456789");
        config.setAlertsQuietEnabled(true);
        tweak.accept(config);
        RuntimeEnvironment.setQualifiers(Shots.DAY_EN);
        shotActivity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(shotActivity);
        SettingsShellFragment shell = new SettingsShellFragment();
        shotActivity.getSupportFragmentManager().beginTransaction()
                .replace(shotActivity.container, shell).commitNow();
        Shots.settle();
        shell.showSection("screen_alerts");
        Shots.settle();
        SettingsPreferenceFragment section = (SettingsPreferenceFragment) shell.getChildFragmentManager()
                .findFragmentById(R.id.settings_detail);
        assertNotNull("「停车警报」那一屏", section);
        return section;
    }

    /** 这一行在组里的第几行。Where a row sits inside its group. */
    private static int indexIn(androidx.preference.PreferenceGroup group, String key) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            if (key.equals(group.getPreference(i).getKey())) {
                return i;
            }
        }
        return -1;
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
