/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.alarm.AlertStore;
import com.kooo.evcam.alarm.SentryAlerts;
import com.kooo.evcam.alarm.ntfy.NtfyRequest;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.share.QrCode;
import com.kooo.evcam.ui.CamDialogs;

/**
 * 「设置 → 录制」最下面的「哨兵提醒」一组（ui-spec §十六，项目所有者 2026-11-17 定位置、2026-11-18 定结构）：
 *
 * <ul>
 *   <li>「手机订阅模式」：开关合进分段「关闭 · ntfy · Telegram」（ui-spec §三）。存的是开没开、用哪个服务两样
 *       （{@link AlertStore}）：选「关闭」= 所有手机订阅服务都关，话题、同意都留着，再选 ntfy 直接打开。
 *       Telegram 还没做，摆出来、置灰。说明下面一行小字写上一次提醒。</li>
 *   <li>不是「关闭」时：「附带画面」（对所有服务都管用）。选 ntfy 时最后两行：「ntfy 话题」（尾号，点开「用手机订阅」框）、
 *       「ntfy 服务器」（点开输入框）。以后选 Telegram 只换最后这几行。</li>
 *   <li>第一次选 ntfy：先问「开启哨兵提醒？」，「开启」后生成话题、弹「用手机订阅」框；「取消」停在「关闭」。</li>
 *   <li>「用手机订阅」框：二维码、怎么订阅、话题尾号；左边「更换话题」（先问，换了框里换新码、不关框），右边「完成」「发送测试」
 *       （发一条测试提醒，不关框，结果用提示说）。</li>
 * </ul>
 *
 * <p>话题、服务器只在这一组和二维码里出现：不写黑匣子、不写日志（二维码里是完整的话题，界面上只写尾号）。
 * 风险提示、话题可换、扫码订阅的设计来自 Fab（Fabbbrrr）的 PR #211。</p>
 *
 * The "Sentry alerts" group at the bottom of Settings → Recording: the phone subscription row (Off / ntfy /
 * Telegram, Telegram grayed), the picture switch, and the ntfy topic and server rows, with the risk,
 * subscribe, change-topic and server dialogs. The topic never reaches a log; only its last four
 * characters show on screen, the whole of it only inside the QR code.
 */
final class SentryAlertRows {

    static final String CATEGORY_KEY = "cat_sentry_alerts";
    static final String MODE_KEY = "pref_sentry_alerts";
    static final String PICTURE_KEY = "pref_sentry_alerts_picture";
    static final String TOPIC_KEY = "pref_ntfy_topic";
    static final String SERVER_KEY = "pref_ntfy_server";

    /** 「手机订阅模式」第一段「关闭」的值。The value of the first segment, Off. */
    private static final String OFF = "off";
    /** 二维码边长，和「发到手机」框一样。QR side, as in the send-to-phone dialog. */
    private static final int QR_SIZE_PX = 420;

    private final PreferenceFragmentCompat fragment;
    /** 改了一项、别的行可能要跟着出现或收起（{@code SettingsPreferenceFragment.requestSync}）。 */
    private final Runnable sync;

    SentryAlertRows(PreferenceFragmentCompat fragment, Runnable sync) {
        this.fragment = fragment;
        this.sync = sync;
    }

    // ================================================================= 接线

    void bind() {
        Context context = fragment.getContext();
        SegmentedPreference mode = fragment.findPreference(MODE_KEY);
        if (context == null || mode == null) {
            return;
        }
        mode.setOptions(new CharSequence[] {context.getString(R.string.opt_off),
                        context.getString(R.string.opt_sentry_alerts_ntfy),
                        context.getString(R.string.opt_sentry_alerts_telegram)},
                new String[] {OFF, AlertStore.SERVICE_NTFY, AlertStore.SERVICE_TELEGRAM});
        mode.setOffValue(OFF);
        mode.setUnusable(AlertStore.SERVICE_TELEGRAM);
        mode.setOnPreferenceChangeListener((preference, newValue) -> pick(String.valueOf(newValue)));

        SwitchPreferenceCompat picture = fragment.findPreference(PICTURE_KEY);
        if (picture != null) {
            picture.setPersistent(false);
            picture.setOnPreferenceChangeListener((preference, newValue) -> {
                boolean on = Boolean.TRUE.equals(newValue);
                store(context).setPicture(on);
                BlackBox.noteImportant("设置变更: " + PICTURE_KEY + " → " + on);
                return true;
            });
        }
        click(TOPIC_KEY, this::showSubscribe);
        click(SERVER_KEY, this::editServer);
        refresh();
    }

    /** 照存着的写一遍（回到设置页时、改了一项之后）。Show what is stored. */
    void refresh() {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        AlertStore store = store(context);
        SegmentedPreference mode = fragment.findPreference(MODE_KEY);
        if (mode != null) {
            mode.setValue(store.isOn() ? store.service() : OFF);
            mode.setNote(store.isOn() ? status(context, store) : null);
        }
        SwitchPreferenceCompat picture = fragment.findPreference(PICTURE_KEY);
        if (picture != null) {
            picture.setChecked(store.picture());
        }
        ValueActionPreference topic = fragment.findPreference(TOPIC_KEY);
        if (topic != null) {
            topic.setValue(context.getString(R.string.set_ntfy_topic_value, store.topicTail()));
        }
        ValueActionPreference server = fragment.findPreference(SERVER_KEY);
        if (server != null) {
            server.setValue(NtfyRequest.host(store.server()));
        }
    }

    /** 关着收起（ui-spec §三）：「关闭」时只有第一行；选 ntfy 时多话题、服务器两行。 */
    void syncRows() {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        AlertStore store = store(context);
        boolean on = store.isOn();
        boolean ntfy = on && AlertStore.SERVICE_NTFY.equals(store.service());
        show(PICTURE_KEY, on);
        show(TOPIC_KEY, ntfy);
        show(SERVER_KEY, ntfy);
    }

    /** 说明下面那一行小字：上一次提醒什么时候发出，或者发送失败和原因；还没发过不写。 */
    private static CharSequence status(Context context, AlertStore store) {
        long at = store.lastAt();
        if (at <= 0) {
            return null;
        }
        return store.lastOk()
                ? context.getString(R.string.set_sentry_alerts_last, SentryAlerts.moment(at))
                : context.getString(R.string.set_sentry_alerts_last_failed,
                        SentryAlerts.reason(context, store.lastKind(), store.lastCode()));
    }

    // ================================================================= 手机订阅模式

    /** 点了一段（Segments 只在点了另一段时叫）：返回 false 停在原来那一段。 */
    private boolean pick(String value) {
        Context context = fragment.getContext();
        if (context == null) {
            return false;
        }
        AlertStore store = store(context);
        if (OFF.equals(value)) {
            store.turnOff();
            changed(context, value);
            return true;
        }
        if (!AlertStore.SERVICE_NTFY.equals(value)) {
            return false;   // Telegram 还没做：那一段是灰的，点不到这里
        }
        if (store.ntfyAccepted()) {
            // 关了再选：话题、同意都留着，直接开
            store.turnOnNtfy();
            changed(context, value);
            return true;
        }
        askRisk(context);
        return false;
    }

    private void changed(Context context, String value) {
        BlackBox.noteImportant("设置变更: " + MODE_KEY + " → " + value);
        SentryAlerts.settingsChanged(context);
        refresh();
        sync.run();
    }

    /** 第一次选 ntfy：「开启哨兵提醒？」—— 开启：生成话题、打开、弹「用手机订阅」框；取消：停在「关闭」。 */
    private void askRisk(Context context) {
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dlg_sentry_alerts_risk_title)
                .setMessage(R.string.dlg_sentry_alerts_risk_body)
                .setPositiveButton(R.string.sentry_alerts_action_turn_on, (dialog, which) -> {
                    AlertStore store = store(context);
                    newTopic(context, store);
                    store.turnOnNtfy();
                    changed(context, AlertStore.SERVICE_NTFY);
                    showSubscribe();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /** 同意了风险提示：换上一个新话题（第一次开启、更换话题）。话题本身不写黑匣子。 */
    private static void newTopic(Context context, AlertStore store) {
        long installedAt = 0L;
        try {
            installedAt = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).firstInstallTime;
        } catch (Exception ignored) {
            // 读不到安装时刻只少一样防撞的输入，话题照样随机
        }
        store.newTopic(System.currentTimeMillis(), installedAt, android.os.Build.FINGERPRINT);
        BlackBox.noteImportant("哨兵提醒：换了一个新话题（同意了风险提示）");
    }

    // ================================================================= 用手机订阅

    /** 「用手机订阅」框：二维码、怎么订阅、话题尾号；「更换话题」「完成」「发送测试」。 */
    private void showSubscribe() {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(20 * density);
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, Math.round(8 * density));
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        ImageView qr = new ImageView(context);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(QR_SIZE_PX, QR_SIZE_PX);
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        qr.setLayoutParams(qrParams);
        root.addView(qr);

        TextView failed = line(context, context.getString(R.string.sentry_alerts_qr_failed), R.dimen.text_caption, true);
        failed.setVisibility(View.GONE);
        root.addView(failed);
        root.addView(line(context, context.getString(R.string.dlg_ntfy_subscribe_steps), R.dimen.text_body, false));
        TextView tail = line(context, "", R.dimen.text_caption, true);
        root.addView(tail);

        Runnable render = () -> {
            AlertStore store = store(context);
            Bitmap code = QrCode.encode(NtfyRequest.deepLink(store.server(), store.topic(),
                    context.getString(R.string.app_name)), QR_SIZE_PX);
            qr.setImageBitmap(code);
            // 画不出来时那一格是白的，看上去和「界面坏了」没区别 —— 说出来
            failed.setVisibility(code == null ? View.VISIBLE : View.GONE);
            tail.setText(context.getString(R.string.dlg_ntfy_subscribe_tail, store.topicTail()));
        };
        render.run();

        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dlg_ntfy_subscribe_title)
                .setView(root)
                .setNeutralButton(R.string.sentry_alerts_action_rotate, null)
                .setNegativeButton(R.string.sentry_alerts_action_done, null)
                .setPositiveButton(R.string.sentry_alerts_action_test, null)
                .create();
        CamDialogs.style(dialog);
        dialog.setOnShowListener(shown -> {
            CamDialogs.paintNow(dialog);
            // 直接拿按钮改点击：默认行为会关掉对话框，而换话题、发测试都不该关（同「发到手机」框的「切换地址」）
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> confirmRotate(context, render));
            Button test = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            test.setOnClickListener(v -> sendTest(context, test));
        });
        dialog.show();
    }

    /** 「更换话题？」—— 更换：新话题、新同意，框里换成新码（框不关）。 */
    private void confirmRotate(Context context, Runnable render) {
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dlg_ntfy_rotate_title)
                .setMessage(R.string.dlg_ntfy_rotate_body)
                .setPositiveButton(R.string.sentry_alerts_action_rotate_confirm, (dialog, which) -> {
                    newTopic(context, store(context));
                    SentryAlerts.settingsChanged(context);
                    render.run();
                    refresh();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /** 「发送测试」：发一条测试提醒，结果用提示说；发着的时候按钮先不让点。 */
    private static void sendTest(Context context, Button button) {
        button.setEnabled(false);
        SentryAlerts.sendTest(context, result -> {
            button.setEnabled(true);
            Toast.makeText(context, result.ok
                    ? context.getString(R.string.msg_sentry_alerts_test_sent)
                    : context.getString(R.string.msg_sentry_alerts_test_failed,
                            SentryAlerts.reason(context, result.kind, result.httpCode)),
                    Toast.LENGTH_SHORT).show();
        });
    }

    // ================================================================= ntfy 服务器

    /**
     * 「ntfy 服务器」输入框：只收 https；填错不存、框不关，输入框下面写「仅支持 https」；换了服务器直接弹「用手机订阅」框
     * （手机要重新扫码）。
     */
    private void editServer() {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        float density = context.getResources().getDisplayMetrics().density;
        int pad = Math.round(24 * density);
        EditText input = CamDialogs.input(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setText(NtfyRequest.server(store(context).server()));
        input.setSelection(input.getText().length());

        TextView error = CamDialogs.note(context, context.getString(R.string.msg_ntfy_server_https_only));
        error.setTextColor(ContextCompat.getColor(context, R.color.energy_text));
        error.setPadding(0, pad / 3, 0, 0);
        error.setVisibility(View.GONE);
        TextView note = CamDialogs.note(context, context.getString(R.string.set_ntfy_server_summary));
        note.setPadding(0, pad / 3, 0, 0);

        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad / 2, pad, 0);
        box.addView(input);
        box.addView(error);
        box.addView(note);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                error.setVisibility(View.GONE);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        AlertDialog dialog = new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_ntfy_server_title)
                .setView(box)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_save, null)
                .create();
        CamDialogs.style(dialog);
        dialog.setOnShowListener(shown -> {
            CamDialogs.paintNow(dialog);
            // 填错不关框：默认行为点了就关
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String typed = input.getText().toString();
                String normalized = NtfyRequest.normalizeServer(typed);
                if (normalized.isEmpty()) {
                    // 写了别的协议说「仅支持 https」；其余（带路径、用户名，端口不对……）说只填服务器地址
                    error.setText(NtfyRequest.wrongScheme(typed)
                            ? R.string.msg_ntfy_server_https_only : R.string.msg_ntfy_server_address_only);
                    error.setVisibility(View.VISIBLE);
                    return;
                }
                dialog.dismiss();
                if (store(context).setServer(normalized)) {
                    BlackBox.noteImportant("设置变更: " + SERVER_KEY + "（换了服务器）");
                    SentryAlerts.settingsChanged(context);
                    refresh();
                    showSubscribe();
                }
            });
        });
        dialog.show();
    }

    // ================================================================= 小工具

    private static AlertStore store(Context context) {
        return AlertStore.of(context);
    }

    private void click(String key, Runnable action) {
        Preference pref = fragment.findPreference(key);
        if (pref != null) {
            pref.setOnPreferenceClickListener(preference -> {
                action.run();
                return true;
            });
        }
    }

    private void show(String key, boolean visible) {
        Preference pref = fragment.findPreference(key);
        if (pref != null && pref.isVisible() != visible) {
            pref.setVisible(visible);
        }
    }

    /** 框里的一行字，居中（同「发到手机」框）。@param sizeDimen 字号体系里的一档 */
    private static TextView line(Context context, String content, int sizeDimen, boolean secondary) {
        TextView view = new TextView(context);
        view.setText(content);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, context.getResources().getDimension(sizeDimen));
        view.setGravity(Gravity.CENTER_HORIZONTAL);
        view.setPadding(0, Math.round(10 * context.getResources().getDisplayMetrics().density), 0, 0);
        view.setTextColor(ContextCompat.getColor(context, secondary ? R.color.text_secondary : R.color.text_primary));
        return view;
    }
}
