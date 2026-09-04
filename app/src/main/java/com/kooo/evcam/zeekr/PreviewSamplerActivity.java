package com.kooo.evcam.zeekr;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.PreviewSampler;
import com.kooo.evcam.camera.SingleCamera;

import java.util.Locale;
import java.util.Map;

/**
 * 预览资源占用实测。
 *
 * <h3>要回答的问题</h3>
 *
 * <p>切到应用内别的界面、或者退到车机桌面之后，预览这一路的资源是不是还占着？</p>
 *
 * <h3>怎么用</h3>
 *
 * <ol>
 *   <li>点「开始采样」，然后<b>随便切界面</b>：回主界面看一会预览，进设置待一会，
 *       按 Home 回车机桌面待一会，再回来；</li>
 *   <li>回到这个界面点「停止并出结果」。</li>
 * </ol>
 *
 * <p>采样跑在自己的线程上，不受界面切换影响。每一秒记一次各路出了多少帧、
 * 相机开着几路、当时人在哪个界面，最后按界面分段汇总 —— 不用掐表，
 * 界限是它自己标出来的。</p>
 *
 * <p>注意这个界面本身也算「应用内其他界面」，所以结果里必然有它的一段。</p>
 */
public class PreviewSamplerActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Button toggle;
    private TextView status;
    private TextView results;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        refresh();
    }

    private View buildLayout() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF1A1A1A);

        // 车机没有系统返回键
        root.addView(button("← 返回", v -> finish()));
        root.addView(title("预览资源占用实测"));
        root.addView(hint("点「开始采样」，然后随便切界面：主界面看一会、进设置待一会、"
                + "按 Home 回车机桌面待一会，再回来点「停止并出结果」。\n"
                + "每秒记一次各路出帧数和相机开着几路，按界面自动分段 —— 不用掐表。"));

        toggle = button("开始采样", v -> onToggle());
        root.addView(toggle);

        status = hint("");
        root.addView(status);

        results = new TextView(this);
        results.setTextColor(0xFFDDDDDD);
        results.setTextSize(13f);
        results.setTypeface(android.graphics.Typeface.MONOSPACE);
        results.setPadding(0, dp(8), 0, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private void onToggle() {
        if (PreviewSampler.isRunning()) {
            PreviewSampler.stop();
            results.setText(PreviewSampler.summarise() + "\n" + interpretation());
        } else {
            PreviewSampler.start(new PreviewSampler.StateProbe() {
                @Override
                public int camerasOpen() {
                    MultiCameraManager manager =
                            CameraManagerHolder.getInstance().getCameraManager();
                    if (manager == null) {
                        return 0;
                    }
                    int open = 0;
                    for (String key : new String[]{"front", "back", "left", "right"}) {
                        SingleCamera camera = manager.getCamera(key);
                        if (camera != null && camera.isCameraOpened()) {
                            open++;
                        }
                    }
                    return open;
                }

                @Override
                public boolean recording() {
                    MultiCameraManager manager =
                            CameraManagerHolder.getInstance().getCameraManager();
                    return manager != null && manager.isRecording();
                }
            });
            results.setText("采样中……现在去切界面，回来再点「停止并出结果」。");
        }
        refresh();
    }

    /** 帮着把结果读懂：三种情况分别意味着什么。 */
    private String interpretation() {
        return "怎么读这张表：\n"
                + "  · 「应用内其他界面」那几段仍有 ~30fps，说明切界面不释放预览流；\n"
                + "  · 「退到后台」那段相机开着 0 路、一帧都没出，说明退到桌面会真正释放；\n"
                + "  · 「退到后台」那段仍在出帧，看同一行的「录制中」——\n"
                + "    录着制时相机是有意保持的，预览流也跟着留着。";
    }

    private void refresh() {
        boolean running = PreviewSampler.isRunning();
        toggle.setText(running ? "停止并出结果" : "开始采样");
        status.setText(running
                ? "采样中，已采 " + PreviewSampler.samples().size() + " 秒"
                : "未在采样");
        if (running) {
            ui.postDelayed(this::refresh, 1000L);
        }
    }

    // ------------------------------------------------------------------ 小工具

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(20f);
        view.setPadding(0, dp(8), 0, dp(4));
        return view;
    }

    private TextView hint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xFFAAAAAA);
        view.setTextSize(13f);
        view.setPadding(0, dp(4), 0, dp(8));
        return view;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        view.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        view.setLayoutParams(params);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }
}
