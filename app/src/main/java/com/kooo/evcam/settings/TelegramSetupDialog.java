/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.ui.CamDialogs;
import com.kooo.evcam.alarm.SentryAlerts;
import com.kooo.evcam.alarm.telegram.TelegramRequest;
import com.kooo.evcam.alarm.telegram.TelegramSender;

import java.util.List;

/**
 * 把 Telegram 配好：造机器人 → 找到聊天 → 试发一张。
 *
 * <h3>写给谁</h3>
 *
 * <p>写给从没造过机器人的人。所以一步一个框，每屏最多两个选择，
 * 手机那头要点的东西写成编号的短句 —— 一段话读完和六条读完不是一回事
 * （{@link NtfySetupDialog} 的节奏，这里照抄）。</p>
 *
 * <h3>三步为什么是这个顺序</h3>
 *
 * <ol>
 *   <li><b>令牌。</b>没有它什么都做不了。存完当场问一句 Telegram 认不认；
 *       不对就说「该长什么样」再回到这一步，绝不往下走。</li>
 *   <li><b>聊天。</b>机器人只会往「跟它说过话的人」发消息。让人先发一句话，
 *       再让车去问 Telegram 谁发过 —— 车猜不出一个没出现过的会话号。</li>
 *   <li><b>试一张。</b>前面两步都是「看上去对」。只有一张照片真的到了手机上，
 *       整条路才算通。</li>
 * </ol>
 *
 * <h3>Three steps</h3>
 *
 * <ol>
 *   <li><b>Token.</b> Nothing works without it. It is saved, then Telegram is asked whether it
 *       accepts it, and a rejection reopens this step instead of moving on.</li>
 *   <li><b>Chat.</b> A bot only writes to people who wrote to it first. The owner sends one
 *       message, then the car asks Telegram who did.</li>
 *   <li><b>One picture.</b> The first two steps only look right. A picture arriving on the phone
 *       is the proof.</li>
 * </ol>
 *
 * <h3>不新增存的地方</h3>
 *
 * <p>令牌、会话号、车主号都走 {@link AppConfig} 现有的三个键，和手填那一条路是同一份数据 ——
 * 所以向导走到一半退出、以后手改，两边看到的都是同一个值。这一版不需要新键。</p>
 *
 * <p>No new storage: the token, the chat and the owner id use the same three {@link AppConfig}
 * keys the manual path writes, so leaving the wizard half way and editing by hand later see one
 * value. This version needs no new key.</p>
 */
public final class TelegramSetupDialog {

    private final Fragment fragment;
    private final AppConfig config;
    private final Runnable onFinished;

    /**
     * 车主已经按了「取消」。
     *
     * <p>找会话号、试发照片都是异步的：回来的时候人可能已经走了。没有这个标记，
     * 一个「还没找到」的框会在人关掉向导之后自己弹出来。</p>
     *
     * The owner already tapped Cancel. Finding the chat and sending the picture are asynchronous,
     * so a "nothing found yet" dialog would otherwise pop up after the wizard was closed.
     */
    private boolean cancelled;

    private TelegramSetupDialog(Fragment fragment, AppConfig config, Runnable onFinished) {
        this.fragment = fragment;
        this.config = config;
        this.onFinished = onFinished;
    }

    /** 从「机器人令牌」那一行进来。Entered from the "bot token" row. */
    public static void show(Fragment fragment, AppConfig config, Runnable onFinished) {
        if (fragment == null || fragment.getContext() == null) {
            return;
        }
        new TelegramSetupDialog(fragment, config, onFinished).askForToken();
    }

    // ------------------------------------------------------------------ 第一步

    /** 第一步：造机器人、粘令牌。 */
    private void askForToken() {
        if (!isLive()) {
            return;
        }
        EditText input = CamDialogs.input(fragment.requireContext());
        input.setHint(R.string.set_alerts_wizard_bot_hint);
        // 令牌是「数字:一串字符」，和手填那一条路一样按密码显示：常亮的屏幕上不留一整串凭据
        // Password-style, like the manual row, so a screen that stays on does not hold a credential.
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setText(config.getAlertsTelegramToken());
        input.setSelection(input.getText().length());
        input.setSingleLine(true);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int gap = (int) (8 * fragment.getResources().getDisplayMetrics().density);
        inputParams.topMargin = gap;

        LinearLayout column = column();
        column.addView(body(R.string.set_alerts_wizard_bot_body));
        column.addView(input, inputParams);

        CamDialogs.show(new MaterialAlertDialogBuilder(fragment.requireContext(),
                R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_alerts_wizard_bot_title)
                .setView(column)
                .setPositiveButton(R.string.set_alerts_wizard_next, (dialog, which) -> {
                    String value = input.getText().toString().trim();
                    if (value.isEmpty()) {
                        toast(fragment.getString(R.string.set_alerts_need_token));
                        askForToken();
                        return;
                    }
                    // 形状先看：少粘几位、粘进别的东西，不用等一次网络往返就能说
                    // Shape first: a short paste or the wrong text is answered without a round trip.
                    config.setAlertsTelegramToken(value);
                    BlackBox.noteImportant("设置变更: pref_alerts_token → 向导填写");
                    alerts().refreshConfig();
                    if (!TelegramRequest.looksLikeToken(value)) {
                        BlackBox.note("telegram wizard: token shape rejected");
                        showTokenProblem();
                        return;
                    }
                    verifyToken();
                })
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish()));
    }

    /** 问一句 Telegram 认不认这个令牌。 */
    private void verifyToken() {
        alerts().checkToken((ok, message) -> {
            if (!isLive()) {
                return;
            }
            if (ok) {
                BlackBox.noteImportant("telegram wizard: token accepted");
                toast(message);
                askForChat();
                return;
            }
            BlackBox.note("telegram wizard: token rejected by Telegram");
            showTokenProblem();
        });
    }

    /**
     * 令牌不对：说清楚该长什么样，然后回到第一步，不往下走。
     *
     * <p>存进去的那个值不擦掉 —— 手填那一条路本来就是这样（填了、行尾写「不像令牌」），
     * 两种进法看到的东西才对得上。取消离开向导也可以，那一行仍然能再点。</p>
     *
     * The rejected value stays stored: the manual path behaves the same way, so both entries agree.
     * Cancelling out is still fine — the row stays tappable.
     */
    private void showTokenProblem() {
        if (!isLive()) {
            return;
        }
        CamDialogs.show(dialog(R.string.set_alerts_wizard_bot_bad_title,
                fragment.getString(R.string.set_alerts_wizard_bot_bad_body))
                .setPositiveButton(R.string.set_alerts_wizard_retry, (dialog, which) -> askForToken())
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish()));
    }

    // ------------------------------------------------------------------ 第二步

    /** 第二步：让车主先给机器人发一句话，再由车去问 Telegram。 */
    private void askForChat() {
        if (!isLive()) {
            return;
        }
        CamDialogs.show(dialog(R.string.set_alerts_wizard_chat_title,
                fragment.getString(R.string.set_alerts_wizard_chat_body))
                .setPositiveButton(R.string.set_alerts_wizard_chat_find, (dialog, which) -> findChat())
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish()));
    }

    /**
     * 走现成的找会话号那一条路。
     *
     * <p>它排在轮询者两轮之间跑（同一时刻只许一个 {@code getUpdates}，D-14），所以这里不去开
     * 第二个 —— 多开一个，Telegram 那边两条长轮询会互相吃掉对方的更新。</p>
     *
     * Reuses the existing find-chats path, which queues between two polling rounds (only one
     * {@code getUpdates} at a time, D-14). Opening a second one here would make the two long polls
     * eat each other's updates.
     */
    private void findChat() {
        if (!isLive()) {
            return;
        }
        toast(fragment.getString(R.string.set_alerts_wizard_chat_looking));
        alerts().findChats(new SentryAlerts.ChatListCallback() {
            @Override
            public void onChats(List<TelegramSender.Chat> chats) {
                if (!isLive()) {
                    return;
                }
                if (chats.isEmpty()) {
                    BlackBox.note("telegram wizard: no chat found");
                    showChatMissing(R.string.set_alerts_wizard_chat_none_title,
                            fragment.getString(R.string.set_alerts_wizard_chat_none_body));
                    return;
                }
                if (chats.size() == 1) {
                    applyChat(chats.get(0));
                    return;
                }
                askWhichChat(chats);
            }

            @Override
            public void onFailed(String message) {
                if (!isLive()) {
                    return;
                }
                BlackBox.note("telegram wizard: finding a chat failed");
                showChatMissing(R.string.set_alerts_wizard_chat_failed_title,
                        fragment.getString(R.string.set_alerts_wizard_chat_failed_body,
                                message == null ? "" : message));
            }
        });
    }

    /** 找到好几个：列出来让人点一个，仍然只有一个「取消」。 */
    private void askWhichChat(final List<TelegramSender.Chat> chats) {
        CharSequence[] labels = new CharSequence[chats.size()];
        for (int i = 0; i < chats.size(); i++) {
            labels[i] = chats.get(i).toString();
        }
        CamDialogs.show(new MaterialAlertDialogBuilder(fragment.requireContext(),
                R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_alerts_wizard_chat_pick_title)
                .setItems(labels, (dialog, which) -> applyChat(chats.get(which)))
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish()));
    }

    /** 会话号和车主号都存进现有的键，然后往下走。 */
    private void applyChat(TelegramSender.Chat chat) {
        if (!isLive()) {
            return;
        }
        config.setAlertsTelegramChatId(chat.id);
        if (chat.userId != null && !chat.userId.isEmpty()) {
            config.setAlertsOwnerId(chat.userId);
        }
        BlackBox.noteImportant("设置变更: pref_alerts_chat → 向导找到");
        alerts().refreshConfig();
        toast(fragment.getString(R.string.set_alerts_wizard_chat_found));
        askForTest();
    }

    /** 没找到 / 问不成：说清楚下一步做什么，再回到第二步，不退出去。 */
    private void showChatMissing(int title, String message) {
        CamDialogs.show(dialog(title, message)
                .setPositiveButton(R.string.set_alerts_wizard_retry, (dialog, which) -> askForChat())
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish()));
    }

    // ------------------------------------------------------------------ 第三步

    /** 第三步：发一张，看手机上收没收到。 */
    private void askForTest() {
        if (!isLive()) {
            return;
        }
        CamDialogs.show(dialog(R.string.set_alerts_wizard_test_title,
                fragment.getString(R.string.set_alerts_wizard_test_body))
                .setPositiveButton(R.string.set_alerts_wizard_test_send, (dialog, which) -> sendTest())
                .setNegativeButton(R.string.action_cancel, (dialog, which) -> finish())
                .setOnCancelListener(dialog -> finish()));
    }

    private void sendTest() {
        if (!isLive()) {
            return;
        }
        toast(fragment.getString(R.string.set_alerts_wizard_test_sending));
        alerts().sendTestPicture((ok, message) -> {
            if (!isLive()) {
                return;
            }
            if (ok) {
                BlackBox.noteImportant("telegram wizard: test picture delivered");
                showDone(message);
                return;
            }
            BlackBox.note("telegram wizard: test picture failed");
            CamDialogs.show(dialog(R.string.set_alerts_wizard_test_failed_title,
                    fragment.getString(R.string.set_alerts_wizard_test_failed_body, message))
                    .setPositiveButton(R.string.set_alerts_wizard_retry,
                            (dialog, which) -> askForTest())
                    .setNegativeButton(R.string.action_cancel, (dialog, which) -> finish())
                    .setOnCancelListener(dialog -> finish()));
        });
    }

    /**
     * 通了。顺手把「现在到底是什么状态」说清楚 —— 总开关是开是关，这里读一次再写，不猜。
     *
     * <p>开关开着也只说一半：警报只在哨兵模式守着的时候发。所以两种状态各有一句话，第二种多一行
     * 讲哨兵模式 —— 那一行不是补充说明，是「为什么我开了却没收到警报」的答案。</p>
     *
     * <p>Done. Say what the state really is, read once, never assumed: with alerts off, say the switch
     * is still off; with alerts on, say so and add the one line that makes it true — alerts only fire
     * while Sentry Mode is armed, and that is what keeps the head unit awake.</p>
     */
    private void showDone(String message) {
        String body = config.isAlertsEnabled()
                ? fragment.getString(R.string.set_alerts_wizard_done_body_on, message,
                        fragment.getString(R.string.vi_sentry_mode))
                : fragment.getString(R.string.set_alerts_wizard_done_body_off, message);
        CamDialogs.show(dialog(R.string.set_alerts_wizard_done_title, body)
                .setPositiveButton(R.string.set_alerts_wizard_finish, (dialog, which) -> finish())
                .setNegativeButton(R.string.set_alerts_wizard_done_again,
                        (dialog, which) -> askForTest())
                .setOnCancelListener(dialog -> finish()));
    }

    // ------------------------------------------------------------------ 工具

    private SentryAlerts alerts() {
        return SentryAlerts.get(fragment.requireContext());
    }

    private MaterialAlertDialogBuilder dialog(int title, String message) {
        return new MaterialAlertDialogBuilder(fragment.requireContext(),
                R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(title)
                .setMessage(message);
    }

    /** 说明文字单独一块，行距放开一点：编号的步骤挤成一段就读不完了。 */
    private TextView body(int text) {
        TextView view = new TextView(fragment.requireContext());
        view.setText(text);
        view.setLineSpacing(0f, 1.15f);
        return view;
    }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(fragment.requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * fragment.getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad / 2, pad, 0);
        return column;
    }

    /** 还能弹框吗：还在界面上、而且人没按过取消。 */
    private boolean isLive() {
        return !cancelled && fragment.isAdded() && fragment.getContext() != null;
    }

    private void finish() {
        if (cancelled) {
            return;
        }
        cancelled = true;
        onFinished.run();
    }

    private void toast(String message) {
        if (fragment.isAdded() && fragment.getContext() != null) {
            android.widget.Toast.makeText(fragment.getContext(), message,
                    android.widget.Toast.LENGTH_LONG).show();
        }
    }
}
