package com.kooo.evcam.settings;

import android.content.Context;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.button.SmartButtonAction;
import com.kooo.evcam.button.SmartButtonAd;
import com.kooo.evcam.button.SmartButtons;
import com.kooo.evcam.ui.CamDialogs;

import java.util.ArrayList;
import java.util.List;

/**
 * 设置 → 悬浮键与灵动贴 → 灵动贴（试验性）（项目所有者 2026-10-30；2026-11-04 搜索改成绑定，ui-spec §十一）。
 *
 * <ul>
 *   <li>总开关「灵动贴」那一行由设置页照别的开关绑（关着时不扫描蓝牙，下面几行灰着）。</li>
 *   <li>「绑定灵动贴」一行：蓝牙要开着、要有「附近的设备」权限（没有就当场要）、没绑满。框里提示长按灵动贴约 3 秒到背光闪；
 *       {@link SmartButtons#BIND_WINDOW_MS} 里长按了没绑过的，框里改成正在连接，绑上了、拿绑过的来长按了、时间到了没找到、没连上，
 *       都提示一句、关框。人关了框就不绑了。</li>
 *   <li>绑定的每一台一行（{@link ValueActionPreference}）：名字「灵动贴 B5:E6」，说明写单击、双击各做什么，下面一行版本和序列号
 *       （有了才写），行尾值是电量，按钮「删除」（先问一句）；点这一行改单击、双击（{@link SmartButtonAction}）。</li>
 * </ul>
 *
 * <p>真正的绑定、听、连在 {@link SmartButtons}；这里只摆界面。绑定的、电量这些变了，它叫一声，这里照着重摆。</p>
 */
final class SmartButtonRows {

    /** 绑定的那几行的 key 前缀（后面接地址）。 */
    private static final String ROW_PREFIX = "smart_button.";
    /** 总开关：下面几行跟着它灰。 */
    static final String SWITCH_KEY = "pref_smart_buttons";

    private final SettingsPreferenceFragment fragment;
    private final PreferenceCategory category;
    private final ActivityResultLauncher<String[]> permissions;
    private final Runnable onChanged = this::rebuildRows;
    private AlertDialog bindDialog;
    /** 为了绑定去要的权限，回来了接着绑。 */
    private boolean bindAfterPermission;

    SmartButtonRows(SettingsPreferenceFragment fragment, PreferenceCategory category,
                    ActivityResultLauncher<String[]> permissions) {
        this.fragment = fragment;
        this.category = category;
        this.permissions = permissions;
    }

    void bind() {
        Preference bindRow = category.findPreference("pref_smart_button_bind");
        if (bindRow != null) {
            bindRow.setOnPreferenceClickListener(p -> {
                startBinding();
                return true;
            });
        }
        SmartButtons buttons = SmartButtons.get();
        if (buttons != null) {
            buttons.addChangeListener(onChanged);
        }
        rebuildRows();
    }

    /** 这一页没了：不再听变化，绑定框关掉（关掉时不绑了）。 */
    void detach() {
        SmartButtons buttons = SmartButtons.get();
        if (buttons != null) {
            buttons.removeChangeListener(onChanged);
        }
        if (bindDialog != null) {
            // 框的关闭回调是排着来的，等不到它：这里直接不绑了
            AlertDialog dialog = bindDialog;
            bindDialog = null;
            dialog.dismiss();
            if (buttons != null) {
                buttons.cancelBinding();
            }
        }
    }

    /** 「附近的设备」权限的结果回来了。 */
    void onPermissions() {
        SmartButtons buttons = SmartButtons.get();
        Context context = fragment.getContext();
        if (buttons == null || context == null) {
            return;
        }
        // 刚给的权限：后台听不用等下一次重试
        buttons.refresh();
        if (!bindAfterPermission) {
            return;
        }
        bindAfterPermission = false;
        if (SmartButtons.canScan(context) && SmartButtons.canConnect(context)) {
            showBindDialog();
        } else {
            toast(context.getString(R.string.msg_smart_button_no_permission));
        }
    }

    // ================================================================= 绑定

    private void startBinding() {
        SmartButtons buttons = SmartButtons.get();
        Context context = fragment.getContext();
        if (buttons == null || context == null) {
            return;
        }
        if (!buttons.hasBluetooth()) {
            toast(context.getString(R.string.msg_smart_button_no_bluetooth));
            return;
        }
        if (!buttons.bluetoothOn()) {
            toast(context.getString(R.string.msg_smart_button_bluetooth_off));
            return;
        }
        if (new AppConfig(context).getSmartButtons().size() >= SmartButtons.MAX_BOUND) {
            toast(context.getString(R.string.msg_smart_button_full, SmartButtons.MAX_BOUND));
            return;
        }
        if (!SmartButtons.canScan(context) || !SmartButtons.canConnect(context)) {
            bindAfterPermission = true;
            permissions.launch(SmartButtons.permissions());
            return;
        }
        showBindDialog();
    }

    /** 绑定框：一行说明（长按到背光闪；连着的时候写正在连接），一个「取消」。关掉就不绑了。 */
    private void showBindDialog() {
        SmartButtons buttons = SmartButtons.get();
        Context context = fragment.getContext();
        if (buttons == null || context == null || bindDialog != null) {
            return;
        }
        int pad = dp(context, 24);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(context, 8), pad, 0);
        TextView note = new TextView(context);
        note.setTextAppearance(R.style.TextAppearance_Cam_Body);
        note.setText(R.string.dlg_smart_button_bind_note);
        box.addView(note);

        SmartButtons.BindListener listener = new SmartButtons.BindListener() {
            @Override
            public void onConnecting(boolean connecting) {
                Context c = fragment.getContext();
                if (c != null) {
                    note.setText(c.getString(connecting ? R.string.dlg_smart_button_bind_connecting
                            : R.string.dlg_smart_button_bind_note));
                }
            }

            @Override
            public void onBindResult(SmartButtons.BindResult result, String address) {
                AlertDialog dialog = bindDialog;
                bindDialog = null;
                if (dialog != null) {
                    dialog.dismiss();
                }
                showResult(result, address);
                rebuildRows();
            }
        };
        bindDialog = CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_smart_button_bind_title)
                .setView(box)
                .setNegativeButton(R.string.action_cancel, null)
                .setOnDismissListener(d -> {
                    if (bindDialog != null) {
                        // 人关的（不是有了结果关的）：不绑了
                        bindDialog = null;
                        SmartButtons b = SmartButtons.get();
                        if (b != null) {
                            b.cancelBinding();
                        }
                    }
                }));
        buttons.startBinding(listener);
    }

    private void showResult(SmartButtons.BindResult result, String address) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        switch (result) {
            case BOUND:
                toast(context.getString(R.string.msg_smart_button_bound, SmartButtonAd.shortId(address)));
                break;
            case ALREADY_BOUND:
                toast(context.getString(R.string.msg_smart_button_already_bound));
                break;
            case CONNECT_FAILED:
                toast(context.getString(R.string.msg_smart_button_bind_failed));
                break;
            case FULL:
                toast(context.getString(R.string.msg_smart_button_full, SmartButtons.MAX_BOUND));
                break;
            case NOT_FOUND:
            default:
                toast(context.getString(R.string.msg_smart_button_not_found));
                break;
        }
    }

    // ================================================================= 绑定的

    /** 照存下的重摆绑定的那几行（顺序 = 绑定先后），排在「绑定灵动贴」下面。 */
    private void rebuildRows() {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        List<Preference> old = new ArrayList<>();
        for (int i = 0; i < category.getPreferenceCount(); i++) {
            Preference p = category.getPreference(i);
            if (p.getKey() != null && p.getKey().startsWith(ROW_PREFIX)) {
                old.add(p);
            }
        }
        for (Preference p : old) {
            category.removePreference(p);
        }
        SmartButtons buttons = SmartButtons.get();
        AppConfig config = new AppConfig(context);
        int order = 2;
        for (String address : config.getSmartButtons()) {
            ValueActionPreference row = new ValueActionPreference(context, null);
            row.setKey(ROW_PREFIX + address);
            row.setPersistent(false);
            row.setIconSpaceReserved(false);
            row.setOrder(order++);
            row.setTitle(context.getString(R.string.smart_button_name, SmartButtonAd.shortId(address)));
            String actions = context.getString(R.string.smart_button_summary,
                    actionLabel(context, config, address, SmartButtonAd.SINGLE),
                    actionLabel(context, config, address, SmartButtonAd.DOUBLE));
            String info = buttons == null ? "" : info(context, buttons.version(address), buttons.serial(address));
            row.setSummary(info.isEmpty() ? actions : actions + "\n" + info);
            int battery = buttons == null ? -1 : buttons.battery(address);
            row.setValue(battery < 0 ? null : context.getString(R.string.smart_button_battery, battery));
            row.setAction(context.getString(R.string.action_delete), () -> confirmDelete(address));
            row.setOnPreferenceClickListener(p -> {
                editActions(address);
                return true;
            });
            category.addPreference(row);
            // 挂上以后才能指依赖（之前找不到总开关）
            row.setDependency(SWITCH_KEY);
        }
    }

    /** 说明下面那一行：版本、序列号（有了才写），「 · 」隔开；都没有是空的。 */
    private static String info(Context context, String version, String serial) {
        List<String> parts = new ArrayList<>();
        if (version != null) {
            parts.add(context.getString(R.string.smart_button_version, version));
        }
        if (serial != null) {
            parts.add(context.getString(R.string.smart_button_serial, serial));
        }
        return String.join(" · ", parts);
    }

    private static String actionLabel(Context context, AppConfig config, String address, int clicks) {
        return SmartButtonAction.fromKey(config.getSmartButtonAction(address, clicks)).label(context);
    }

    /** 点一台：先选改单击还是双击，再选做什么。 */
    private void editActions(String address) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        AppConfig config = new AppConfig(context);
        String[] items = {
                context.getString(R.string.set_smart_button_single) + " · "
                        + actionLabel(context, config, address, SmartButtonAd.SINGLE),
                context.getString(R.string.set_smart_button_double) + " · "
                        + actionLabel(context, config, address, SmartButtonAd.DOUBLE)};
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(context.getString(R.string.smart_button_name, SmartButtonAd.shortId(address)))
                .setItems(items, (d, which) -> pickAction(address,
                        which == 0 ? SmartButtonAd.SINGLE : SmartButtonAd.DOUBLE))
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void pickAction(String address, int clicks) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        AppConfig config = new AppConfig(context);
        List<SmartButtonAction> actions = SmartButtonAction.all();
        String current = SmartButtonAction.fromKey(config.getSmartButtonAction(address, clicks)).key;
        String[] labels = new String[actions.size()];
        int checked = 0;
        for (int i = 0; i < actions.size(); i++) {
            labels[i] = actions.get(i).label(context);
            if (actions.get(i).key.equals(current)) {
                checked = i;
            }
        }
        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(clicks == SmartButtonAd.DOUBLE ? R.string.set_smart_button_double : R.string.set_smart_button_single)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    SmartButtonAction picked = actions.get(which);
                    config.setSmartButtonAction(address, clicks, picked.key);
                    com.kooo.evcam.blackbox.BlackBox.noteImportant("开关变更: 灵动贴 " + SmartButtonAd.shortId(address)
                            + (clicks == SmartButtonAd.DOUBLE ? " 双击" : " 单击") + " → " + picked.key);
                    d.dismiss();
                    rebuildRows();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void confirmDelete(String address) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.dlg_smart_button_delete_title)
                .setMessage(R.string.dlg_smart_button_delete_msg)
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    SmartButtons buttons = SmartButtons.get();
                    if (buttons != null) {
                        buttons.forget(address);
                    } else {
                        new AppConfig(context).removeSmartButton(address);
                    }
                    rebuildRows();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private void toast(String text) {
        Context context = fragment.getContext();
        if (context != null) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        }
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
