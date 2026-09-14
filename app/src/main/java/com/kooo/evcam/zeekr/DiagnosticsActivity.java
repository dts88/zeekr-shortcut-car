package com.kooo.evcam.zeekr;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.List;
import java.util.Locale;

/**
 * 诊断页：一次性列出相机、屏幕、车辆信号来源、存储与最近日志，并支持导出。
 *
 * <p>导出提供三种方式，因为车机上能用哪种不一定：</p>
 * <ul>
 *   <li><b>保存到存储</b>——写成 .json，U 盘拔下来就能拷走，最可靠；</li>
 *   <li><b>复制到剪贴板</b>——车机上没有文件管理器时的退路；</li>
 *   <li><b>分享</b>——有微信/邮件之类应用时直接发出去。</li>
 * </ul>
 *
 * <p>导出的是 JSON 而不是纯文本：屏幕上那份为了能翻，每块有条数上限、长值会截断，
 * 而这些上限对事后分析是有害的 —— <b>被截掉的那部分恰恰可能是要找的东西</b>。
 * JSON 那份不设上限、不截断，人看的完整文本也一并放在 {@code text_report} 字段里，
 * 一个文件两用。</p>
 */
import androidx.appcompat.app.AppCompatActivity;

public class DiagnosticsActivity extends AppCompatActivity {

    private static final String TAG = "DiagnosticsActivity";

    private TextView reportView;
    private Button saveButton;
    private Button copyButton;
    private Button shareButton;
    private Button refreshButton;
    private Button requestCarPermsButton;
    private Button snapshotButton;
    private Button compareButton;

    /** 「拍快照」存下的那一份，等着和之后的状态对比。 */
    private Map<String, String> baselineSnapshot;
    private long baselineAtMs;
    private static final int REQUEST_CAR_PERMISSIONS = 4101;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile String report = "";
    private File lastSavedFile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);

        reportView = findViewById(R.id.diagnostics_report);
        saveButton = findViewById(R.id.diagnostics_save);
        copyButton = findViewById(R.id.diagnostics_copy);
        shareButton = findViewById(R.id.diagnostics_share);
        refreshButton = findViewById(R.id.diagnostics_refresh);
        requestCarPermsButton = findViewById(R.id.diagnostics_request_car_perms);

        snapshotButton = findViewById(R.id.diagnostics_snapshot);
        compareButton = findViewById(R.id.diagnostics_compare);
        if (snapshotButton != null) {
            snapshotButton.setOnClickListener(v -> takeBaselineSnapshot());
        }
        if (compareButton != null) {
            compareButton.setOnClickListener(v -> compareWithBaseline());
        }

        View close = findViewById(R.id.diagnostics_close);
        if (close != null) {
            close.setOnClickListener(v -> finish());
        }
        if (refreshButton != null) {
            refreshButton.setOnClickListener(v -> runCollection());
        }
        if (saveButton != null) {
            saveButton.setOnClickListener(v -> saveInBackground(null));
        }
        if (copyButton != null) {
            copyButton.setOnClickListener(v -> copyReport());
        }
        if (shareButton != null) {
            shareButton.setOnClickListener(v -> shareReport());
        }
        if (requestCarPermsButton != null) {
            requestCarPermsButton.setOnClickListener(v -> requestCarPermissions());
        }

        runCollection();
    }

    /**
     * 申请车辆权限。
     *
     * <p>申请<b>全部</b>尚未授予的车辆权限，而不是只挑 dangerous 的 ——
     * 「车机会不会弹框」本身就是要在车上观察的实验，替系统先筛掉一部分就把实验做没了。
     * dangerous 的会弹授权框；signature/privileged 的系统会当场静默拒绝、弹都不弹。
     * 两种结果用户都能直接看到，再采一次报告对照即可：申请过之后仍是「未授予」，
     * 才真正说明是被系统挡住，而不是没问过。</p>
     */
    private void requestCarPermissions() {
        String[] pending = VehicleSignalProbe.ungrantedCarPermissions(this);
        if (pending.length == 0) {
            Toast.makeText(this, R.string.diag_perms_none, Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this,
                getString(R.string.diag_perms_requesting, pending.length),
                Toast.LENGTH_LONG).show();
        requestPermissions(pending, REQUEST_CAR_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_CAR_PERMISSIONS) {
            return;
        }
        int granted = 0;
        for (int result : grantResults) {
            if (result == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                granted++;
            }
        }
        Toast.makeText(this,
                getString(R.string.diag_perms_result, granted, grantResults.length),
                Toast.LENGTH_LONG).show();
        runCollection();
    }

    /** 采集可能读 logcat 和文件系统，放后台线程。 */
    private void runCollection() {
        setButtonsEnabled(false);
        if (reportView != null) {
            reportView.setText(R.string.diag_collecting);
        }
        final FrameGapWatch gaps = new FrameGapWatch();
        gaps.start();
        new Thread(() -> {
            String result;
            try {
                result = DiagnosticsCollector.collect(getApplicationContext());
            } catch (Throwable t) {
                AppLog.e(TAG, "采集诊断信息失败", t);
                result = getString(R.string.diag_collect_failed, String.valueOf(t));
            }
            final String finalResult = result;
            mainHandler.post(() -> {
                report = finalResult;
                if (reportView != null) {
                    long setStart = android.os.SystemClock.uptimeMillis();
                    reportView.setText(finalResult);
                    AppLog.i(TAG, "report text set in "
                            + (android.os.SystemClock.uptimeMillis() - setStart)
                            + "ms (" + finalResult.length() + " chars)");
                }
                setButtonsEnabled(true);
                // 贴上去之后还要排版、画第一帧，再多看两秒
                gaps.stopAfter(2000L);
            });
        }).start();
    }

    /**
     * 生成报告时「卡一下」卡在哪：记下这段时间里界面两帧之间最长隔了多久。
     *
     * <p>从开始采集看到结果贴上去之后两秒，然后自己停，把结果写进日志。
     * 主线程被堵住时下一帧就来得晚，隔多久就是卡了多久。</p>
     */
    private final class FrameGapWatch implements android.view.Choreographer.FrameCallback {
        private final long startMs = android.os.SystemClock.uptimeMillis();
        private long stopAtMs = Long.MAX_VALUE;
        private long lastFrameNanos;
        private long longestGapMs;
        private int gapsOver100Ms;

        void start() {
            android.view.Choreographer.getInstance().postFrameCallback(this);
        }

        void stopAfter(long delayMs) {
            stopAtMs = android.os.SystemClock.uptimeMillis() + delayMs;
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            if (lastFrameNanos != 0L) {
                long gapMs = (frameTimeNanos - lastFrameNanos) / 1_000_000L;
                longestGapMs = Math.max(longestGapMs, gapMs);
                if (gapMs > 100L) {
                    gapsOver100Ms++;
                }
            }
            lastFrameNanos = frameTimeNanos;
            if (android.os.SystemClock.uptimeMillis() < stopAtMs && !isDestroyed()) {
                android.view.Choreographer.getInstance().postFrameCallback(this);
            } else {
                AppLog.i(TAG, "UI while generating the report: longest gap between frames "
                        + longestGapMs + "ms, " + gapsOver100Ms + " gaps over 100ms, watched "
                        + (android.os.SystemClock.uptimeMillis() - startMs) + "ms");
            }
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        if (saveButton != null) {
            saveButton.setEnabled(enabled);
        }
        if (copyButton != null) {
            copyButton.setEnabled(enabled);
        }
        if (shareButton != null) {
            shareButton.setEnabled(enabled);
        }
        if (requestCarPermsButton != null) {
            requestCarPermsButton.setEnabled(enabled);
        }
        if (refreshButton != null) {
            refreshButton.setEnabled(enabled);
        }
    }

    /**
     * 写到日志目录（跟随当前存储位置设置，通常就是 U 盘），方便直接拷走。
     */
    /**
     * 写出诊断报告。
     *
     * <p>组装 JSON 要重新读一遍系统属性和三张 Settings 表，比写文件本身慢得多，
     * 所以调用方必须在后台线程上调它 —— 主线程卡住在车机上立刻能感觉到。</p>
     */
    private File saveReport() {
        if (report == null || report.isEmpty()) {
            toast(getString(R.string.diag_not_ready));
            return null;
        }
        try {
            boolean useExternal = new com.kooo.evcam.AppConfig(this).isUsingExternalSdCard();
            File dir = StorageHelper.getLogDir(this, useExternal);
            if (dir == null) {
                dir = getExternalFilesDir(null);
            }
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                toast(getString(R.string.diag_mkdir_failed, dir.getAbsolutePath()));
                return null;
            }
            String name = "zeekr_diagnostics_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                    + ".json";
            File out = new File(dir, name);

            String json = DiagnosticsJson.build(this, report);

            FileOutputStream fos = new FileOutputStream(out);
            OutputStreamWriter writer = new OutputStreamWriter(fos, Charset.forName("UTF-8"));
            try {
                writer.write(json);
                writer.flush();
            } finally {
                writer.close();
            }

            lastSavedFile = out;
            AppLog.i(TAG, "诊断报告已保存: " + out.getAbsolutePath()
                    + "（" + out.length() / 1024 + " KB）");
            toast(getString(R.string.diag_saved, out.getAbsolutePath()));
            return out;
        } catch (Exception e) {
            AppLog.e(TAG, "保存诊断报告失败", e);
            toast(getString(R.string.diag_save_failed, String.valueOf(e.getMessage())));
            return null;
        }
    }

    /**
     * 拍下当前状态，作为对比的基准。
     *
     * <p>用法：在车里先按「拍快照」，然后做一个动作（挂倒挡、开门、打转向灯），
     * 再按「对比变化」—— 变了的属性就是这个动作的候选信号源。</p>
     *
     * <p>这个流程存在的理由是：**不必事先知道属性叫什么**。
     * 之前找车辆信号一直卡在猜名字上，而猜不中并不能证明信号不存在。</p>
     */
    private void takeBaselineSnapshot() {
        reportView.setText(R.string.diag_snapshotting);
        // getprop 要开一个进程、读上千行，别占着主线程 —— 车机上卡一下就能感觉到
        new Thread(() -> {
            Map<String, String> snapshot = VehicleSignalProbe.captureProperties();
            mainHandler.post(() -> {
                baselineSnapshot = snapshot;
                baselineAtMs = System.currentTimeMillis();
                reportView.setText(getString(R.string.diag_snapshot_done, snapshot.size()));
                toast(getString(R.string.diag_snapshot_toast));
            });
        }, "snapshot-baseline").start();
    }

    /** 和基准快照对比，把变了的属性列出来。 */
    private void compareWithBaseline() {
        if (baselineSnapshot == null) {
            Toast.makeText(this, R.string.diag_snapshot_first, Toast.LENGTH_SHORT).show();
            return;
        }
        reportView.setText(R.string.diag_comparing);
        new Thread(() -> {
            Map<String, String> now = VehicleSignalProbe.captureProperties();
            String result = describeChanges(now);
            mainHandler.post(() -> {
                reportView.setText(result);
                report = result;
            });
        }, "snapshot-compare").start();
    }

    /** 把对比结果写成报告文本。 */
    private String describeChanges(Map<String, String> now) {
        List<SnapshotDiff.Change> all = SnapshotDiff.between(baselineSnapshot, now);
        List<SnapshotDiff.Change> signal = SnapshotDiff.signalOnly(all);

        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.diag_cmp_title)).append("\n\n");
        sb.append(getString(R.string.diag_cmp_baseline,
                new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date(baselineAtMs)),
                (System.currentTimeMillis() - baselineAtMs) / 1000)).append('\n');
        sb.append(getString(R.string.diag_cmp_counts, now.size(), all.size(), signal.size()))
                .append("\n\n");

        if (signal.isEmpty()) {
            sb.append(getString(R.string.diag_cmp_none)).append('\n');
        } else {
            sb.append(getString(R.string.diag_cmp_changed_title)).append("\n\n");
            for (SnapshotDiff.Change change : signal) {
                sb.append("- ").append(change.toString()).append('\n');
            }
            sb.append('\n').append(getString(R.string.diag_cmp_next)).append('\n');
        }

        if (all.size() > signal.size()) {
            sb.append('\n').append(getString(R.string.diag_cmp_noise, all.size() - signal.size()))
                    .append('\n');
        }

        return sb.toString();
    }

    private void copyReport() {
        if (report == null || report.isEmpty()) {
            toast(getString(R.string.diag_not_ready));
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                toast(getString(R.string.diag_clipboard_unavailable));
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.diag_clip_label), report));
            toast(getString(R.string.diag_copied));
        } catch (Exception e) {
            AppLog.e(TAG, "复制失败", e);
            toast(getString(R.string.diag_copy_failed, String.valueOf(e.getMessage())));
        }
    }

    /**
     * 保存到后台线程上去做，完成后回到主线程。
     *
     * @param then 保存完要做的事（例如接着分享）；不需要就传 null
     */
    private void saveInBackground(java.util.function.Consumer<File> then) {
        toast(getString(R.string.diag_exporting));
        new Thread(() -> {
            File out = saveReport();
            mainHandler.post(() -> {
                if (then != null && out != null) {
                    then.accept(out);
                }
            });
        }, "diagnostics-save").start();
    }

    private void shareReport() {
        File file = lastSavedFile;
        if (file == null || !file.exists()) {
            // 还没存过，先存再分享 —— 存要读一遍系统属性，不能占着主线程
            saveInBackground(this::shareFile);
            return;
        }
        shareFile(file);
    }

    private void shareFile(File file) {
        if (file == null) {
            return;
        }
        try {
            android.net.Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT,
                    getString(R.string.diag_share_subject, getString(R.string.app_name)));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, getString(R.string.diag_share_chooser)));
        } catch (Exception e) {
            // 车机上常常没有可分享的应用，退回提示文件路径
            AppLog.w(TAG, "分享失败", e);
            toast(getString(R.string.diag_share_failed, file.getAbsolutePath()));
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
