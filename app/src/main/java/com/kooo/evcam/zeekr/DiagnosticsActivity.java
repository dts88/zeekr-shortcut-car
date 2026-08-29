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
     * <p>只申请本机报告为 dangerous 级别的那些 —— 声明一个 dangerous 权限而不申请，
     * 它依然是拒绝状态；而 signature|privileged 的申请系统会直接忽略，弹都不弹。
     * 分清这两种情况正是这个按钮存在的意义：申请过之后再采一次报告，
     * 「未授予」才真正说明是被拒绝，而不是没问过。</p>
     */
    private void requestCarPermissions() {
        String[] pending = VehicleSignalProbe.runtimeGrantableCarPermissions(this);
        if (pending.length == 0) {
            Toast.makeText(this,
                    "本机没有可运行时申请的车辆权限（都不是 dangerous 级别），"
                            + "详见报告 4.5 节的保护级别",
                    Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "正在申请 " + pending.length + " 项车辆权限",
                Toast.LENGTH_SHORT).show();
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
