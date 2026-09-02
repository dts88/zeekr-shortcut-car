package com.kooo.evcam.share;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * 「发送到手机」的连通性测试界面（开发者选项）。
 *
 * <h3>为什么先做这个，而不是直接做分享按钮</h3>
 *
 * <p>整件事的前提是<b>两台设备在同一个局域网里直连</b> —— 车机开热点手机连上来，
 * 或者反过来。这个前提在这台车机上成不成立、成立时该用哪块网卡的地址，
 * 代码里看不出来，只能在车上试。</p>
 *
 * <p>所以先把「有哪些地址、能不能起服务、手机扫码打不打得开」这三件事
 * 单独摆出来。等这一屏确认可用，再把按钮接到回放界面上才有意义 ——
 * 否则出了问题分不清是网络、是服务、还是界面接错了。</p>
 */
public class ShareTestActivity extends Activity {

    private static final String TAG = "ShareTest";
    private static final int QR_SIZE_PX = 480;

    private TextView networkInfo;
    private TextView urlText;
    private ImageView qrImage;
    private FileShareServer server;
    private LocalNetwork.Endpoint chosen;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        refreshNetwork();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopServer();
    }

    // ------------------------------------------------------------------ 界面

    private View buildLayout() {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF1A1A1A);

        root.addView(title("发送到手机 · 连通性测试"));
        root.addView(hint("前提：手机与车机之间已经建立直连 —— "
                + "车机开热点手机连上来，或手机开热点车机连上去。"));

        networkInfo = body("");
        root.addView(networkInfo);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("刷新网络", v -> refreshNetwork()));
        buttons.addView(button("换一个地址", v -> cycleEndpoint()));
        root.addView(buttons);

        LinearLayout shareButtons = new LinearLayout(this);
        shareButtons.setOrientation(LinearLayout.HORIZONTAL);
        shareButtons.addView(button("分享最新照片", v -> shareNewest(
                StorageHelper.getPhotoDir(this), "照片")));
        shareButtons.addView(button("分享最新录像", v -> shareNewest(
                StorageHelper.getFinalVideoDir(this), "录像")));
        shareButtons.addView(button("停止", v -> {
            stopServer();
            Toast.makeText(this, "已停止", Toast.LENGTH_SHORT).show();
        }));
        root.addView(shareButtons);

        urlText = body("");
        root.addView(urlText);

        qrImage = new ImageView(this);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(
                QR_SIZE_PX, QR_SIZE_PX);
        qrParams.topMargin = dp(12);
        qrParams.gravity = Gravity.CENTER_HORIZONTAL;
        qrImage.setLayoutParams(qrParams);
        root.addView(qrImage);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        view.setTextColor(Color.WHITE);
        view.setPadding(0, 0, 0, dp(8));
        return view;
    }

    private TextView hint(String text) {
        TextView view = body(text);
        view.setTextColor(0xFF999999);
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        view.setTextColor(0xFFDDDDDD);
        view.setPadding(0, dp(6), 0, dp(6));
        return view;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        view.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.rightMargin = dp(8);
        view.setLayoutParams(params);
        return view;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------------ 网络

    private List<LocalNetwork.Endpoint> endpoints;

    private void refreshNetwork() {
        endpoints = LocalNetwork.enumerate();
        chosen = LocalNetwork.preferred(endpoints);

        StringBuilder sb = new StringBuilder();
        if (endpoints.isEmpty()) {
            sb.append("没有找到任何可用的 IPv4 地址。\n")
                    .append("先确认热点已经建立、两台设备确实连上了。");
        } else {
            sb.append("检测到 ").append(endpoints.size()).append(" 个地址");
            sb.append("（排在前面的更可能是那条直连）：\n");
            for (LocalNetwork.Endpoint endpoint : endpoints) {
                sb.append(endpoint == chosen ? "  ▸ " : "    ").append(endpoint).append('\n');
            }
        }
        networkInfo.setText(sb.toString());
        AppLog.i(TAG, "可用地址: " + endpoints);
        // 地址换了，之前那张二维码就不作数了
        showQr(null, null);
    }

    /** 挑错了地址是常事 —— 让人能一个一个试过去。 */
    private void cycleEndpoint() {
        if (endpoints == null || endpoints.isEmpty()) {
            Toast.makeText(this, "还没有可用地址", Toast.LENGTH_SHORT).show();
            return;
        }
        int index = endpoints.indexOf(chosen);
        chosen = endpoints.get((index + 1) % endpoints.size());
        Toast.makeText(this, "改用 " + chosen, Toast.LENGTH_SHORT).show();
        refreshHighlight();
        if (server != null && server.sharedFile() != null) {
            publish();
        }
    }

    private void refreshHighlight() {
        if (endpoints == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("检测到 ").append(endpoints.size()).append(" 个地址")
                .append("（排在前面的更可能是那条直连）：\n");
        for (LocalNetwork.Endpoint endpoint : endpoints) {
            sb.append(endpoint == chosen ? "  ▸ " : "    ").append(endpoint).append('\n');
        }
        networkInfo.setText(sb.toString());
    }

    // ------------------------------------------------------------------ 分享

    private void shareNewest(File dir, String what) {
        File newest = newestFile(dir);
        if (newest == null) {
            Toast.makeText(this, "没有找到" + what + "（目录：" + dir + "）",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (chosen == null) {
            Toast.makeText(this, "还没有可用地址，先建立热点连接", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            if (server == null) {
                server = new FileShareServer(this);
                server.start();
                AppLog.i(TAG, "分享服务已启动，端口 " + server.getListeningPort());
            }
            server.share(newest);
            publish();
        } catch (Exception e) {
            AppLog.e(TAG, "启动分享服务失败", e);
            Toast.makeText(this, "启动失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void publish() {
        String url = server.urlFor(chosen.address);
        File shared = server.sharedFile();
        showQr(url, shared);
    }

    private void showQr(String url, File shared) {
        if (url == null) {
            urlText.setText("");
            qrImage.setImageBitmap(null);
            return;
        }
        urlText.setText(String.format(Locale.US, "%s\n%s  ·  %s",
                url, shared.getName(), FileShareServer.readableSize(shared.length())));
        Bitmap qr = QrCode.encode(url, QR_SIZE_PX);
        qrImage.setImageBitmap(qr);
        if (qr == null) {
            Toast.makeText(this, "二维码生成失败", Toast.LENGTH_SHORT).show();
        }
    }

    private void stopServer() {
        if (server != null) {
            try {
                server.stop();
            } catch (Exception e) {
                AppLog.w(TAG, "停止分享服务时出错: " + e);
            }
            server = null;
        }
        if (urlText != null) {
            showQr(null, null);
        }
    }

    /** 目录里改动时间最新的那个文件；没有就返回 null。 */
    private static File newestFile(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return null;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        File newest = null;
        for (File file : files) {
            if (!file.isFile() || file.length() <= 0) {
                continue;
            }
            if (newest == null || file.lastModified() > newest.lastModified()) {
                newest = file;
            }
        }
        return newest;
    }
}
