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
        AlertDialog checking = message(activity, "正在检查更新…", false);
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
                    toast(activity, "检查更新失败：" + failure);
                } else if (found == null) {
                    toast(activity, "没有找到可安装的版本");
                } else {
                    compareAndOffer(activity, found);
                }
            });
        }, "update-check").start();
    }

    private static void compareAndOffer(Activity activity, GithubReleases.Release release) {
        String current = currentVersion(activity);
        if (!VersionName.isNewer(release.tagName, current)) {
            toast(activity, "已是最新版本（" + current + "）");
            return;
        }

        String size = release.apkBytes > 0
                ? String.format(Locale.US, "，约 %.1f MB", release.apkBytes / 1024f / 1024f)
                : "";
        new AlertDialog.Builder(activity, R.style.AlertDialogTheme)
                .setTitle("发现新版本")
                .setMessage(release.tagName + size + "\n\n当前版本 " + current
                        + "\n\n下载完会直接进入系统的安装界面。安装包放在应用缓存里，"
                        + "下次检查更新时自动清掉。")
                .setPositiveButton("下载并安装", (d, w) -> download(activity, release))
                .setNegativeButton("稍后", null)
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
        label.setText("正在下载…");
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

        AlertDialog dialog = new AlertDialog.Builder(activity, R.style.AlertDialogTheme)
                .setTitle("下载 " + release.tagName)
                .setView(box)
                .setCancelable(false)
                .create();
        dialog.show();

        new Thread(() -> {
            String error = null;
            try {
                GithubReleases.download(release, target, (done, total) -> {
                    if (total <= 0) {
                        return;
                    }
                    int percent = (int) (done * 100 / total);
                    post(activity, () -> {
                        bar.setProgress(percent);
                        label.setText(String.format(Locale.US, "正在下载… %d%%（%.1f / %.1f MB）",
                                percent, done / 1024f / 1024f, total / 1024f / 1024f));
                    });
                });
            } catch (Exception e) {
                AppLog.e(TAG, "下载失败", e);
                error = e.getMessage();
            }
            final String failure = error;
            post(activity, () -> {
                dismiss(dialog);
                if (failure != null) {
                    toast(activity, "下载失败：" + failure);
                } else {
                    install(activity, target);
                }
            });
        }, "update-download").start();
    }

    // ------------------------------------------------------------------ 安装

    private static void install(Activity activity, File apk) {
        if (!apk.isFile() || apk.length() == 0) {
            toast(activity, "安装包不见了，请重试");
            return;
        }
        // Android 8 起「安装未知来源应用」是一项单独授权，没有它 startActivity 会被静默挡掉
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity, R.style.AlertDialogTheme)
                    .setTitle("需要允许安装应用")
                    .setMessage("系统要求先允许本应用安装其他应用，才能进入安装界面。"
                            + "\n\n授权后回到这里再点一次「检查更新」。")
                    .setPositiveButton("去设置", (d, w) -> openInstallPermission(activity))
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        Uri uri;
        try {
            uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
        } catch (IllegalArgumentException e) {
            AppLog.e(TAG, "FileProvider 拿不到 URI", e);
            toast(activity, "无法打开安装包：" + e.getMessage());
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            activity.startActivity(intent);
        } catch (Exception e) {
            AppLog.e(TAG, "打不开安装界面", e);
            toast(activity, "这台车机上打不开安装界面：" + e.getMessage());
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
            toast(activity, "这台车机上打不开该设置页");
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
