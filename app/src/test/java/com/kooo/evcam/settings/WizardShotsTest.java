/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.material.button.MaterialButton;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.screens.ShotActivity;
import com.kooo.evcam.screens.Shots;
import com.kooo.evcam.alarm.ShadowNtfySender;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * ntfy 设置向导，每一步画一张图。
 *
 * <p>向导是对话框一层层往下走的，所以这里不截整页，而是把车上的样子画下来：底下的设置页、压暗的遮罩、
 * 中间那个框 —— 和车主看到的顺序一样。框本身是真正的 {@code MaterialAlertDialog}，按钮由
 * {@code CamDialogs} 上色，文字来自真的字符串资源。</p>
 *
 * <p>向导里的网络调用由 {@link ShadowNtfySender} 照剧本回答：向导的代码一行没改，只有「ntfy 怎么说」
 * 这一层是假的。中文、英文各画一套（{@code -zh}、{@code -en}），文档里用英文那套。图片写到
 * {@code app/build/outputs/screenshots/}。</p>
 *
 * <p>Every step of the ntfy setup wizard, one picture per step.</p>
 *
 * <p>The wizard walks down through dialogs, so this does not shoot a page: it draws what the head unit
 * shows — the settings page underneath, a dimmed scrim, the dialog in the middle, in the order the
 * owner sees them. The dialog is a real {@code MaterialAlertDialog}, its buttons are painted by
 * {@code CamDialogs}, and the words come from the real string resources.</p>
 *
 * <p>The network calls behind the steps are answered from a script by {@link ShadowNtfySender};
 * not one line of the wizard is changed, only "what ntfy says" is fake. Every step is shot in Chinese
 * and in English ({@code -zh}, {@code -en}); the docs use the English set. Pictures land in
 * {@code app/build/outputs/screenshots/}.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class,
        // 只为了截图把发送换成「照剧本回答」的那一份（Robolectric 的 shadow）。
        // Only to swap the sender for the scripted answers, for the screenshots (Robolectric shadow).
        instrumentedPackages = {"com.kooo.evcam.alarm"},
        shadows = {ShadowNtfySender.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WizardShotsTest {

    private static final File FOLDER = new File("build/outputs/screenshots");
    private static final String EN = Shots.DAY_EN;
    private static final String ZH = Shots.DAY;

    private ShotActivity activity;
    private AppConfig config;
    private SettingsShellFragment shell;

    @Before
    public void setUp() {
        ShadowNtfySender.reset();
    }

    /** 开一屏「停车警报」：向导就是从这一屏点进去的，所以底下放的是真的设置页。 */
    private Fragment openSettings(String qualifiers) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        config = new AppConfig(activity);
        config.setAlertsEnabled(true);
        config.setAlertsNtfyTopic("");
        shell = new SettingsShellFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .replace(activity.container, shell).commitNow();
        Shots.settle();
        shell.showSection("screen_alerts");
        Shots.settle();
        Fragment host = new Fragment();
        activity.getSupportFragmentManager().beginTransaction().add(host, "wizard").commitNow();
        return host;
    }

    // ------------------------------------------------------------------ ntfy

    /** 走完三步，英文。Every step of the ntfy wizard, English. */
    @Test
    public void ntfyEveryStepEnglish() {
        ntfyEveryStep(EN, "en");
    }

    /** 同一套，中文。The same, Chinese. */
    @Test
    public void ntfyEveryStepChinese() {
        ntfyEveryStep(ZH, "zh");
    }

    private void ntfyEveryStep(String qualifiers, String lang) {
        Fragment host = openSettings(qualifiers);
        NtfySetupDialog.show(host, config, () -> { });
        Shots.settle();

        Dialog risk = latest();
        assertEquals("第一步：先把风险说清楚", text(R.string.set_alerts_ntfy_risk_accept),
                button(risk, DialogInterface.BUTTON_POSITIVE));
        shot("wizard-ntfy-1-risk-" + lang);
        click(risk, DialogInterface.BUTTON_POSITIVE);

        Dialog qr = latest();
        assertNotSame(risk, qr);
        assertEquals("第二步：扫码", text(R.string.set_alerts_ntfy_qr_open),
                button(qr, DialogInterface.BUTTON_POSITIVE));
        shot("wizard-ntfy-2-qr-" + lang);
        click(qr, DialogInterface.BUTTON_POSITIVE);

        Dialog code = latest();
        assertNotSame(qr, code);
        assertEquals("第三步：存", text(R.string.action_save),
                button(code, DialogInterface.BUTTON_POSITIVE));
        shot("wizard-ntfy-3-code-" + lang);
        assertTrue("话题是新生成的一个", config.getAlertsNtfyTopic().length() > 0);
        // 「重发一条」：同一个框再来一次。Resend: the same dialog again.
        click(code, DialogInterface.BUTTON_NEGATIVE);
        assertNotSame(code, latest());
    }

    // ------------------------------------------------------------------ 画图

    /**
     * 把这个框画在设置页上面存下来：底下那一层画真的设置页，再压暗，再把框画在中间，最后裁到框外扩一点。
     *
     * Draw this dialog over the settings page and save it: the real page underneath, dimmed, the
     * dialog in the middle, cropped to the dialog plus a little margin.
     */
    private File shot(String name) {
        Shots.settle();
        Dialog dialog = latest();
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;

        View page = activity.getWindow().getDecorView();
        page.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        page.layout(0, 0, width, height);
        Shots.settle();

        View card = dialog.getWindow().getDecorView();
        int cardWidth = card.getWidth();
        int cardHeight = card.getHeight();
        if (cardWidth <= 0 || cardHeight <= 0) {
            // 没排过版就自己排：宽度照框的窗口属性，最多到屏幕宽。
            // Lay it out here if the window did not: width per the dialog window, capped at the screen.
            int max = width - Math.round(64 * metrics.density);
            card.measure(View.MeasureSpec.makeMeasureSpec(max, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
            cardWidth = Math.max(1, card.getMeasuredWidth());
            cardHeight = Math.max(1, card.getMeasuredHeight());
            card.layout(0, 0, cardWidth, cardHeight);
            Shots.settle();
        }

        Bitmap screen = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(screen);
        page.draw(canvas);
        // 对话框自己会把底下的界面压暗，这里照样画一层。
        // A real dialog dims what is behind it, so draw the same layer.
        canvas.drawColor(0x66000000);
        int left = Math.max(0, (width - cardWidth) / 2);
        int top = Math.max(0, (height - cardHeight) / 2);
        canvas.save();
        canvas.translate(left, top);
        card.draw(canvas);
        canvas.restore();

        int margin = Math.round(20 * metrics.density);
        int x = Math.max(0, left - margin);
        int y = Math.max(0, top - margin);
        int w = Math.min(width - x, cardWidth + 2 * margin);
        int h = Math.min(height - y, cardHeight + 2 * margin);
        Bitmap crop = Bitmap.createBitmap(screen, x, y, w, h);
        File out = new File(FOLDER, name + ".png");
        //noinspection ResultOfMethodCallIgnored
        FOLDER.mkdirs();
        try (FileOutputStream stream = new FileOutputStream(out)) {
            crop.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } catch (IOException e) {
            throw new IllegalStateException("无法写入截图 " + out, e);
        } finally {
            crop.recycle();
            screen.recycle();
        }
        assertTrue(out + " 是空的", out.length() > 10_000);
        return out;
    }

    private static Dialog latest() {
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull("这一步没有弹出对话框", dialog);
        assertTrue("这一步的对话框关着", dialog.isShowing());
        return dialog;
    }

    private static String button(Dialog dialog, int which) {
        Button button = ((androidx.appcompat.app.AlertDialog) dialog).getButton(which);
        assertNotNull("这个框没有那个按钮 " + which, button);
        return String.valueOf(button.getText());
    }

    private void click(Dialog dialog, int which) {
        Button button = ((androidx.appcompat.app.AlertDialog) dialog).getButton(which);
        assertNotNull("这个框没有那个按钮 " + which, button);
        button.performClick();
        Shots.settle();
    }

    private String text(int id) {
        return activity.getString(id);
    }
}
