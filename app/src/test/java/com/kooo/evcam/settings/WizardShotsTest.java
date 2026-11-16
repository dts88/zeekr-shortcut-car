/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.app.Dialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;

import androidx.fragment.app.Fragment;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.alarm.ShadowNtfySender;
import com.kooo.evcam.alarm.ShadowSentryAlerts;
import com.kooo.evcam.screens.ShotActivity;
import com.kooo.evcam.screens.Shots;

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
 * 两个设置向导，每一步画一张图：{@link TelegramSetupDialog} 与 {@link NtfySetupDialog}。
 *
 * <p>向导是对话框一层层往下走的，所以这里不截整页，而是把车上的样子画下来：底下的设置页、压暗的遮罩、
 * 中间那个框 —— 和车主看到的顺序一样。框本身是真正的 {@code MaterialAlertDialog}，按钮由
 * {@code CamDialogs} 上色，文字来自真的字符串资源。</p>
 *
 * <p>三步里的网络调用由 {@link ShadowSentryAlerts} 和 {@link ShadowNtfySender} 照剧本回答
 * （见那两个类）：向导的代码一行没改，只有「Telegram / ntfy 怎么说」这一层是假的。
 * 中文、英文各画一套（{@code -zh}、{@code -en}），文档里用英文那套。图片写到
 * {@code app/build/outputs/screenshots/}。</p>
 *
 * <p>Every step of both setup wizards, one picture per step.</p>
 *
 * <p>The wizards walk down through dialogs, so this does not shoot a page: it draws what the head unit
 * shows — the settings page underneath, a dimmed scrim, the dialog in the middle, in the order the
 * owner sees them. The dialog is a real {@code MaterialAlertDialog}, its buttons are painted by
 * {@code CamDialogs}, and the words come from the real string resources.</p>
 *
 * <p>The network calls behind the steps are answered from a script by {@link ShadowSentryAlerts} and
 * {@link ShadowNtfySender}; not one line of the wizard is changed, only "what Telegram / ntfy says" is
 * fake. Every step is shot in Chinese and in English ({@code -zh}, {@code -en}); the docs use the
 * English set. Pictures land in {@code app/build/outputs/screenshots/}.</p>
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, application = android.app.Application.class,
        // 只为了截图把这两个类换成「照剧本回答」的那一份（Robolectric 的 shadow）。
        // Only to swap these two classes for the scripted answers, for the screenshots (Robolectric shadows).
        instrumentedPackages = {"com.kooo.evcam.alarm"},
        shadows = {ShadowSentryAlerts.class, ShadowNtfySender.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class WizardShotsTest {

    private static final File FOLDER = new File("build/outputs/screenshots");
    /** 外形对得上的假令牌：截图里只有圆点，不看这串本身。A shape-valid fake token; the picture only shows dots. */
    private static final String TOKEN = "8123456789:AAHnotARealTokenForScreenshots";
    private static final String EN = Shots.DAY_EN;
    private static final String ZH = Shots.DAY;

    private ShotActivity activity;
    private AppConfig config;

    @Before
    public void setUp() {
        ShadowSentryAlerts.reset();
        ShadowNtfySender.reset();
    }

    /** 开一屏「停车警报」：向导就是从这一屏点进去的，所以底下放的是真的设置页。 */
    private Fragment openSettings(String qualifiers) {
        RuntimeEnvironment.setQualifiers(qualifiers);
        activity = Robolectric.buildActivity(ShotActivity.class).setup().get();
        Shots.stillFrames(activity);
        config = new AppConfig(activity);
        config.setAlertsEnabled(true);
        config.setAlertsTelegramToken("");
        config.setAlertsTelegramChatId("");
        config.setAlertsNtfyTopic("");
        SettingsShellFragment shell = new SettingsShellFragment();
        activity.getSupportFragmentManager().beginTransaction()
                .replace(activity.container, shell).commitNow();
        Shots.settle();
        shell.showSection("screen_alerts");
        Shots.settle();
        Fragment host = new Fragment();
        activity.getSupportFragmentManager().beginTransaction().add(host, "wizard").commitNow();
        return host;
    }

    // ------------------------------------------------------------------ Telegram

    /** 顺利走完三步，英文：文档里用的那一套。The happy path, English: the set the docs use. */
    @Test
    public void telegramEveryStepEnglish() {
        telegramEveryStep(EN, "en");
    }

    /** 同一套，中文：文档不放，但两种语言都得看着一样。The same, Chinese: not in the docs, but both must look right. */
    @Test
    public void telegramEveryStepChinese() {
        telegramEveryStep(ZH, "zh");
    }

    private void telegramEveryStep(String qualifiers, String lang) {
        Fragment host = openSettings(qualifiers);
        TelegramSetupDialog.show(host, config, () -> { });
        Shots.settle();

        Dialog token = latest();
        assertEquals("第一步：粘令牌", text(R.string.set_alerts_wizard_next),
                button(token, DialogInterface.BUTTON_POSITIVE));
        shot("wizard-telegram-1-botfather-" + lang);

        // 粘进去、按「下一步」：SentryAlerts 说这个令牌可用，于是进第二步。
        // Paste it and press Next: SentryAlerts says the token works, so the second step opens.
        EditText input = find(token.getWindow().getDecorView(), EditText.class);
        assertNotNull("第一步有一个粘令牌的输入框", input);
        input.setText(TOKEN);
        click(token, DialogInterface.BUTTON_POSITIVE);

        Dialog chat = latest();
        assertNotSame("按了「下一步」就换了一个框", token, chat);
        assertEquals("第二步：找到会话", text(R.string.set_alerts_wizard_chat_find),
                button(chat, DialogInterface.BUTTON_POSITIVE));
        shot("wizard-telegram-2-chat-" + lang);

        ShadowSentryAlerts.addChat("Xiao Ming's car", "123456789", "123456789");
        click(chat, DialogInterface.BUTTON_POSITIVE);

        Dialog test = latest();
        assertEquals("第三步：试一张", text(R.string.set_alerts_wizard_test_send),
                button(test, DialogInterface.BUTTON_POSITIVE));
        shot("wizard-telegram-3-test-" + lang);

        click(test, DialogInterface.BUTTON_POSITIVE);

        Dialog done = latest();
        assertEquals("通了", text(R.string.set_alerts_wizard_finish),
                button(done, DialogInterface.BUTTON_POSITIVE));
        shot("wizard-telegram-4-done-" + lang);
        assertEquals("走到最后就存下了会话号", "123456789", config.getAlertsTelegramChatId());
        // 「完成」：框关掉，向导结束。Finish: the dialog closes and the wizard ends.
        click(done, DialogInterface.BUTTON_POSITIVE);
        assertTrue("「完成」之后框关掉", !done.isShowing());
    }

    /** 令牌不对：说清楚该长什么样，人还在第一步。Telegram rejects the token: say what it looks like. */
    @Test
    public void telegramRejectedToken() {
        for (String[] lang : new String[][] {{EN, "en"}, {ZH, "zh"}}) {
            ShadowSentryAlerts.reset();
            ShadowSentryAlerts.tokenAccepted = false;
            Fragment host = openSettings(lang[0]);
            TelegramSetupDialog.show(host, config, () -> { });
            Shots.settle();
            Dialog token = latest();
            find(token.getWindow().getDecorView(), EditText.class).setText(TOKEN);
            click(token, DialogInterface.BUTTON_POSITIVE);

            Dialog problem = latest();
            assertNotSame(token, problem);
            assertEquals("回到第一步，不往下走", text(R.string.set_alerts_wizard_retry),
                    button(problem, DialogInterface.BUTTON_POSITIVE));
            shot("wizard-telegram-1b-token-rejected-" + lang[1]);
            click(problem, DialogInterface.BUTTON_NEGATIVE);
        }
    }

    /** 形状就不对的一串码：不用等一次网络往返就能说。A paste that does not even look like a token. */
    @Test
    public void telegramTokenShapeRejected() {
        Fragment host = openSettings(EN);
        TelegramSetupDialog.show(host, config, () -> { });
        Shots.settle();
        Dialog token = latest();
        find(token.getWindow().getDecorView(), EditText.class).setText("123456789");
        click(token, DialogInterface.BUTTON_POSITIVE);
        Dialog problem = latest();
        assertNotSame(token, problem);
        assertEquals("形状不对也是这一屏", text(R.string.set_alerts_wizard_retry),
                button(problem, DialogInterface.BUTTON_POSITIVE));
        click(problem, DialogInterface.BUTTON_NEGATIVE);
    }

    /** 找到好几个聊天：列出来让人点一个。Several chats: list them and let the owner pick one. */
    @Test
    public void telegramChatPicker() {
        for (String[] lang : new String[][] {{EN, "en"}, {ZH, "zh"}}) {
            ShadowSentryAlerts.reset();
            ShadowSentryAlerts.addChat("Xiao Ming's car", "123456789", "123456789");
            ShadowSentryAlerts.addChat("Garage group", "-1001234567890", "123456789");
            Fragment host = openSettings(lang[0]);
            TelegramSetupDialog.show(host, config, () -> { });
            Shots.settle();
            Dialog token = latest();
            find(token.getWindow().getDecorView(), EditText.class).setText(TOKEN);
            click(token, DialogInterface.BUTTON_POSITIVE);

            Dialog chat = latest();
            click(chat, DialogInterface.BUTTON_POSITIVE);
            Dialog pick = latest();
            assertNotSame(chat, pick);
            shot("wizard-telegram-2b-chat-picker-" + lang[1]);
            click(pick, DialogInterface.BUTTON_NEGATIVE);
        }
    }

    /** 照片没发出去：原因写出来，可以再试。The picture did not go out: say why, offer another go. */
    @Test
    public void telegramTestPictureFailed() {
        for (String[] lang : new String[][] {{EN, "en"}, {ZH, "zh"}}) {
            ShadowSentryAlerts.reset();
            ShadowSentryAlerts.pictureSent = false;
            Fragment host = openSettings(lang[0]);
            TelegramSetupDialog.show(host, config, () -> { });
            Shots.settle();
            Dialog token = latest();
            find(token.getWindow().getDecorView(), EditText.class).setText(TOKEN);
            ShadowSentryAlerts.addChat("Xiao Ming's car", "123456789", "123456789");
            click(token, DialogInterface.BUTTON_POSITIVE);
            Dialog chat = latest();
            click(chat, DialogInterface.BUTTON_POSITIVE);
            Dialog test = latest();
            click(test, DialogInterface.BUTTON_POSITIVE);

            Dialog failed = latest();
            assertNotSame(test, failed);
            assertEquals("可以再试一次", text(R.string.set_alerts_wizard_retry),
                    button(failed, DialogInterface.BUTTON_POSITIVE));
            shot("wizard-telegram-3b-test-failed-" + lang[1]);
            click(failed, DialogInterface.BUTTON_NEGATIVE);
        }
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

    /** 在框里找第一个某种控件（输入框）。Find the first view of a kind inside the dialog. */
    private static <T extends View> T find(View root, Class<T> kind) {
        if (kind.isInstance(root)) {
            return kind.cast(root);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                T found = find(group.getChildAt(i), kind);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
