package com.kooo.evcam.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

import java.io.File;
import java.util.Locale;

/**
 * 「检查更新」这件事从头到尾。
 *
 * <h3>装完之后不留东西</h3>
 *
 * <p>APK 下到<b>应用缓存目录</b>。系统装包必须从一个真实文件读，没法从内存直接装，
 * 所以「不落盘」做不到；能做到的是不落到用户的存储里，并且<b>每次检查前先把上一次
 * 的残留清掉</b>。缓存目录也在系统的回收范围内，空间紧张时会被自动清理。</p>
 *
 * <h3>这是本应用唯一一次主动出网</h3>
 *
 * <p>只在用户点这一项时发生，不带设备信息，也不上传任何东西。</p>
 */
public final class UpdateFlow {

    private static final String TAG = "UpdateFlow";
    private static final String CACHE_DIR = "update";

    private UpdateFlow() {
    }

    public static void start(Activity activity) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        AlertDialog checking = message(activity,
                activity.getString(R.string.upd_checking), false);
        new Thread(() -> {
            GithubReleases.Release release = null;
            String error = null;
            try {
                release = GithubReleases.fetchLatest();
            } catch (Exception e) {
                AppLog.w(TAG, "检查更新失败: " + e);
                error = e.getMessage();
            }
            final GithubReleases.Release found = release;
            final String failure = error;
            post(activity, () -> {
                dismiss(checking);
                if (failure != null) {
                    toast(activity, activity.getString(R.string.upd_check_failed, failure));
                } else if (found == null) {
                    toast(activity, activity.getString(R.string.upd_none));
                } else {
                    compareAndOffer(activity, found);
                }
            });
        }, "update-check").start();
    }

    private static void compareAndOffer(Activity activity, GithubReleases.Release release) {
        String current = currentVersion(activity);
        if (!VersionName.isNewer(release.tagName, current)) {
            toast(activity, activity.getString(R.string.upd_up_to_date, current));
            return;
        }

        String size = release.apkBytes > 0
                ? activity.getString(R.string.upd_size_suffix,
                        String.format(Locale.US, "%.1f", release.apkBytes / 1024f / 1024f))
                : "";
        new AlertDialog.Builder(activity, R.style.AlertDialogTheme)
                .setTitle(R.string.upd_found_title)
                .setMessage(activity.getString(R.string.upd_found_msg,
                        release.tagName, size, current))
                .setPositiveButton(R.string.upd_download, (d, w) -> download(activity, release))
                .setNegativeButton(R.string.upd_later, null)
                .show();
    }

    // ------------------------------------------------------------------ 下载

    private static void download(Activity activity, GithubReleases.Release release) {
        File dir = new File(activity.getCacheDir(), CACHE_DIR);
        clear(dir);
        File target = new File(dir, release.apkName);

        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView label = new TextView(activity);
        label.setText(R.string.upd_downloading);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24,
                activity.getResources().getDisplayMetrics());
        box.setPadding(pad, pad, pad, pad);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 「连接中」和「已经在下但没进度」是两回事，界面上要能分清 ——
        // 否则卡在哪一步都只能看到同一句「正在下载…」
        label.setText(R.string.upd_connecting);
        bar.setIndeterminate(true);

        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.AlertDialogTheme)
                .setTitle(activity.getString(R.string.upd_download_title, release.tagName))
                .setView(box)
                .setCancelable(false)
                .create();
        dialog.show();
        AppLog.i(TAG, "开始下载 " + release.apkName + "：" + release.apkUrl
                + "，存到 " + target);

        new Thread(() -> {
            String error = null;
            try {
                GithubReleases.download(release, target, (done, total) -> post(activity, () -> {
                    if (total > 0) {
                        int percent = (int) (done * 100 / total);
                        bar.setIndeterminate(false);
                        bar.setProgress(percent);
                        label.setText(activity.getString(R.string.upd_downloading_pct,
                                percent,
                                String.format(Locale.US, "%.1f", done / 1024f / 1024f),
                                String.format(Locale.US, "%.1f", total / 1024f / 1024f)));
                    } else {
                        // 对面没给长度：算不出百分比，但至少让人看见字节在涨
                        label.setText(activity.getString(R.string.upd_downloading_size,
                                String.format(Locale.US, "%.1f", done / 1024f / 1024f)));
                    }
                }));
            } catch (Exception e) {
                AppLog.e(TAG, "下载失败", e);
                error = e.getClass().getSimpleName()
                        + (e.getMessage() == null ? "" : "：" + e.getMessage());
            }
            final String failure = error;
            post(activity, () -> {
                dismiss(dialog);
                if (failure != null) {
                    // 用对话框而不是 toast：下载失败是需要看清原因的，
                    // 一闪而过的提示等于「点了没反应」
                    new AlertDialog.Builder(activity, R.style.AlertDialogTheme)
                            .setTitle(R.string.upd_download_failed_title)
                            .setMessage(activity.getString(
                                    R.string.upd_download_failed, failure))
                            .setPositiveButton(R.string.action_got_it, null)
                            .show();
                } else {
                    install(activity, target);
                }
            });
        }, "update-download").start();
    }

    // ------------------------------------------------------------------ 安装

    private static void install(Activity activity, File apk) {
        if (!apk.isFile() || apk.length() == 0) {
            toast(activity, activity.getString(R.string.upd_apk_missing));
            return;
        }
        // Android 8 起「安装未知来源应用」是一项单独授权，没有它 startActivity 会被静默挡掉
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity, R.style.AlertDialogTheme)
                    .setTitle(R.string.upd_need_install_title)
                    .setMessage(R.string.upd_need_install_msg)
                    .setPositiveButton(R.string.upd_go_settings,
                            (d, w) -> openInstallPermission(activity))
                    .setNegativeButton(R.string.action_cancel, null)
                    .show();
            return;
        }

        Uri uri;
        try {
            uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
        } catch (IllegalArgumentException e) {
            AppLog.e(TAG, "FileProvider 拿不到 URI", e);
            toast(activity, activity.getString(R.string.upd_cannot_open_apk, e.getMessage()));
            return;
        }

        AppLog.i(TAG, "下载完成，打开安装界面：" + apk + "（" + apk.length() + " 字节）");
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            AppLog.e(TAG, "打不开安装界面", e);
            toast(activity, activity.getString(R.string.upd_no_installer, e.getMessage()));
        }
    }

    private static void openInstallPermission(Activity activity) {
        try {
            Intent intent = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                    ? new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:" + activity.getPackageName()))
                    : new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception e) {
            toast(activity, activity.getString(R.string.upd_no_settings_page));
        }
    }

    // ------------------------------------------------------------------ 小工具

    /** 上一次下的东西不留 —— 缓存目录里躺一个旧 APK 没有任何用处。 */
    private static void clear(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (!file.delete()) {
                AppLog.w(TAG, "旧的安装包删不掉: " + file);
            }
        }
    }

    private static String currentVersion(Context context) {
        try {
            return context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "";
        }
    }

    private static AlertDialog message(Activity activity, String text, boolean cancelable) {
        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.AlertDialogTheme)
                .setMessage(text)
                .setCancelable(cancelable)
                .create();
        dialog.show();
        return dialog;
    }

    private static void dismiss(AlertDialog dialog) {
        if (dialog != null && dialog.isShowing()) {
            try {
                dialog.dismiss();
            } catch (IllegalArgumentException e) {
                // 界面已经没了，忽略
            }
        }
    }

    /** 界面可能在等网络的这几秒里被关掉，回来之前先确认它还在。 */
    private static void post(Activity activity, Runnable action) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        activity.runOnUiThread(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                action.run();
            }
        });
    }

    private static void toast(Context context, String text) {
        Toast.makeText(context, text, Toast.LENGTH_LONG).show();
    }
}
