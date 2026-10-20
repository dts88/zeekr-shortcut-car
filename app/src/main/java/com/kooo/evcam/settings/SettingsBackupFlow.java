package com.kooo.evcam.settings;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.CamDialogs;
import com.kooo.evcam.ui.FailureReason;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 「设置 → 系统」里「导出设置」「导入设置」两行的流程（读写见 {@link SettingsBackup}）。
 *
 * <ul>
 *   <li><b>导出</b>：写到诊断报告的那个目录，提示写到了哪里。</li>
 *   <li><b>导入</b>：列出那个目录里的设置文件（新的在前，写着导出时间和版本）→ 选一个 → 别的大版本的说明不能导入 →
 *       确认（当前设置会被覆盖、应用会退出）→ 写回去 → 走主界面的退出（{@link MainActivity#exitApp}：停录、按次序关相机、
 *       收好文件）。重新打开时一切照新设置起来，不用一项一项去通知正在跑的服务（项目所有者 2026-10-19）。</li>
 * </ul>
 *
 * <p>读写文件都在后台线程上做，对话框和提示回到主线程；页面这期间关了就不再弹。</p>
 */
public final class SettingsBackupFlow {

    private static final String TAG = "SettingsBackupFlow";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SettingsBackupFlow() {
    }

    // ================================================================= 导出

    public static void export(Activity activity) {
        new Thread(() -> {
            File out = null;
            Exception failure = null;
            try {
                out = SettingsBackup.export(activity);
            } catch (Exception e) {
                AppLog.e(TAG, "导出设置失败", e);
                failure = e;
            }
            final File written = out;
            final Exception error = failure;
            MAIN.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                String message;
                if (written != null) {
                    message = activity.getString(R.string.settings_exported, written.getAbsolutePath());
                } else {
                    String reason = FailureReason.of(activity, error);
                    message = reason != null ? activity.getString(R.string.settings_export_failed_reason, reason)
                            : activity.getString(R.string.settings_export_failed);
                }
                Toast.makeText(activity.getApplicationContext(), message, Toast.LENGTH_LONG).show();
            });
        }, "settings-export").start();
    }

    // ================================================================= 导入

    public static void startImport(Activity activity) {
        new Thread(() -> {
            File folder = SettingsBackup.folder(activity);
            List<SettingsBackup.Backup> backups = new ArrayList<>();
            for (File file : SettingsBackup.list(activity)) {
                backups.add(SettingsBackup.read(activity, file));
            }
            MAIN.post(() -> {
                if (activity.isFinishing() || activity.isDestroyed()) {
                    return;
                }
                if (backups.isEmpty()) {
                    CamDialogs.show(builder(activity)
                            .setTitle(R.string.settings_none_title)
                            .setMessage(activity.getString(R.string.settings_none_msg,
                                    folder != null ? folder.getAbsolutePath() : "?"))
                            .setPositiveButton(R.string.action_got_it, null));
                    return;
                }
                String[] labels = new String[backups.size()];
                for (int i = 0; i < labels.length; i++) {
                    labels[i] = label(activity, backups.get(i));
                }
                CamDialogs.show(builder(activity)
                        .setTitle(R.string.settings_pick_title)
                        .setItems(labels, (d, which) -> chosen(activity, backups.get(which)))
                        .setNegativeButton(R.string.action_cancel, null));
            });
        }, "settings-import-list").start();
    }

    /** 列表里的一行：导出时间 · 版本；读不出来的写文件名。 */
    private static String label(Activity activity, SettingsBackup.Backup backup) {
        if (backup.exportedAt <= 0L || backup.appVersion.isEmpty()) {
            return backup.file.getName();
        }
        // 数字写法，哪种语言都一样（和诊断报告、文件名里的时间同一种写法）
        String when = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date(backup.exportedAt));
        return activity.getString(R.string.settings_file_label, when, backup.appVersion);
    }

    private static void chosen(Activity activity, SettingsBackup.Backup backup) {
        switch (backup.status) {
            case OK:
                CamDialogs.showDestructive(builder(activity)
                        .setTitle(R.string.settings_import_confirm_title)
                        .setMessage(R.string.settings_import_confirm_msg)
                        .setPositiveButton(R.string.settings_import_action, (d, w) -> apply(activity, backup))
                        .setNegativeButton(R.string.action_cancel, null));
                break;
            case OTHER_VERSION:
                CamDialogs.show(builder(activity)
                        .setTitle(R.string.settings_import_refused_title)
                        .setMessage(activity.getString(R.string.settings_other_version_msg,
                                backup.appVersion.isEmpty() ? "?" : backup.appVersion,
                                SettingsBackup.currentMajor(activity)))
                        .setPositiveButton(R.string.action_got_it, null));
                break;
            default:
                Toast.makeText(activity.getApplicationContext(), R.string.settings_unreadable,
                        Toast.LENGTH_LONG).show();
                break;
        }
    }

    /** 写回去（后台线程，同步落盘），写成了就走主界面的退出。 */
    private static void apply(Activity activity, SettingsBackup.Backup backup) {
        new Thread(() -> {
            boolean ok = SettingsBackup.apply(activity, backup);
            MAIN.post(() -> {
                if (!ok) {
                    Toast.makeText(activity.getApplicationContext(), R.string.settings_import_failed,
                            Toast.LENGTH_LONG).show();
                    return;
                }
                if (activity instanceof MainActivity && !activity.isFinishing()) {
                    ((MainActivity) activity).exitApp();
                } else {
                    // 设置页只长在主界面里（不会走到这里）：写好了，提示重新打开
                    AppLog.w(TAG, "导入设置之后找不到主界面，没有退出");
                    Toast.makeText(activity.getApplicationContext(), R.string.settings_imported_reopen,
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "settings-import").start();
    }

    private static MaterialAlertDialogBuilder builder(Activity activity) {
        return new MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog);
    }
}
