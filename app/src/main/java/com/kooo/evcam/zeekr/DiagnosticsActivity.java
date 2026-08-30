package com.kooo.evcam.zeekr;

import android.app.Activity;
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
 *   <li><b>保存到存储</b>——写成 .txt，U 盘拔下来就能拷走，最可靠；</li>
 *   <li><b>复制到剪贴板</b>——车机上没有文件管理器时的退路；</li>
 *   <li><b>分享</b>——有微信/邮件之类应用时直接发出去。</li>
 * </ul>
 */
public class DiagnosticsActivity extends Activity {

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
            saveButton.setOnClickListener(v -> saveReport());
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
            Toast.makeText(this,
                    "车辆权限要么已全部授予，要么本平台未定义，详见报告 4.5 节",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this,
                "正在申请 " + pending.length + " 项车辆权限，请留意车机是否弹出授权框",
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
                "车辆权限：授予 " + granted + " / " + grantResults.length + "，正在重新采集",
                Toast.LENGTH_LONG).show();
        runCollection();
    }

    /** 采集可能读 logcat 和文件系统，放后台线程。 */
    private void runCollection() {
        setButtonsEnabled(false);
        if (reportView != null) {
            reportView.setText("正在采集诊断信息...");
        }
        new Thread(() -> {
            String result;
            try {
                result = DiagnosticsCollector.collect(getApplicationContext());
            } catch (Throwable t) {
                AppLog.e(TAG, "采集诊断信息失败", t);
                result = "采集失败: " + t;
            }
            final String finalResult = result;
            mainHandler.post(() -> {
                report = finalResult;
                if (reportView != null) {
                    reportView.setText(finalResult);
                }
                setButtonsEnabled(true);
            });
        }).start();
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
    private File saveReport() {
        if (report == null || report.isEmpty()) {
            toast("诊断信息尚未生成");
            return null;
        }
        try {
            boolean useExternal = new com.kooo.evcam.AppConfig(this).isUsingExternalSdCard();
            File dir = StorageHelper.getLogDir(this, useExternal);
            if (dir == null) {
                dir = getExternalFilesDir(null);
            }
            if (dir != null && !dir.exists() && !dir.mkdirs()) {
                toast("无法创建目录: " + dir.getAbsolutePath());
                return null;
            }
            String name = "zeekr_diagnostics_"
                    + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                    + ".txt";
            File out = new File(dir, name);

            FileOutputStream fos = new FileOutputStream(out);
            OutputStreamWriter writer = new OutputStreamWriter(fos, Charset.forName("UTF-8"));
            try {
                writer.write(report);
                writer.flush();
            } finally {
                writer.close();
            }

            lastSavedFile = out;
            AppLog.i(TAG, "诊断报告已保存: " + out.getAbsolutePath());
            toast("已保存到:\n" + out.getAbsolutePath());
            return out;
        } catch (Exception e) {
            AppLog.e(TAG, "保存诊断报告失败", e);
            toast("保存失败: " + e.getMessage());
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
        reportView.setText("正在记录当前状态...");
        // getprop 要开一个进程、读上千行，别占着主线程 —— 车机上卡一下就能感觉到
        new Thread(() -> {
            Map<String, String> snapshot = VehicleSignalProbe.captureProperties();
            mainHandler.post(() -> {
                baselineSnapshot = snapshot;
                baselineAtMs = System.currentTimeMillis();
                reportView.setText("已记录 " + snapshot.size() + " 个属性。\n\n"
                        + "现在去做一个动作（挂倒挡 / 开关车门 / 打转向灯 / 踩刹车），"
                        + "做完回来按「② 对比变化」。");
                toast("快照已记录");
            });
        }, "snapshot-baseline").start();
    }

    /** 和基准快照对比，把变了的属性列出来。 */
    private void compareWithBaseline() {
        if (baselineSnapshot == null) {
            Toast.makeText(this, "请先按「① 拍快照」", Toast.LENGTH_SHORT).show();
            return;
        }
        reportView.setText("正在对比...");
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
        sb.append("# 状态变化对比\n\n");
        sb.append("基准时间：")
                .append(new SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        .format(new Date(baselineAtMs)))
                .append("　间隔 ")
                .append((System.currentTimeMillis() - baselineAtMs) / 1000)
                .append(" 秒\n");
        sb.append("属性总数：").append(now.size())
                .append("　变化 ").append(all.size())
                .append(" 项，滤除噪音后 ").append(signal.size()).append(" 项\n\n");

        if (signal.isEmpty()) {
            sb.append("## 没有发现变化\n\n");
            sb.append("这说明刚才那个动作**没有反映到任何系统属性上**。\n");
            sb.append("注意这不等于「车机读不到这个信号」——只说明它不走系统属性这条路。\n");
            sb.append("还可以试：ECARX binder（见主报告 4.3）、logcat（4.6）、广播。\n");
        } else {
            sb.append("## 变化的属性\n\n");
            for (SnapshotDiff.Change change : signal) {
                sb.append("- ").append(change.toString()).append('\n');
            }
            sb.append("\n**接下来**：把上面这些名字对着刚才做的动作看一遍。\n");
            sb.append("同一个动作重复做两次，两次都变的那一项才可靠 ——\n");
            sb.append("只变一次的可能只是碰巧同时发生的别的事。\n");
        }

        if (all.size() > signal.size()) {
            sb.append("\n<details>滤掉的噪音项（开机时长、内存计数之类）：")
                    .append(all.size() - signal.size()).append(" 项</details>\n");
        }

        return sb.toString();
    }

    private void copyReport() {
        if (report == null || report.isEmpty()) {
            toast("诊断信息尚未生成");
            return;
        }
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                toast("剪贴板不可用");
                return;
            }
            cm.setPrimaryClip(ClipData.newPlainText("Zeekr 诊断报告", report));
            toast("已复制到剪贴板");
        } catch (Exception e) {
            AppLog.e(TAG, "复制失败", e);
            toast("复制失败: " + e.getMessage());
        }
    }

    private void shareReport() {
        File file = lastSavedFile;
        if (file == null || !file.exists()) {
            file = saveReport();
        }
        if (file == null) {
            return;
        }
        try {
            android.net.Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.putExtra(Intent.EXTRA_SUBJECT, "极氪即刻 诊断报告");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "分享诊断报告"));
        } catch (Exception e) {
            // 车机上常常没有可分享的应用，退回提示文件路径
            AppLog.w(TAG, "分享失败", e);
            toast("无法分享，文件已保存在:\n" + file.getAbsolutePath());
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
