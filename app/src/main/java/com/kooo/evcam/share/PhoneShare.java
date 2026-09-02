package com.kooo.evcam.share;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

import java.io.File;
import java.util.List;

/**
 * 「发送到手机」：起一个临时的局域网服务，把二维码摆出来。
 *
 * <h3>为什么是扫码而不是配套 App</h3>
 *
 * <p>手机上不用装任何东西：扫码 → 浏览器打开 → 长按保存。
 * 代价是保存这一步要用户自己做，换来的是零安装。</p>
 *
 * <h3>服务活多久</h3>
 *
 * <p>只在这个对话框开着的时候。关掉就停 —— 一个能被局域网访问的文件服务，
 * 没有理由在用户已经不看它的时候继续开着。所以对话框里写明了
 * 「保存完成前请不要关闭」。</p>
 */
public final class PhoneShare {

    private static final String TAG = "PhoneShare";
    private static final int QR_SIZE_PX = 420;

    private PhoneShare() {
    }

    /**
     * 弹出发送对话框。
     *
     * @param file 要发送的那一个文件；为 null 或不存在时只提示，不弹窗
     */
    public static void show(Activity activity, File file) {
        show(activity, file, null);
    }

    /**
     * @param extraNote 额外的一句说明，显示在操作步骤下面；不需要时传 null。
     *                  视频用它讲清楚「只发当前这一段」。
     */
    public static void show(Activity activity, File file, String extraNote) {
        if (activity == null || activity.isFinishing()) {
            return;
        }
        if (file == null || !file.isFile() || file.length() <= 0) {
            toast(activity, activity.getString(R.string.share_phone_no_file));
            return;
        }

        List<LocalNetwork.Endpoint> endpoints = LocalNetwork.enumerate();
        if (endpoints.isEmpty()) {
            // 没有可用地址就不是「失败」，是前提没满足 —— 说清楚该做什么
            new AlertDialog.Builder(activity, R.style.AlertDialogTheme)
                    .setTitle(R.string.share_phone_need_hotspot_title)
                    .setMessage(activity.getString(R.string.share_phone_need_hotspot_msg)
                            + "\n\n" + activity.getString(R.string.share_phone_network_hint))
                    .setPositiveButton(R.string.action_got_it, null)
                    .show();
            return;
        }

        FileShareServer server = new FileShareServer(activity);
        try {
            server.start();
            server.share(file);
        } catch (Exception e) {
            AppLog.e(TAG, "起不来分享服务", e);
            toast(activity, activity.getString(R.string.share_phone_failed, e.getMessage()));
            return;
        }
        AppLog.i(TAG, "分享 " + file.getName() + "，端口 " + server.getListeningPort());
        new Presenter(activity, server, endpoints, file, extraNote).show();
    }

    private static void toast(Activity activity, String text) {
        Toast.makeText(activity, text, Toast.LENGTH_LONG).show();
    }

    /** 对话框本身。拆出来是因为「换一个地址」要改二维码，得留着几个引用。 */
    private static final class Presenter {
        private final Activity activity;
        private final FileShareServer server;
        private final List<LocalNetwork.Endpoint> endpoints;
        private final File file;
        private final String extraNote;

        private int index;
        private ImageView qrImage;
        private TextView addressText;

        Presenter(Activity activity, FileShareServer server,
                  List<LocalNetwork.Endpoint> endpoints, File file, String extraNote) {
            this.activity = activity;
            this.server = server;
            this.endpoints = endpoints;
            this.file = file;
            this.extraNote = extraNote;
        }

        void show() {
            int pad = dp(20);
            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(pad, pad, pad, pad);
            root.setGravity(Gravity.CENTER_HORIZONTAL);

            qrImage = new ImageView(activity);
            LinearLayout.LayoutParams qrParams =
                    new LinearLayout.LayoutParams(QR_SIZE_PX, QR_SIZE_PX);
            qrParams.gravity = Gravity.CENTER_HORIZONTAL;
            qrImage.setLayoutParams(qrParams);
            root.addView(qrImage);

            root.addView(text(activity.getString(R.string.share_phone_steps), 15, false));
            if (extraNote != null && !extraNote.isEmpty()) {
                root.addView(text(extraNote, 14, false));
            }

            addressText = text("", 13, true);
            root.addView(addressText);

            root.addView(text(activity.getString(R.string.share_phone_network_hint), 13, true));
            root.addView(text(activity.getString(R.string.share_phone_keep_open), 13, true));

            AlertDialog.Builder builder = new AlertDialog.Builder(
                    activity, R.style.AlertDialogTheme)
                    .setTitle(R.string.share_phone_title)
                    .setView(root)
                    .setPositiveButton(R.string.action_close, null)
                    .setOnDismissListener(dialog -> stop());

            if (endpoints.size() > 1) {
                // 两块网卡同时有地址是常事，默认那个未必是手机连得上的那个。
                // 与其让人对着一个扫不通的码发呆，不如给一个「换一个」。
                builder.setNeutralButton(R.string.share_phone_switch_address, null);
            }

            AlertDialog dialog = builder.create();
            dialog.setOnShowListener(d -> {
                if (endpoints.size() > 1) {
                    // 直接拿按钮改点击：默认行为会关掉对话框，而换地址不该关
                    dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                        index = (index + 1) % endpoints.size();
                        render();
                    });
                }
            });
            dialog.show();
            render();
        }

        private void render() {
            LocalNetwork.Endpoint endpoint = endpoints.get(index);
            String url = server.urlFor(endpoint.address);
            Bitmap qr = QrCode.encode(url, QR_SIZE_PX);
            qrImage.setImageBitmap(qr);
            addressText.setText(activity.getString(R.string.share_phone_address,
                    url == null ? "" : url, file.getName(),
                    FileShareServer.readableSize(file.length())));
            AppLog.d(TAG, "二维码地址: " + url + "（网卡 " + endpoint.interfaceName + "）");
        }

        private void stop() {
            try {
                server.stop();
                AppLog.d(TAG, "分享服务已停止");
            } catch (Exception e) {
                AppLog.w(TAG, "停止分享服务时出错: " + e);
            }
        }

        private TextView text(String content, int sizeSp, boolean secondary) {
            TextView view = new TextView(activity);
            view.setText(content);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
            view.setGravity(Gravity.CENTER_HORIZONTAL);
            view.setPadding(0, dp(10), 0, 0);
            if (secondary) {
                view.setAlpha(0.7f);
            }
            view.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            return view;
        }

        private int dp(int value) {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    activity.getResources().getDisplayMetrics());
        }
    }
}
