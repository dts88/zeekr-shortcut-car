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

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.automation.Change;
import com.kooo.evcam.automation.Condition;
import com.kooo.evcam.automation.Place;
import com.kooo.evcam.automation.PlaceStore;
import com.kooo.evcam.automation.Rule;
import com.kooo.evcam.automation.RuleStore;
import com.kooo.evcam.automation.Setting;
import com.kooo.evcam.automation.ShareCode;
import com.kooo.evcam.automation.Trigger;
import com.kooo.evcam.screens.ShotActivity;
import com.kooo.evcam.screens.Shots;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 分享码（ui-spec §十八「分享码」）：展开的一条最后一行「分享码」、「分享这条自动化」框（二维码扫出来是网站的网址 + 码）、
 * 不能分享的那一行；「添加自动化」→「输入分享码」：边打边分组、预览、给编号选地点、码不对、地点不够。白天 / 英文 / 夜间 / 阿拉伯文。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AutomationShareShotsTest {

    private static final Place HOME = Place.of("home", "家", 10d, 20d, 600);
    private static final Place WORK = Place.of("work", "公司", 10.05d, 20.02d, 350);
    /** 到达地点，挡位是 P → 尾门开启上限 80%（共用用例里的第一条）。 */
    private static final String TAILGATE = "1A1G0C580963";
    /** 上车，在地点甲或乙，不在地点丙 → 陡坡缓降开启、外循环（用到三个地点）。 */
    private static final String THREE_PLACES = "1EP12X3C2141292";

    static List<Rule> rules() {
        Rule tailgate = new Rule("s1", "", true, Trigger.arrive("home"),
                Collections.singletonList(Condition.gearIs("P")),
                Collections.singletonList(Change.of(Setting.TAILGATE_LIMIT, "80")), false);
        // 两个不一样的「在…」：不能分享
        Rule twice = new Rule("s2", "", true, Trigger.getIn(),
                Arrays.asList(Condition.inPlaces(Collections.singletonList("home")),
                        Condition.inPlaces(Arrays.asList("home", "work"))),
                Collections.singletonList(Change.of(Setting.SENTRY, "on")), false);
        return Arrays.asList(tailgate, twice);
    }

    @Test
    public void byDay() throws Exception {
        shoot(Shots.DAY, "day", true);
    }

    @Test
    public void inEnglish() throws Exception {
        shoot(Shots.DAY_EN, "day-en", true);
    }

    @Test
    public void byNight() throws Exception {
        shoot(Shots.NIGHT, "night", false);
    }

    @Test
    public void inArabic() throws Exception {
        shoot(Shots.DAY_AR, "day-ar", false);
    }

    private void shoot(String qualifiers, String tag, boolean all) throws Exception {
        AppConfig config = new AppConfig(RuntimeEnvironment.getApplication());
        config.setAutomationPlacesAndRules(PlaceStore.format(Arrays.asList(HOME, WORK)), RuleStore.format(rules()));

        RuntimeEnvironment.setQualifiers(qualifiers);
        ShotActivity activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        SettingsShellFragment shell = new SettingsShellFragment();
        activity.getSupportFragmentManager().beginTransaction().replace(activity.container, shell).commitNow();
        Shots.settle();
        shell.showSection("screen_automation");
        Shots.settle();
        SettingsPreferenceFragment section = (SettingsPreferenceFragment)
                shell.getChildFragmentManager().findFragmentById(R.id.settings_detail);
        assertNotNull(section);

        // 展开尾门那条：最后一行是分享码，4 个一组
        click(section.findPreference("automation_rule_s1"));
        ClauseItemPreference share = section.findPreference("automation_rule_s1/share");
        assertNotNull(share);
        assertTrue(share.isVisible());
        assertTrue(share.isEnabled());
        assertEquals(ShareCode.display(TAILGATE), String.valueOf(share.getTitle()));
        assertEquals(activity.getString(R.string.auto_share_label), String.valueOf(share.getLabel()));
        section.scrollToPreference("automation_rule_s1/share");
        Shots.settle();
        save(activity, "settings-share-" + tag + "-row");

        // 点它：二维码扫出来是网站的网址 + 码，下面是码
        click(share);
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        Bitmap qr = qrIn(dialog.getWindow().getDecorView());
        assertNotNull("二维码", qr);
        assertEquals(AutomationShare.LINK + TAILGATE, scan(qr));
        assertTrue(texts(dialog.getWindow().getDecorView()).contains(ShareCode.display(TAILGATE)));
        save(dialog, "settings-share-" + tag + "-dialog");
        dialog.dismiss();

        // 两个「在…」的那条：写为什么、灰着
        click(section.findPreference("automation_rule_s2"));
        ClauseItemPreference refused = section.findPreference("automation_rule_s2/share");
        assertFalse(refused.isEnabled());
        assertEquals(activity.getString(R.string.auto_share_place_conditions), String.valueOf(refused.getTitle()));
        section.scrollToPreference("automation_rule_s2/share");
        Shots.settle();
        save(activity, "settings-share-" + tag + "-refused");

        // 添加自动化 → 最后一项「输入分享码」
        AlertDialog input = openImport(section, tag, all);
        EditText field = first(input.getWindow().getDecorView(), EditText.class);
        assertNotNull(field);
        assertFalse("还没打", input.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        if (all) {
            save(input, "settings-share-" + tag + "-import-empty");
        }

        // 照显示的样子、小写、连字符打：自动 4 个一组，码对了下面是这一条，地点 1 还没选
        type(field, "1a1g-0c58 0963");
        assertEquals(ShareCode.display(TAILGATE), field.getText().toString());
        List<String> shown = texts(input.getWindow().getDecorView());
        assertTrue(shown.toString(), shown.contains(activity.getString(R.string.auto_import_pick)));
        assertTrue(shown.toString(), shown.contains(activity.getString(R.string.auto_trigger_arrive,
                activity.getString(R.string.auto_place_default_name, 1))));
        assertTrue(input.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        save(input, "settings-share-" + tag + "-import");

        // 点「添加」：地点 1 没选，弹选地点的框；选「公司」，这一条就写「到达公司」
        input.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shots.settle();
        AlertDialog pick = (AlertDialog) ShadowDialog.getLatestDialog();
        org.junit.Assert.assertNotSame(input, pick);
        assertEquals(2, pick.getListView().getCount());
        if (all) {
            save(pick, "settings-share-" + tag + "-import-pick");
        }
        pick(pick, 1);
        shown = texts(input.getWindow().getDecorView());
        assertTrue(shown.toString(), shown.contains(activity.getString(R.string.auto_trigger_arrive, "公司")));
        save(input, "settings-share-" + tag + "-import-picked");

        // 添加：加在最后、开着、地点是公司，码不变
        input.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shots.settle();
        assertFalse(input.isShowing());
        List<Rule> stored = RuleStore.parse(config.getAutomationRules());
        assertEquals(3, stored.size());
        Rule added = stored.get(2);
        assertTrue(added.enabled);
        assertEquals(Trigger.arrive("work"), added.trigger);
        assertEquals(TAILGATE, ShareCode.encode(added));
        assertTrue("新加的展开着", section.findPreference("automation_rule_" + added.id + "/when").isVisible());

        if (!all) {
            config.setAutomationPlacesAndRules("", "");
            return;
        }

        // 码不对：打的时候不说，点「添加」才说
        input = openImport(section, tag, false);
        field = first(input.getWindow().getDecorView(), EditText.class);
        type(field, "1EC12568");
        assertFalse(texts(input.getWindow().getDecorView()).contains(activity.getString(R.string.auto_import_wrong)));
        input.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shots.settle();
        assertTrue(input.isShowing());
        assertTrue(texts(input.getWindow().getDecorView()).contains(activity.getString(R.string.auto_import_wrong)));
        save(input, "settings-share-" + tag + "-import-wrong");

        // 用到三个地点，自己只有两个：说先去添加，「添加」点不了
        type(field, THREE_PLACES);
        assertTrue(texts(input.getWindow().getDecorView()).contains(activity.getResources()
                .getQuantityString(R.plurals.auto_import_places_missing, 3, 3)));
        assertFalse(input.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
        save(input, "settings-share-" + tag + "-import-missing");
        input.dismiss();

        config.setAutomationPlacesAndRules("", "");
    }

    private static AlertDialog openImport(SettingsPreferenceFragment section, String tag, boolean shoot) {
        Preference add = section.findPreference(AutomationRows.ADD_KEY);
        add.getOnPreferenceClickListener().onPreferenceClick(add);
        Shots.settle();
        AlertDialog templates = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals("常用的四条 + 空白 + 输入分享码", 6, templates.getListView().getCount());
        if (shoot) {
            save(templates, "settings-share-" + tag + "-add");
        }
        pick(templates, 5);
        AlertDialog input = (AlertDialog) ShadowDialog.getLatestDialog();
        org.junit.Assert.assertNotSame(templates, input);
        return input;
    }

    /** 像打字一样：光标停在最后。 */
    private static void type(EditText field, String text) {
        field.setText(text);
        field.setSelection(field.getText().length());
        Shots.settle();
    }

    /** 二维码里是什么（ZXing 自己的解码器读一遍）。 */
    private static String scan(Bitmap bitmap) throws Exception {
        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(w, h, pixels))))
                .getText();
    }

    /** 框里画着的那张图（标题的图标那一格也是 ImageView，但是空的）。 */
    private static Bitmap qrIn(View root) {
        if (root instanceof ImageView && ((ImageView) root).getDrawable() instanceof BitmapDrawable) {
            return ((BitmapDrawable) ((ImageView) root).getDrawable()).getBitmap();
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                Bitmap found = qrIn(g.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static <T extends View> T first(View root, Class<T> type) {
        if (type.isInstance(root)) {
            return type.cast(root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                T found = first(g.getChildAt(i), type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** 看得见的字（框里的每一行）。 */
    private static List<String> texts(View root) {
        List<String> out = new ArrayList<>();
        collect(root, out);
        return out;
    }

    private static void collect(View v, List<String> out) {
        if (v.getVisibility() != View.VISIBLE) {
            return;
        }
        if (v instanceof TextView && !(v instanceof EditText)) {
            out.add(String.valueOf(((TextView) v).getText()));
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                collect(g.getChildAt(i), out);
            }
        }
    }

    private static void click(Preference p) {
        assertNotNull(p);
        p.getOnPreferenceClickListener().onPreferenceClick(p);
        Shots.settle();
    }

    private static void pick(AlertDialog dialog, int position) {
        ListView list = dialog.getListView();
        list.performItemClick(list.getChildAt(position), position, list.getItemIdAtPosition(position));
        Shots.settle();
    }

    private static void save(ShotActivity activity, String name) {
        File file = Shots.save(activity, name);
        assertTrue(file + " 是空的", file.length() > 10_000);
    }

    private static void save(Dialog dialog, String name) {
        File file = Shots.save(dialog, name);
        assertTrue(file + " 是空的", file.length() > 5_000);
    }

}
