/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import com.kooo.evcam.AppConfig;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * 灵动贴的「名称 / 位置」怎么存、怎么读（{@link AppConfig#getAccessoryName}）：写进去之前洗，读出来时再洗 ——
 * 存着的值可能来自导入的备份、被人改过的文件，不指望它是洗过的。地址是随便编的。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
public class SmartButtonNameTest {

    private static final String FIRST = "00:11:22:33:44:55";
    private static final String SECOND = "66:77:88:99:AA:BB";
    private static final String KEY = "smart_button_name." + FIRST;

    private AppConfig config;
    private SharedPreferences prefs;

    @Before
    public void setUp() {
        Context context = RuntimeEnvironment.getApplication();
        config = new AppConfig(context);
        prefs = context.getSharedPreferences(AppConfig.PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().clear().commit();
        config.addAccessory(Accessory.BUTTON, FIRST);
        config.addAccessory(Accessory.BUTTON, SECOND);
    }

    @Test
    public void noNameUntilTheUserGivesOne() {
        assertEquals("", config.getAccessoryName(Accessory.BUTTON, FIRST));
        assertFalse(prefs.contains(KEY));
    }

    @Test
    public void aNameIsStoredPerButton() {
        config.setAccessoryName(Accessory.BUTTON, FIRST, "方向盘左");
        assertEquals("方向盘左", config.getAccessoryName(Accessory.BUTTON, FIRST));
        assertEquals("另一台不受影响", "", config.getAccessoryName(Accessory.BUTTON, SECOND));
        assertEquals("方向盘左", prefs.getString(KEY, null));
    }

    /** 留空、全是空白、全是看不见的字符：这一项删掉，回到默认名。 */
    @Test
    public void anEmptyNameGoesBackToTheDefault() {
        config.setAccessoryName(Accessory.BUTTON, FIRST, "方向盘左");
        config.setAccessoryName(Accessory.BUTTON, FIRST, "   ");
        assertEquals("", config.getAccessoryName(Accessory.BUTTON, FIRST));
        assertFalse(prefs.contains(KEY));
        config.setAccessoryName(Accessory.BUTTON, FIRST, "x");
        config.setAccessoryName(Accessory.BUTTON, FIRST, null);
        assertFalse(prefs.contains(KEY));
        config.setAccessoryName(Accessory.BUTTON, FIRST, "​‮\u0000");
        assertFalse(prefs.contains(KEY));
    }

    /** 写进去之前洗：超长的截掉，换行变空格，看不见的丢掉 —— 存下的就是洗过的。 */
    @Test
    public void whatIsStoredIsAlreadyClean() {
        config.setAccessoryName(Accessory.BUTTON, FIRST, "  第一行\n第二行‮\u0000 " + "很长".repeat(40));
        String stored = prefs.getString(KEY, null);
        assertEquals(stored, DeviceLabel.clean(stored));
        assertTrue(stored.codePointCount(0, stored.length()) <= DeviceLabel.MAX);
        assertTrue(stored.startsWith("第一行 第二行 很长"));
        assertFalse(stored.contains("\n"));
    }

    /**
     * 读出来时再洗：存着的值不是从这个应用的输入框来的（导入的备份、改过的文件）—— 一段带换行、带方向控制符的长字，
     * 拿到手的还是最多 20 个字的一行。
     */
    @Test
    public void whatIsReadIsCleanEvenIfTheStoredValueIsNot() {
        String hostile = "ok\n\n‮gnp.exe\u0000" + "A".repeat(5000);
        prefs.edit().putString(KEY, hostile).commit();
        String read = config.getAccessoryName(Accessory.BUTTON, FIRST);
        assertTrue(read.codePointCount(0, read.length()) <= DeviceLabel.MAX);
        assertEquals("ok gnp.exeAAAAAAAAAA", read);
        assertEquals(read, DeviceLabel.clean(read));
    }

    /** 这个键上存的不是字（被人改成了数、开关、一组字）：按没起名算，不崩。 */
    @Test
    public void aValueOfTheWrongTypeCountsAsNoName() {
        prefs.edit().putInt(KEY, 42).commit();
        assertEquals("", config.getAccessoryName(Accessory.BUTTON, FIRST));
        prefs.edit().putBoolean(KEY, true).commit();
        assertEquals("", config.getAccessoryName(Accessory.BUTTON, FIRST));
        prefs.edit().putStringSet(KEY, new java.util.HashSet<>(java.util.Arrays.asList("a", "b"))).commit();
        assertEquals("", config.getAccessoryName(Accessory.BUTTON, FIRST));
        // 还能照常起名
        config.setAccessoryName(Accessory.BUTTON, FIRST, "后排");
        assertEquals("后排", config.getAccessoryName(Accessory.BUTTON, FIRST));
    }

    /**
     * 导出设置时它是 JSON 里的一个字符串值：引号、反斜杠由 JSON 库转义，读回来一字不差，结构不会被它改掉
     * （一个想冒充别的键的名字，出来还是一个名字）。
     */
    @Test
    public void aNameSurvivesTheSettingsBackupAsPlainText() throws Exception {
        config.setAccessoryName(Accessory.BUTTON, FIRST, "\",\"admin\":true,\"x\":\"\\");
        String name = config.getAccessoryName(Accessory.BUTTON, FIRST);
        assertFalse(name.isEmpty());
        org.json.JSONObject out = new org.json.JSONObject();
        out.put("name", name);
        org.json.JSONObject back = new org.json.JSONObject(out.toString());
        assertEquals(name, back.getString("name"));
        assertEquals("没多出别的键", 1, back.length());
        // 整份设置导出来、当 JSON 读回去，这个名字原样在里面，是一个字符串值
        org.json.JSONObject snapshot = new org.json.JSONObject(
                com.kooo.evcam.settings.SettingsBackup.snapshot(RuntimeEnvironment.getApplication()).toString());
        assertTrue("导出的设置里有这个名字", holds(snapshot, name));
    }

    /** 这棵 JSON 里有没有哪个字符串值正好是它。 */
    private static boolean holds(Object node, String wanted) throws org.json.JSONException {
        if (node instanceof org.json.JSONObject) {
            org.json.JSONObject object = (org.json.JSONObject) node;
            for (java.util.Iterator<String> keys = object.keys(); keys.hasNext(); ) {
                if (holds(object.get(keys.next()), wanted)) {
                    return true;
                }
            }
            return false;
        }
        if (node instanceof org.json.JSONArray) {
            org.json.JSONArray array = (org.json.JSONArray) node;
            for (int i = 0; i < array.length(); i++) {
                if (holds(array.get(i), wanted)) {
                    return true;
                }
            }
            return false;
        }
        return wanted.equals(node);
    }

    /** 删掉一台，它的名字跟着删；别的不动。 */
    @Test
    public void forgettingAButtonForgetsItsName() {
        config.setAccessoryName(Accessory.BUTTON, FIRST, "方向盘左");
        config.setAccessoryName(Accessory.BUTTON, SECOND, "后排");
        config.removeAccessory(Accessory.BUTTON, FIRST);
        assertFalse(prefs.contains(KEY));
        assertEquals("", config.getAccessoryName(Accessory.BUTTON, FIRST));
        assertEquals("后排", config.getAccessoryName(Accessory.BUTTON, SECOND));
    }
}
