package com.kooo.evcam.repair;

import android.app.Activity;
import android.media.MediaExtractor;
import android.media.MediaFormat;
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
import com.kooo.evcam.camera.StoragePlan;
import com.kooo.evcam.ui.CamDialogs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 「修复缺少索引的录像」这个开发者入口的全部流程。
 *
 * <h3>不碰不该碰的</h3>
 *
 * <ul>
 *   <li><b>录制中不给修。</b>正在录的那一段本来就没有索引，看起来和断电留下的一模一样，
 *       但 MediaMuxer 还在往里写 —— 这时候往文件尾追加东西，就是在毁掉正在录的片段。</li>
 *   <li><b>只看本应用录的文件</b>（{@link StoragePlan#isOwnClip}），U 盘上别人的东西一律不碰。</li>
 *   <li><b>刚写过的不碰</b>：两分钟内动过的文件当成还在录，跳过。</li>
 *   <li><b>修完要验</b>：用 MediaExtractor 真读一遍，读不出来就原样退回去。</li>
 * </ul>
 */
public final class Mp4RepairFlow {

    private static final String TAG = "Mp4RepairFlow";

    /** 这么短时间内动过的文件，当成还在录，不碰。 */
    private static final long FRESH_MS = 120_000L;

    private Mp4RepairFlow() {
    }

    // ================================================================= 入口

    public static void start(Activity activity, boolean recording) {
        if (recording) {
            CamDialogs.show(builder(activity)
                    .setTitle(R.string.dev_tool_recording_title)
                    .setMessage(R.string.dev_repair_refuse_recording)
                    .setPositiveButton(R.string.action_got_it, null));
            return;
        }

        AlertDialog waiting = spinner(activity, activity.getString(R.string.dev_repair_scanning));
        new Thread(() -> {
            List<File> broken = new ArrayList<>();
            Map<String, List<File>> healthy = new HashMap<>();
            int total = 0;
            for (File dir : dirs(activity)) {
                File[] files = dir.listFiles((d, name) -> StoragePlan.isOwnClip(name));
                if (files == null) {
                    continue;
                }
                for (File file : files) {
                    total++;
                    if (System.currentTimeMillis() - file.lastModified() < FRESH_MS) {
                        continue;
                    }
                    Mp4Repair.Scan scan = Mp4Repair.scan(file);
                    if (scan.status == Mp4Repair.Status.REPAIRABLE) {
                        broken.add(file);
                    } else if (scan.status == Mp4Repair.Status.HEALTHY) {
                        List<File> list = healthy.get(cameraKey(file.getName()));
                        if (list == null) {
                            list = new ArrayList<>();
                            healthy.put(cameraKey(file.getName()), list);
                        }
                        list.add(file);
                    }
                }
            }
            Collections.sort(broken, Comparator.comparing(File::getName));
            for (List<File> list : healthy.values()) {
                // 最近的那个当参考：参数和坏文件最接近
                Collections.sort(list, Comparator.comparing(File::getName).reversed());
            }
            final int scanned = total;
            AppLog.i(TAG, "扫描 " + scanned + " 个片段，没封口的 " + broken.size() + " 个");
            activity.runOnUiThread(() -> {
                dismiss(waiting);
                showFindings(activity, broken, healthy, scanned);
            });
        }, "mp4-repair-scan").start();
    }

    // ================================================================= 扫描结果

    private static void showFindings(Activity activity, List<File> broken,
                                     Map<String, List<File>> healthy, int scanned) {
        if (broken.isEmpty()) {
            CamDialogs.show(builder(activity)
                    .setTitle(R.string.dev_repair_none_title)
                    .setMessage(activity.getResources().getQuantityString(
                            R.plurals.dev_repair_none_msg, scanned, scanned)
                            + "\n\n" + activity.getString(R.string.dev_repair_none_note))
                    .setPositiveButton(R.string.action_got_it, null));
            return;
        }

        StringBuilder message = new StringBuilder();
        message.append(activity.getResources().getQuantityString(
                R.plurals.dev_repair_found, broken.size(), broken.size())).append("\n\n");
        long bytes = 0;
        for (File file : broken) {
            bytes += file.length();
            String line = file.getName() + "　" + size(file.length());
            if (!healthy.containsKey(cameraKey(file.getName()))) {
                line = activity.getString(R.string.dev_repair_no_reference, line);
            }
            message.append(line).append('\n');
        }
        message.append('\n').append(activity.getString(R.string.dev_repair_total, size(bytes))).append("\n\n")
                .append(activity.getString(R.string.dev_repair_explain));

        CamDialogs.show(builder(activity)
                .setTitle(R.string.dev_repair_mp4_title)
                .setMessage(message.toString())
                .setPositiveButton(R.string.dev_repair_start, (d, w) -> repairAll(activity, broken, healthy))
                .setNegativeButton(R.string.action_cancel, null));
    }

    // ================================================================= 修

    private static void repairAll(Activity activity, List<File> broken,
                                  Map<String, List<File>> healthy) {
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        TextView label = text(activity, "");
        LinearLayout box = column(activity);
        box.addView(label, wide());
        box.addView(bar, wide());

        AlertDialog dialog = CamDialogs.style(builder(activity)
                .setTitle(R.string.dev_repair_running)
                .setView(box)
                .setCancelable(false)
                .create());
        dialog.show();

        new Thread(() -> {
            Map<String, Mp4Repair.Template> templates = new HashMap<>();
            List<String> report = new ArrayList<>();
            int fixed = 0;
            for (int i = 0; i < broken.size(); i++) {
                File file = broken.get(i);
                final int index = i;
                activity.runOnUiThread(() -> {
                    label.setText(activity.getString(R.string.dev_tool_progress,
                            index + 1, broken.size(), file.getName()));
                    bar.setProgress(0);
                });
                Outcome outcome = repairOne(activity, file, healthy, templates, bar);
                report.add(activity.getString(R.string.dev_tool_file_result, file.getName(), outcome.text));
                if (outcome.fixed) {
                    fixed++;
                }
            }
            final int done = fixed;
            activity.runOnUiThread(() -> {
                dismiss(dialog);
                CamDialogs.show(builder(activity)
                        .setTitle(activity.getString(R.string.dev_repair_done_title, done, broken.size()))
                        .setMessage(join(report)
                                + (done > 0 ? "\n\n" + activity.getString(R.string.dev_repair_done_hint) : ""))
                        .setPositiveButton(R.string.action_got_it, null));
            });
        }, "mp4-repair").start();
    }

    /** 修一个的结果：写进报告的那句话，和算不算修好（计数看这个，不去比文字）。 */
    private static final class Outcome {
        final boolean fixed;
        final String text;

        Outcome(boolean fixed, String text) {
            this.fixed = fixed;
            this.text = text;
        }
    }

    /** 修一个。 */
    private static Outcome repairOne(Activity activity, File file,
                                     Map<String, List<File>> healthy,
                                     Map<String, Mp4Repair.Template> templates, ProgressBar bar) {
        String key = cameraKey(file.getName());
        Mp4Repair.Template template = templates.get(key);
        if (template == null) {
            List<File> references = healthy.get(key);
            if (references == null || references.isEmpty()) {
                return new Outcome(false, activity.getString(R.string.dev_repair_skip_no_reference));
            }
            IOException last = null;
            for (File reference : references) {
                try {
                    template = Mp4Repair.templateFrom(reference);
                    break;
                } catch (IOException e) {
                    last = e;
                }
            }
            if (template == null) {
                // 读不出的原因（Mp4Repair 抛的中文说明、系统的英文原话）只进日志，报告里只说跳过了
                AppLog.w(TAG, key + " 的参考片段都读不出解码参数，跳过 " + file.getName(), last);
                return new Outcome(false, activity.getString(R.string.dev_repair_skip_bad_reference));
            }
            AppLog.i(TAG, key + " 的参考片段：" + template.source.getName()
                    + "，" + template.width + "x" + template.height
                    + "，" + String.format(Locale.US, "%.1f", template.fps()) + " fps");
            templates.put(key, template);
        }

        Mp4Repair.Repaired repaired;
        try {
            repaired = Mp4Repair.repair(file, template, (done, total) -> {
                final int percent = total > 0 ? (int) (done * 100 / total) : 0;
                activity.runOnUiThread(() -> bar.setProgress(percent));
            });
        } catch (Exception e) {
            AppLog.e(TAG, "修复失败：" + file.getName(), e);
            return new Outcome(false, activity.getString(R.string.dev_repair_failed));
        }

        if (!playable(file)) {
            try {
                repaired.rollback();
                AppLog.w(TAG, "补出来的索引读不出来，已退回：" + file.getName());
                return new Outcome(false, activity.getString(R.string.dev_repair_rolled_back));
            } catch (IOException e) {
                AppLog.e(TAG, "退回失败：" + file.getName(), e);
                return new Outcome(false, activity.getString(R.string.dev_repair_rollback_failed));
            }
        }
        AppLog.i(TAG, "修好 " + file.getName() + "：" + repaired.samples + " 帧，"
                + repaired.durationMs + "ms，丢掉结尾 " + repaired.tailIgnored + " 字节");
        String done = activity.getResources().getQuantityString(R.plurals.dev_repair_ok,
                repaired.samples, repaired.samples,
                String.format(Locale.US, "%.1f", repaired.durationMs / 1000f));
        return new Outcome(true, repaired.tailIgnored > 0
                ? activity.getString(R.string.dev_repair_ok_tail, done) : done);
    }

    /**
     * 真读一遍，确认补出来的索引是对的。
     *
     * <p>只看 setDataSource 成不成不够 —— 索引结构对、但每帧的位置算错了，它一样能打开。
     * 所以还要真读出第一帧来。</p>
     */
    private static boolean playable(File file) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            if (extractor.getTrackCount() < 1) {
                return false;
            }
            MediaFormat format = extractor.getTrackFormat(0);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime == null || !mime.startsWith("video/")) {
                return false;
            }
            if (!format.containsKey(MediaFormat.KEY_DURATION)
                    || format.getLong(MediaFormat.KEY_DURATION) <= 0) {
                return false;
            }
            extractor.selectTrack(0);
            int capacity = format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)
                    ? Math.max(format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE), 1 << 20)
                    : 8 << 20;
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(capacity);
            return extractor.readSampleData(buffer, 0) > 0;
        } catch (Exception e) {
            AppLog.w(TAG, "验证失败：" + file.getName() + " " + e.getMessage());
            return false;
        } finally {
            extractor.release();
        }
    }

    // ================================================================= 零碎

    /** 录制目录和最终目录可能不是一个（开了中转写入时），两个都看。 */
    private static Set<File> dirs(Activity activity) {
        Set<File> dirs = new LinkedHashSet<>();
        File video = StorageHelper.getVideoDir(activity);
        if (video != null && video.isDirectory()) {
            dirs.add(video);
        }
        File recording = StorageHelper.getRecordingDir(activity);
        if (recording != null && recording.isDirectory()) {
            dirs.add(recording);
        }
        return dirs;
    }

    /** {@code 20250101_120000_front.mp4} → {@code front}。 */
    static String cameraKey(String name) {
        if (name == null) {
            return "";
        }
        int underscore = name.lastIndexOf('_');
        int dot = name.lastIndexOf('.');
        if (underscore < 0 || dot <= underscore) {
            return "";
        }
        // 归一到对外的槽位名：改名那一版前后的文件是同一路相机，
        // 不归一的话，新的坏文件会找不到旧的完好片段当参考
        return com.kooo.evcam.camera.CameraSlots.canonical(
                name.substring(underscore + 1, dot));
    }

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
