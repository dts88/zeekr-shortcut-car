package com.kooo.evcam.repair;

import android.app.Activity;
import android.os.Environment;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.ui.CamDialogs;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 「归档到其他 U 盘」这个开发者入口的全部流程：把录像、照片、日志从录像盘搬到另一个盘。
 *
 * <p>项目所有者的用法（2026-09-27）：平时录在 USB-A 口的盘上（熄屏后只有这个口还能用），
 * 定期把东西归档到 Type-C 口的固态盘统一管理。</p>
 *
 * <h3>规矩</h3>
 *
 * <ul>
 *   <li><b>录制中不归档</b>：正在写的那一段搬不得。</li>
 *   <li><b>只搬本应用的目录</b>：{@code DCIM/EVCam_Video}、{@code DCIM/EVCam_Photo}、
 *       {@code Download/EVCam_Log}（诊断报告），盘上别人的东西一律不碰。
 *       应用自己的运行日志（内部存储）是<b>复制</b>不是搬 —— 它还在写。</li>
 *   <li><b>搬 = 复制、核对大小、再删源文件</b>；目标盘上同名同大小的文件当作搬过了，只删源文件；
 *       复制失败的源文件原样留着，写了一半的目标文件删掉。</li>
 *   <li><b>两分钟内动过的文件当成还在写，跳过。</b></li>
 *   <li>目标盘剩余空间不够就不开始。</li>
 * </ul>
 */
public final class ArchiveFlow {

    private static final String TAG = "ArchiveFlow";

    /** 这么短时间内动过的文件，当成还在写，不碰。 */
    private static final long FRESH_MS = 120_000L;

    private ArchiveFlow() {
    }

    // ================================================================= 入口

    public static void start(Activity activity, boolean recording) {
        if (recording) {
            CamDialogs.show(builder(activity)
                    .setTitle(R.string.dev_tool_recording_title)
                    .setMessage(R.string.dev_archive_refuse_recording)
                    .setPositiveButton(R.string.action_got_it, null));
            return;
        }
        StorageHelper.clearCache();
        File source = StorageHelper.getExternalSdCardRoot(activity);
        if (source == null) {
            CamDialogs.show(builder(activity)
                    .setTitle(R.string.dlg_no_external_title)
                    .setMessage(R.string.dev_archive_no_drive_msg)
                    .setPositiveButton(R.string.action_got_it, null));
            return;
        }
        List<File> targets = new ArrayList<>();
        for (File root : StorageHelper.mountedVolumes()) {
            if (!root.getAbsolutePath().equals(source.getAbsolutePath())) {
                targets.add(root);
            }
        }
        if (targets.isEmpty()) {
            CamDialogs.show(builder(activity)
                    .setTitle(R.string.dev_archive_one_drive_title)
                    .setMessage(activity.getString(R.string.dev_archive_one_drive_msg, source.getName()))
                    .setPositiveButton(R.string.action_got_it, null));
            return;
        }
        if (targets.size() == 1) {
            scan(activity, source, targets.get(0));
            return;
        }
        // 和「存储位置」列表里同一种写法：盘名 · 剩余 / 共
        String[] labels = new String[targets.size()];
        for (int i = 0; i < targets.size(); i++) {
            File target = targets.get(i);
            labels[i] = activity.getString(R.string.storage_volume_desc, target.getName(),
                    size(target.getUsableSpace()), size(target.getTotalSpace()));
        }
        CamDialogs.show(builder(activity)
                .setTitle(R.string.dev_archive_pick_target)
                .setItems(labels, (d, which) -> scan(activity, source, targets.get(which)))
                .setNegativeButton(R.string.action_cancel, null));
    }

    // ================================================================= 清点

    /** 一个要搬的文件：在源盘的哪里、搬到目标盘的哪里、搬还是只复制。 */
    private static final class Item {
        final File from;
        final File to;
        final boolean move;

        Item(File from, File to, boolean move) {
            this.from = from;
            this.to = to;
            this.move = move;
        }
    }

    private static final class Plan {
        final List<Item> items = new ArrayList<>();
        int videos;
        int photos;
        int logs;
        int skippedFresh;
        long bytes;
    }

    private static void scan(Activity activity, File source, File target) {
        AlertDialog waiting = spinner(activity, activity.getString(R.string.dev_archive_scanning, source.getName()));
        new Thread(() -> {
            Plan plan = new Plan();
            File dcimFrom = new File(source, Environment.DIRECTORY_DCIM);
            File dcimTo = new File(target, Environment.DIRECTORY_DCIM);
            File downloadFrom = new File(source, Environment.DIRECTORY_DOWNLOADS);
            File downloadTo = new File(target, Environment.DIRECTORY_DOWNLOADS);
            plan.videos = collect(plan, new File(dcimFrom, StorageHelper.VIDEO_DIR_NAME),
                    new File(dcimTo, StorageHelper.VIDEO_DIR_NAME), true);
            plan.photos = collect(plan, new File(dcimFrom, StorageHelper.PHOTO_DIR_NAME),
                    new File(dcimTo, StorageHelper.PHOTO_DIR_NAME), true);
            plan.logs = collect(plan, new File(downloadFrom, StorageHelper.LOG_DIR_NAME),
                    new File(downloadTo, StorageHelper.LOG_DIR_NAME), true);
            // 应用自己的运行日志还在写：只复制，放在目标盘日志目录下的 app-logs 里
            plan.logs += collect(plan, new File(activity.getFilesDir(), "logs"),
                    new File(new File(downloadTo, StorageHelper.LOG_DIR_NAME), "app-logs"), false);
            activity.runOnUiThread(() -> {
                dismiss(waiting);
                confirm(activity, source, target, plan);
            });
        }, "archive-scan").start();
    }

    /** 把一个目录里的文件（含子目录）加进清单，返回加进去的个数。 */
    private static int collect(Plan plan, File fromDir, File toDir, boolean move) {
        File[] files = fromDir.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        long now = System.currentTimeMillis();
        for (File file : files) {
            if (file.isDirectory()) {
                count += collect(plan, file, new File(toDir, file.getName()), move);
                continue;
            }
            if (!file.isFile()) {
                continue;
            }
            if (now - file.lastModified() < FRESH_MS) {
                plan.skippedFresh++;
                continue;
            }
            plan.items.add(new Item(file, new File(toDir, file.getName()), move));
            plan.bytes += file.length();
            count++;
        }
        return count;
    }

    private static void confirm(Activity activity, File source, File target, Plan plan) {
        String skipped = plan.skippedFresh > 0 ? "\n\n" + activity.getResources().getQuantityString(
                R.plurals.dev_archive_skipped_fresh, plan.skippedFresh, plan.skippedFresh) : "";
        if (plan.items.isEmpty()) {
            CamDialogs.show(builder(activity)
                    .setTitle(R.string.dev_archive_nothing_title)
                    .setMessage(activity.getString(R.string.dev_archive_nothing_msg, source.getName()) + skipped)
                    .setPositiveButton(R.string.action_got_it, null));
            return;
        }
        long free = target.getUsableSpace();
        StringBuilder message = new StringBuilder();
        message.append(source.getName()).append(" → ").append(target.getName()).append("\n\n")
                .append(activity.getString(R.string.dev_archive_counts,
                        plan.videos, plan.photos, plan.logs, size(plan.bytes))).append('\n')
                .append(activity.getString(R.string.dev_archive_target_free, size(free))).append("\n\n")
                .append(activity.getString(R.string.dev_archive_explain))
                .append(skipped);
        if (free < plan.bytes) {
            message.append("\n\n").append(activity.getString(R.string.dev_archive_no_space));
            CamDialogs.show(builder(activity)
                    .setTitle(R.string.dev_archive_title)
                    .setMessage(message.toString())
                    .setPositiveButton(R.string.action_got_it, null));
            return;
        }
        CamDialogs.show(builder(activity)
                .setTitle(R.string.dev_archive_title)
                .setMessage(message.toString())
                .setPositiveButton(R.string.dev_archive_start, (d, w) -> run(activity, source, target, plan))
                .setNegativeButton(R.string.action_cancel, null));
    }

    // ================================================================= 搬

    private static void run(Activity activity, File source, File target, Plan plan) {
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView label = text(activity, "");
        LinearLayout box = column(activity);
        box.addView(label, wide());
        box.addView(bar, wide());
        AtomicBoolean cancelled = new AtomicBoolean(false);

        AlertDialog dialog = CamDialogs.style(builder(activity)
                .setTitle(R.string.dev_archive_running)
                .setView(box)
                .setCancelable(false)
                .setNegativeButton(R.string.dev_archive_stop, (d, w) -> cancelled.set(true))
                .create());
        dialog.show();

        new Thread(() -> {
            int moved = 0;
            int copied = 0;
            int already = 0;
            long done = 0;
            List<String> failed = new ArrayList<>();
            byte[] buffer = new byte[1 << 20];
            for (int i = 0; i < plan.items.size() && !cancelled.get(); i++) {
                Item item = plan.items.get(i);
                final int index = i;
                final long doneSoFar = done;
                activity.runOnUiThread(() -> {
                    label.setText(activity.getString(R.string.dev_tool_progress,
                            index + 1, plan.items.size(), item.from.getName()));
                    bar.setProgress(plan.bytes > 0 ? (int) (doneSoFar * 100 / plan.bytes) : 0);
                });
                long length = item.from.length();
                try {
                    if (item.to.isFile() && item.to.length() == length) {
                        already++;
                    } else {
                        copy(item.from, item.to, buffer);
                        if (item.move) {
                            moved++;
                        } else {
                            copied++;
                        }
                    }
                    if (item.move && !item.from.delete()) {
                        failed.add(activity.getString(R.string.dev_archive_delete_failed, item.from.getName()));
                    }
                } catch (IOException e) {
                    AppLog.e(TAG, "归档失败：" + item.from.getAbsolutePath(), e);
                    if (item.to.exists() && !item.to.delete()) {
                        AppLog.w(TAG, "写了一半的目标文件删不掉：" + item.to.getAbsolutePath());
                    }
                    // 原因说得清（空间不足之类）就写上；说不清的只列文件名，原文在上面那行日志里
                    String reason = com.kooo.evcam.ui.FailureReason.of(activity, e);
                    failed.add(reason == null ? item.from.getName()
                            : activity.getString(R.string.dev_tool_file_result, item.from.getName(), reason));
                }
                done += length;
            }
            StorageHelper.clearCache();
            final int m = moved;
            final int c = copied;
            final int a = already;
            final long bytes = done;
            final boolean stopped = cancelled.get();
            com.kooo.evcam.blackbox.BlackBox.noteImportant("归档 " + source.getName() + " → " + target.getName()
                    + "：搬了 " + m + " 个，复制 " + c + " 个，已有 " + a + " 个，失败 " + failed.size() + " 个，"
                    + size(bytes) + (stopped ? "，中途停止" : ""));
            activity.runOnUiThread(() -> {
                dismiss(dialog);
                StringBuilder report = new StringBuilder();
                report.append(activity.getString(R.string.dev_archive_report, target.getName(), m, c, a, size(bytes)));
                if (stopped) {
                    report.append("\n\n").append(activity.getString(R.string.dev_archive_stopped, source.getName()));
                }
                if (!failed.isEmpty()) {
                    report.append("\n\n").append(activity.getString(R.string.dev_archive_failed, failed.size()))
                            .append('\n').append(join(failed));
                }
                CamDialogs.show(builder(activity)
                        .setTitle(failed.isEmpty() && !stopped
                                ? R.string.dev_archive_done_title : R.string.dev_archive_ended_title)
                        .setMessage(report.toString())
                        .setPositiveButton(R.string.action_got_it, null));
            });
        }, "archive").start();
    }

    /** 复制一个文件：写完落盘、核对大小、保留修改时间（回看按时间排序靠它）。 */
    private static void copy(File from, File to, byte[] buffer) throws IOException {
        File dir = to.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("建不了目录 " + dir.getAbsolutePath());
        }
        try (FileInputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
            }
            out.getFD().sync();
        }
        if (to.length() != from.length()) {
            throw new IOException("复制后大小不对：" + to.length() + " ≠ " + from.length());
        }
        if (!to.setLastModified(from.lastModified())) {
            AppLog.w(TAG, "修改时间没保住：" + to.getAbsolutePath());
        }
    }

    // ================================================================= 零碎

    private static String size(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format(Locale.US, "%.1f GB", bytes / 1024f / 1024f / 1024f);
        }
        return String.format(Locale.US, "%.0f MB", bytes / 1024f / 1024f);
    }

    private static String join(List<String> lines) {
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append(line);
        }
        return out.toString();
    }

    private static MaterialAlertDialogBuilder builder(Activity activity) {
        return new MaterialAlertDialogBuilder(activity, R.style.Theme_Cam_MaterialAlertDialog);
    }

    private static AlertDialog spinner(Activity activity, String message) {
        ProgressBar bar = new ProgressBar(activity);
        bar.setIndeterminate(true);
        LinearLayout box = column(activity);
        box.addView(text(activity, message), wide());
        box.addView(bar, wide());
        AlertDialog dialog = CamDialogs.style(builder(activity)
                .setView(box)
                .setCancelable(false)
                .create());
        dialog.show();
        return dialog;
    }

    private static LinearLayout column(Activity activity) {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24,
                activity.getResources().getDisplayMetrics());
        box.setPadding(pad, pad, pad, pad);
        box.setGravity(Gravity.CENTER_VERTICAL);
        return box;
    }

    private static TextView text(Activity activity, String message) {
        TextView view = new TextView(activity);
        view.setText(message);
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                activity.getResources().getDimension(R.dimen.text_body));
        return view;
    }

    private static LinearLayout.LayoutParams wide() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static void dismiss(AlertDialog dialog) {
        try {
            dialog.dismiss();
        } catch (Exception ignored) {
            // 界面已经没了
        }
    }
}
