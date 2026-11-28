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

import android.content.Context;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.screens.ShotActivity;
import com.kooo.evcam.screens.Shots;
import com.kooo.evcam.speedlimit.LimitCatalog;
import com.kooo.evcam.speedlimit.LimitDownloads;
import com.kooo.evcam.speedlimit.LimitStore;
import com.kooo.evcam.speedlimit.WifiGate;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 「设置 → 按键」的「悬浮按钮显示限速」和「限速数据」那一页（项目所有者 2026-11-28 加在开发者选项，同日实测后移到「按键」）。
 * 不出网：看网络、取清单、下包都换成假的，
 * 后台线程换成当场跑的。装着的包是测试资源里那个（假造的路），只把包头的国家、日期改掉。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SpeedLimitShotsTest {

    private static final String SHA = "21e025c48b852be33d4c15f26d05ed540578ea37ff0272c6530e90b64a668225";
    /** Lab 11-28 估的大小（合并 + 抽稀后的中间值）。 */
    private static final String CATALOG = "{\"format\":1,\"packages\":["
            + entry("SG", 367001) + "," + entry("MY", 14155776) + "," + entry("AU", 23068672) + ","
            + entry("HK", 471859) + "," + entry("NZ", 5767168) + "," + entry("TH", 28311552) + "]}";

    /** 假的网络：出网地址前两段（null = 问不到；文档专用的段，不是谁的真网络）。 */
    private String exit = "203.0";
    /** 假的下载：null 成功，否则抛它。 */
    private IOException downloadFails;

    private static String entry(String country, long bytes) {
        return "{\"country\":\"" + country + "\",\"date\":\"2026-11-28\",\"file\":\"" + country + ".zslp\",\"bytes\":"
                + bytes + ",\"sha256\":\"" + SHA + "\"}";
    }

    @Before
    public void fakeTheNetwork() {
        // 平时的那个网络记过是 Wi-Fi
        new WifiGate(RuntimeEnvironment.getApplication()).teach(exit, true);
        SpeedLimitDataFragment.executor = Direct::new;
        SpeedLimitDataFragment.source = new SpeedLimitDataFragment.Source() {
            @Override
            public WifiGate.Result network(WifiGate gate) {
                return gate.judge(exit);
            }

            @Override
            public LimitCatalog catalog() throws IOException {
                return LimitCatalog.parse(CATALOG, LimitCatalog.URL);
            }

            @Override
            public void download(LimitCatalog.Entry entry, LimitStore store, LimitDownloads.Progress progress)
                    throws IOException {
                progress.onProgress(entry.bytes / 2, entry.bytes);
                if (downloadFails != null) {
                    throw downloadFails;
                }
                install(entry.country, 20261128);
            }
        };
    }

    @After
    public void restore() throws Exception {
        SpeedLimitDataFragment.executor = java.util.concurrent.Executors::newSingleThreadExecutor;
        java.lang.reflect.Field unlocked = DeveloperMode.class.getDeclaredField("unlocked");
        unlocked.setAccessible(true);
        unlocked.setBoolean(null, false);
    }

    /**
     * 「设置 → 按键」悬浮按钮那一组最后一行「悬浮按钮显示限速」：开关加下一层，默认关，行尾写装了哪几个，点这一行进「限速数据」；
     * 不归开发者选项管（没解锁也照存着的读）；悬浮按钮关着时收着。开发者选项里不再有这一组。
     */
    @Test
    public void keysRowByDay() throws Exception {
        keysRow(Shots.DAY, "day");
    }

    @Test
    public void keysRowInEnglish() throws Exception {
        keysRow(Shots.DAY_EN, "day-en");
    }

    private void keysRow(String qualifiers, String tag) throws Exception {
        Context app = RuntimeEnvironment.getApplication();
        AppConfig config = new AppConfig(app);
        assertFalse("默认关", config.isSpeedLimitOnButton());
        config.setRecordingFloatingEnabled(true);
        install("SG", 20261128);
        install("MY", 20261128);
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
        SwitchNavPreference row = section.findPreference("pref_speed_limit_on_button");
        assertNotNull(row);
        assertTrue("悬浮按钮开着时出现", row.isVisible());
        assertFalse(row.isChecked());
        assertEquals(SpeedLimitDataFragment.summary(activity), String.valueOf(row.getValue()));
        assertTrue("装着两个，不是「未下载」", !activity.getString(R.string.set_speed_limit_data_none)
                .equals(String.valueOf(row.getValue())));
        File file = save(activity, "settings-keys-speed-limit-" + tag);
        assertTrue(file + " 是空的", file.length() > 10_000);

        assertTrue(row.callChangeListener(true));
        assertTrue("不归开发者选项管：没解锁也照存着的读", new AppConfig(app).isSpeedLimitOnButton());

        // 点这一行（开关以外的地方）进「限速数据」，开关不动
        row.performClick();
        Shots.settle();
        assertTrue(shell.getChildFragmentManager().findFragmentById(R.id.settings_detail)
                instanceof SpeedLimitDataFragment);
        assertTrue(new AppConfig(app).isSpeedLimitOnButton());

        // 开发者选项里不再有这一组
        SettingsShellFragment dev = new SettingsShellFragment();
        DeveloperMode.init(app);
        java.lang.reflect.Field unlocked = DeveloperMode.class.getDeclaredField("unlocked");
        unlocked.setAccessible(true);
        unlocked.setBoolean(null, true);
        activity.getSupportFragmentManager().beginTransaction().replace(activity.container, dev).commitNow();
        Shots.settle();
        dev.showSection(SettingsShellFragment.DEVELOPER_SECTION);
        Shots.settle();
        SettingsPreferenceFragment developer = (SettingsPreferenceFragment)
                dev.getChildFragmentManager().findFragmentById(R.id.settings_detail);
        assertNotNull(developer);
        assertTrue(developer.findPreference("pref_speed_limit_on_button") == null);
        unlocked.setBoolean(null, false);
    }

    /** 悬浮按钮关着：那一行收着（同「点击」「长按」「外观」）。 */
    @Test
    public void keysRowHidesWithTheButton() throws Exception {
        new AppConfig(RuntimeEnvironment.getApplication()).setRecordingFloatingEnabled(false);
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
        Preference row = section.findPreference("pref_speed_limit_on_button");
        assertNotNull(row);
        assertFalse(row.isVisible());
    }

    @Test
    public void dataPageByDay() throws Exception {
        dataPage(Shots.DAY, "day");
    }

    @Test
    public void dataPageByNight() throws Exception {
        dataPage(Shots.NIGHT, "night");
    }

    @Test
    public void dataPageInEnglish() throws Exception {
        dataPage(Shots.DAY_EN, "day-en");
    }

    @Test
    public void dataPageInArabic() throws Exception {
        dataPage(Shots.DAY_AR, "day-ar");
    }

    /**
     * 新加坡装着、马来西亚装的是旧的（有更新）、香港上一次校验没过，别的没下；点澳大利亚就下、下完写「已下载」。
     */
    private void dataPage(String qualifiers, String tag) throws Exception {
        RuntimeEnvironment.setQualifiers(qualifiers);
        install("SG", 20261128);
        install("MY", 20261101);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SpeedLimitDataFragment page = open(activity);

        assertEquals(activity.getString(R.string.sl_network_wifi), summary(page, "sl_network"));
        assertEquals(activity.getString(R.string.sl_installed, "2026-11-28"), summary(page, "sl_country_SG"));
        assertEquals(activity.getString(R.string.sl_update, "2026-11-28",
                android.text.format.Formatter.formatShortFileSize(activity, 14155776)), summary(page, "sl_country_MY"));
        assertEquals(android.text.format.Formatter.formatShortFileSize(activity, 5767168), summary(page, "sl_country_NZ"));
        assertTrue("清单取到了，没有「正在获取」那一行", page.findPreference("sl_catalog") == null);

        downloadFails = new LimitDownloads.Unverified("digest", null);
        click(page, "sl_country_HK");
        assertEquals(activity.getString(R.string.sl_failed_verify), summary(page, "sl_country_HK"));
        assertTrue("没过的不启用", new LimitStore(activity).find("HK") == null);
        downloadFails = null;
        click(page, "sl_country_AU");
        assertEquals(activity.getString(R.string.sl_installed, "2026-11-28"), summary(page, "sl_country_AU"));
        File file = save(activity, "settings-speed-limit-data-" + tag);
        assertTrue(file + " 是空的", file.length() > 10_000);
        page.scrollToPreference("sl_note");
        save(activity, "settings-speed-limit-data-" + tag + "-end");
    }

    /** 没连 Wi-Fi：不取清单、不下；装着的照列。网络那一行能改记。 */
    @Test
    public void mobileDataDownloadsNothing() throws Exception {
        RuntimeEnvironment.setQualifiers(Shots.DAY);
        install("SG", 20261128);
        exit = "198.51";
        new WifiGate(RuntimeEnvironment.getApplication()).teach(exit, false);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SpeedLimitDataFragment page = open(activity);
        assertEquals(activity.getString(R.string.sl_network_mobile), summary(page, "sl_network"));
        assertEquals(activity.getString(R.string.sl_catalog_offline), summary(page, "sl_catalog"));
        assertEquals(activity.getString(R.string.sl_installed, "2026-11-28"), summary(page, "sl_country_SG"));
        File file = save(activity, "settings-speed-limit-data-mobile-day");
        assertTrue(file.length() > 10_000);

        // 其实连着 Wi-Fi：教一次，再看就是 Wi-Fi、清单取到了
        click(page, "sl_network");
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        Shots.save(dialog, "settings-speed-limit-network-dialog-day");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shots.settle();
        assertEquals(activity.getString(R.string.sl_network_wifi), summary(page, "sl_network"));
        assertTrue(page.findPreference("sl_country_AU") != null);

        // 下载前再看一次网络：这时没连 Wi-Fi 就不下
        new WifiGate(activity).teach(exit, false);
        click(page, "sl_country_AU");
        assertEquals(activity.getString(R.string.sl_failed_not_wifi), summary(page, "sl_country_AU"));
        assertEquals(activity.getString(R.string.sl_network_mobile), summary(page, "sl_network"));
    }

    /** 没见过的网络：打开这一页就问一次是不是 Wi-Fi；答之前不取清单、不下。 */
    @Test
    public void unseenNetworkIsAskedOnce() throws Exception {
        RuntimeEnvironment.setQualifiers(Shots.DAY);
        exit = "192.0";
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SpeedLimitDataFragment page = open(activity);
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("自己问了", dialog);
        assertEquals(activity.getString(R.string.sl_network_is_wifi),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertEquals(activity.getString(R.string.sl_network_not_wifi),
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        Shots.save(dialog, "settings-speed-limit-network-confirm-day");
        assertEquals(activity.getString(R.string.sl_network_unconfirmed), summary(page, "sl_network"));
        assertEquals(activity.getString(R.string.sl_catalog_unconfirmed), summary(page, "sl_catalog"));
        assertTrue("答之前没有清单", page.findPreference("sl_country_AU") == null);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shots.settle();
        assertEquals(activity.getString(R.string.sl_network_wifi), summary(page, "sl_network"));
        assertTrue(page.findPreference("sl_country_AU") != null);
    }

    /** 装着的点一下：有更新时「更新 / 删除」，没有时只有删除（红）。 */
    @Test
    public void installedRowOffersUpdateAndDelete() throws Exception {
        RuntimeEnvironment.setQualifiers(Shots.DAY);
        install("MY", 20261101);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SpeedLimitDataFragment page = open(activity);
        click(page, "sl_country_MY");
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertEquals(activity.getString(R.string.sl_action_update),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        Shots.save(dialog, "settings-speed-limit-manage-day");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shots.settle();
        assertEquals(activity.getString(R.string.sl_installed, "2026-11-28"), summary(page, "sl_country_MY"));

        click(page, "sl_country_MY");
        dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(activity.getString(R.string.action_delete),
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shots.settle();
        assertEquals("删了就是没下的样子", 0, new LimitStore(activity).installed().size());
        assertEquals(activity.getString(R.string.set_speed_limit_data_none), SpeedLimitDataFragment.summary(activity));
    }

    // ------------------------------------------------------------------ 帮手

    /** 这一页的行是拿掉重建的，新的一行淡入：等淡入放完再画（同权限设置页的截图）。 */
    private static File save(ShotActivity activity, String name) {
        Shots.settle();
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600));
        return Shots.save(activity, name);
    }

    private static SpeedLimitDataFragment open(ShotActivity activity) {
        SettingsShellFragment shell = new SettingsShellFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(activity.container, shell).commitNow();
        Shots.settle();
        shell.openDetail(new SpeedLimitDataFragment(), activity.getString(R.string.set_speed_limit_data_title));
        Shots.settle();
        SpeedLimitDataFragment page = (SpeedLimitDataFragment)
                shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
        assertNotNull(page);
        return page;
    }

    private static String summary(SpeedLimitDataFragment page, String key) {
        Preference p = page.findPreference(key);
        assertNotNull(key, p);
        return String.valueOf(p.getSummary());
    }

    private static void click(SpeedLimitDataFragment page, String key) {
        Preference p = page.findPreference(key);
        assertNotNull(key, p);
        p.getOnPreferenceClickListener().onPreferenceClick(p);
        Shots.settle();
    }

    /** 测试资源里的包，包头换成这个国家、这一天，放进应用的目录。 */
    private static void install(String country, int date) throws IOException {
        byte[] data = fixture();
        data[6] = (byte) country.charAt(0);
        data[7] = (byte) country.charAt(1);
        data[8] = (byte) date;
        data[9] = (byte) (date >>> 8);
        data[10] = (byte) (date >>> 16);
        data[11] = (byte) (date >>> 24);
        File dir = new File(RuntimeEnvironment.getApplication().getFilesDir(), "speed-limits");
        assertTrue(dir.isDirectory() || dir.mkdirs());
        try (FileOutputStream out = new FileOutputStream(new File(dir, country + ".zslp"))) {
            out.write(data);
        }
    }

    private static void delete(String country) {
        new LimitStore(RuntimeEnvironment.getApplication()).delete(country);
    }

    private static byte[] fixture() throws IOException {
        try (InputStream in = SpeedLimitShotsTest.class.getResourceAsStream("/speedlimit/fixture.zslp")) {
            assertNotNull(in);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int n;
            while ((n = in.read(chunk)) != -1) {
                out.write(chunk, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** 当场跑的「后台线程」：测试里不用等。 */
    private static final class Direct extends AbstractExecutorService {
        private boolean shut;

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public void shutdown() {
            shut = true;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shut = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shut;
        }

        @Override
        public boolean isTerminated() {
            return shut;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    }
}
