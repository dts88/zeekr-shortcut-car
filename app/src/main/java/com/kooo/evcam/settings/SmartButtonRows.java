package com.kooo.evcam.settings;

import android.content.Context;
import android.util.TypedValue;
import android.view.View;
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
 * 设置 → 悬浮键与灵动贴 → 灵动贴（试验性）（项目所有者 2026-10-30，ui-spec §十一）。
 *
 * <ul>
 *   <li>「搜索灵动贴」一行：蓝牙要开着、要有「附近的设备」权限（没有就当场要）。框里列搜到的 —— 只列名字是 ZEEKR_button、
 *       还没连接过的，信号强的在前 —— 点哪一台就连接哪一台。灵动贴只在按下时广播，所以框里提示按一下。</li>
 *   <li>连接过的每一台一行（{@link ValueActionPreference}）：名字「灵动贴 B5:E6」，说明写单击、双击各做什么，行尾值是电量，
 *       按钮「删除」（先问一句）；点这一行改单击、双击（{@link SmartButtonAction}）。</li>
 * </ul>
 *
 * <p>真正的连接、听、读电量在 {@link SmartButtons}；这里只摆界面。连接过的、电量变了，它叫一声，这里照着重摆。</p>
 */
final class SmartButtonRows {

    /** 连接过的那几行的 key 前缀（后面接地址）。 */
    private static final String ROW_PREFIX = "smart_button.";

    private final SettingsPreferenceFragment fragment;
    private final PreferenceCategory category;
    private final ActivityResultLauncher<String[]> permissions;
    private final Runnable onChanged = this::rebuildRows;
    private SmartButtons.SearchListener searchListener;
    private AlertDialog searchDialog;
    /** 为了搜索去要的权限，回来了接着搜。 */
    private boolean searchAfterPermission;

    SmartButtonRows(SettingsPreferenceFragment fragment, PreferenceCategory category,
                    ActivityResultLauncher<String[]> permissions) {
        this.fragment = fragment;
        this.category = category;
        this.permissions = permissions;
    }

    void bind() {
        Preference search = category.findPreference("pref_smart_button_search");
        if (search != null) {
            search.setOnPreferenceClickListener(p -> {
                startSearch();
                return true;
            });
        }
        SmartButtons buttons = SmartButtons.get();
        if (buttons != null) {
            buttons.addChangeListener(onChanged);
        }
        rebuildRows();
    }

    /** 这一页没了：不再听变化，搜索框关掉（关掉时停止搜索）。 */
    void detach() {
        SmartButtons buttons = SmartButtons.get();
        if (buttons != null) {
            buttons.removeChangeListener(onChanged);
        }
        if (searchDialog != null) {
            searchDialog.dismiss();
            searchDialog = null;
        }
        stopSearch();
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
        if (!searchAfterPermission) {
            return;
        }
        searchAfterPermission = false;
        if (SmartButtons.canScan(context)) {
            showSearch();
        } else {
            toast(R.string.msg_smart_button_no_permission);
        }
    }

    // ================================================================= 搜索

    private void startSearch() {
        SmartButtons buttons = SmartButtons.get();
        Context context = fragment.getContext();
        if (buttons == null || context == null) {
            return;
        }
        if (!buttons.hasBluetooth()) {
            toast(R.string.msg_smart_button_no_bluetooth);
            return;
        }
        if (!buttons.bluetoothOn()) {
            toast(R.string.msg_smart_button_bluetooth_off);
            return;
        }
        if (!SmartButtons.canScan(context) || !SmartButtons.canConnect(context)) {
            searchAfterPermission = true;
            permissions.launch(SmartButtons.permissions());
            return;
        }
        showSearch();
    }

    /** 搜索框：上面是搜到的几台（一台一行，点了就连接），下面一行说明。关掉就停止搜索。 */
    private void showSearch() {
        SmartButtons buttons = SmartButtons.get();
        Context context = fragment.getContext();
        if (buttons == null || context == null || searchDialog != null) {
            return;
        }
        int pad = dp(context, 24);
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(context, 8), pad, 0);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        box.addView(list);
        TextView note = CamDialogs.note(context, context.getString(R.string.dlg_smart_button_search_note));
        note.setPadding(0, dp(context, 12), 0, 0);
        box.addView(note);

        searchListener = found -> fillSearch(list, found);
        searchDialog = CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.set_smart_button_search_title)
                .setView(box)
                .setNegativeButton(R.string.action_close, null)
                .setOnDismissListener(d -> {
                    searchDialog = null;
                    stopSearch();
                }));
        buttons.addSearchListener(searchListener);
    }

    private void stopSearch() {
        SmartButtons buttons = SmartButtons.get();
        if (buttons != null && searchListener != null) {
            buttons.removeSearchListener(searchListener);
        }
        searchListener = null;
    }

    /** 搜到的几台一台一行（和选择框的选项一样大的字），点了就连接、关框。 */
    private void fillSearch(LinearLayout list, List<SmartButtons.Found> found) {
        Context context = fragment.getContext();
        if (context == null) {
            return;
        }
        list.removeAllViews();
        TypedValue ripple = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        for (SmartButtons.Found f : found) {
            TextView row = new TextView(context);
            row.setTextAppearance(R.style.TextAppearance_Cam_Body);
            row.setText(context.getString(R.string.smart_button_name, SmartButtonAd.shortId(f.address)));
            row.setMinHeight(dp(context, 56));
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setBackgroundResource(ripple.resourceId);
            row.setOnClickListener(v -> connect(f.address));
            list.addView(row);
        }
        list.setVisibility(found.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void connect(String address) {
        SmartButtons buttons = SmartButtons.get();
        if (buttons == null) {
            return;
        }
        if (searchDialog != null) {
            searchDialog.dismiss();
        }
        buttons.connect(address);
        Context context = fragment.getContext();
        if (context != null) {
            Toast.makeText(context, context.getString(R.string.msg_smart_button_connected,
                    SmartButtonAd.shortId(address)), Toast.LENGTH_SHORT).show();
        }
    }

    // ================================================================= 连接过的

    /** 照存下的重摆连接过的那几行（顺序 = 连接先后），排在「搜索灵动贴」下面。 */
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
        int order = 1;
        for (String address : config.getSmartButtons()) {
            ValueActionPreference row = new ValueActionPreference(context, null);
            row.setKey(ROW_PREFIX + address);
            row.setPersistent(false);
            row.setIconSpaceReserved(false);
            row.setOrder(order++);
            row.setTitle(context.getString(R.string.smart_button_name, SmartButtonAd.shortId(address)));
            row.setSummary(context.getString(R.string.smart_button_summary,
                    actionLabel(context, config, address, SmartButtonAd.SINGLE),
                    actionLabel(context, config, address, SmartButtonAd.DOUBLE)));
            int battery = buttons == null ? -1 : buttons.battery(address);
            row.setValue(battery < 0 ? null : context.getString(R.string.smart_button_battery, battery));
            row.setAction(context.getString(R.string.action_delete), () -> confirmDelete(address));
            row.setOnPreferenceClickListener(p -> {
                editActions(address);
                return true;
            });
            category.addPreference(row);
        }
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

    private void toast(int text) {
        Context context = fragment.getContext();
        if (context != null) {
            Toast.makeText(context, text, Toast.LENGTH_SHORT).show();
        }
    }

    private static int dp(Context context, int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }
}
