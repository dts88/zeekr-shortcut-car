package com.kooo.evcam.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.ImageFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.camera.PreviewFrameRates;
import com.kooo.evcam.zeekr.StreamLayoutTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 配置编辑：按「相机 → 流」摆开，每条流显示自己的参数和拆分状态。
 *
 * <h3>为什么按这个层次摆</h3>
 *
 * <p>以前的设置界面是一堆平铺的全局开关，看不出哪一项管到哪一路。参数本来就长在
 * 「某一路的某条流」上（Camera2 的实际结构就是一个会话 + 若干条输出流），
 * 照着这个层次摆，作用域自己就说清楚了。</p>
 *
 * <h3>拆分状态显示在流上，不是相机上</h3>
 *
 * <p>两条流可以有不同分辨率，于是同一路相机上「录制拆四格、预览不拆」是可能的。
 * 把拆分状态挂在相机上会说谎，所以它跟着每条流走。</p>
 *
 * <h3>保存前要过两道</h3>
 *
 * <ol>
 *   <li><b>数据检查</b>（{@link ProfileValidation}）：一路都没启用、选了没声明的尺寸，
 *       这些不用开相机就知道错。</li>
 *   <li><b>实测</b>：真开一次会话、采几秒帧率。会话配得起来不等于跑得动 ——
 *       三路各开三条流是系统级问题，没有任何声明能回答。</li>
 * </ol>
 *
 * <p>实测结果连同倒计时一起摆出来：十秒内不确认就回滚。配错了最坏的情况是没有画面，
 * 那时候人是点不动屏幕的，所以不能指望用户来点「取消」。</p>
 */
public class ProfileEditorActivity extends Activity {

    private static final String TAG = "ProfileEditor";

    /** 确认倒计时。够看清帧率，又不至于让人干等。 */
    private static final int CONFIRM_SECONDS = 10;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private ProfileStore store;
    private Profile profile;
    private CameraManager cameraManager;

    private LinearLayout content;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new ProfileStore(this);
        profile = store.current();
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        setContentView(buildLayout());
        render();
    }

    // ------------------------------------------------------------------ 界面

    private View buildLayout() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF1A1A1A);

        root.addView(button("← 返回", v -> finish()));
        root.addView(title("配置编辑"));
        status = hint("");
        root.addView(status);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(button("校验并保存", v -> validateAndSave()));
        root.addView(button("恢复为初值", v -> confirmReset()));
        return root;
    }

    private void render() {
        content.removeAllViews();
        status.setText("配置 " + profile.id
                + (profile.name.isEmpty() ? "" : "（" + profile.name + "）"));

        for (CameraProfile camera : profile.cameras) {
            content.addView(cameraHeader(camera));
            content.addView(streamRow(camera, "预览", camera.preview, true));
            content.addView(streamRow(camera, "录制", camera.record, true));
            content.addView(streamRow(camera, "拍照", camera.photo, false));
        }
    }

    private View cameraHeader(CameraProfile camera) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(18), 0, dp(4));

        TextView name = new TextView(this);
        name.setText(roleName(camera.role) + "   " + cameraSummary(camera.role));
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(16f);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(name, params);

        Button toggle = new Button(this);
        toggle.setText(camera.enabled ? "已启用" : "未启用");
        toggle.setAllCaps(false);
        toggle.setOnClickListener(v -> {
            camera.enabled = !camera.enabled;
            render();
        });
        row.addView(toggle);
        return row;
    }

    /** 一条流一行：参数 + 拆分状态。 */
    private View streamRow(CameraProfile camera, String label, StreamSpec spec,
                           boolean showsSplit) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(12), dp(6), 0, dp(6));

        TextView text = new TextView(this);
        StringBuilder sb = new StringBuilder();
        sb.append(label).append("   分辨率 ").append(spec.resolution);
        if (spec == camera.record) {
            sb.append("   帧率 ").append(spec.fps)
                    .append("   码率 ").append(spec.bitrate)
                    .append("   分段 ").append(spec.segmentMinutes).append(" 分钟");
        }
        if (spec == camera.photo) {
            sb.append("   质量 ").append(spec.jpegQuality);
        }
        if (showsSplit) {
            sb.append('\n').append("        ").append(splitStatus(camera.role, spec));
        }
        text.setText(sb.toString());
        text.setTextColor(0xFFCCCCCC);
        text.setTextSize(14f);
        row.addView(text);

        Button edit = new Button(this);
        edit.setText("改分辨率");
        edit.setAllCaps(false);
        edit.setOnClickListener(v -> pickResolution(camera, spec));
        row.addView(edit);
        return row;
    }

    /**
     * 这条流会不会被拆。
     *
     * <p>拆不拆只取决于「哪一路相机 + 什么分辨率」，规则在 {@link StreamLayoutTable}。
     * 配置改不了它 —— 那是设备事实，不是选择。</p>
     */
    private String splitStatus(String role, StreamSpec spec) {
        int[] size = ProfileResolution.parse(spec.resolution);
        if (size == null) {
            return "拆分状态：要等实际尺寸定下来才知道（现在是 " + spec.resolution + "）";
        }
        boolean splits = splitsFor(role, size[0], size[1]);
        return splits ? "拆分状态：拆成四格（前 后 左 右）" : "拆分状态：整幅显示，不拆";
    }

    private boolean splitsFor(String role, int width, int height) {
        String cameraId = CameraProfile.ROLE_COMPOSITE.equals(role)
                ? StreamLayoutTable.compositeCameraId() : null;
        return StreamLayoutTable.stackingFor(cameraId, width, height)
                != com.kooo.evcam.zeekr.CompositeStreamGeometry.Stacking.NOT_COMPOSITE;
    }

    // ------------------------------------------------------------------ 编辑

    private void pickResolution(CameraProfile camera, StreamSpec spec) {
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        values.add(StreamSpec.RESOLUTION_AUTO);
        labels.add("自动（探测结果 / 沿用原规则）");
        values.add(StreamSpec.RESOLUTION_MAX);
        labels.add("最大（这一路声明的最大尺寸）");
        for (int[] size : declaredSizes(camera.role)) {
            String text = size[0] + "x" + size[1];
            values.add(text);
            labels.add(text + (splitsFor(camera.role, size[0], size[1]) ? "   拆四格" : ""));
        }

        new AlertDialog.Builder(this, R.style.AlertDialogTheme)
                .setTitle(roleName(camera.role) + " · 分辨率")
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    spec.resolution = values.get(which);
                    render();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ 校验

    private void validateAndSave() {
        List<ProfileValidation.Issue> issues =
                ProfileValidation.check(profile, new ProfileValidation.Capabilities() {
                    @Override
                    public int[][] declaredSizes(String role) {
                        List<int[]> sizes = ProfileEditorActivity.this.declaredSizes(role);
                        return sizes.isEmpty() ? null : sizes.toArray(new int[0][]);
                    }

                    @Override
                    public boolean splits(String role, int width, int height) {
                        return splitsFor(role, width, height);
                    }
                });

        if (ProfileValidation.hasBlocking(issues)) {
            showIssues("这份配置存不了", issues, null);
            return;
        }
        showIssues("检查通过，接下来实测", issues, this::measureThenConfirm);
    }

    private void showIssues(String title, List<ProfileValidation.Issue> issues,
                            Runnable onContinue) {
        StringBuilder sb = new StringBuilder();
        for (ProfileValidation.Issue issue : issues) {
            sb.append(issue).append('\n');
        }
        if (sb.length() == 0) {
            sb.append("没有发现问题。");
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.AlertDialogTheme)
                .setTitle(title)
                .setMessage(sb.toString());
        if (onContinue != null) {
            builder.setPositiveButton("继续", (d, w) -> onContinue.run())
                    .setNegativeButton(R.string.action_cancel, null);
        } else {
            builder.setPositiveButton(R.string.action_got_it, null);
        }
        builder.show();
    }

    /**
     * 采几秒实际帧率，再连同倒计时一起让人确认。
     *
     * <p>采的是<b>当前正在跑的那套</b>的帧率 —— 新配置要生效得重启应用，
     * 所以这个数是「现在这样能跑到多少」，不是「按新配置能跑到多少」。
     * 它的价值在于给出一个基线：真按新配置重启之后跑成什么样，
     * 回到这里再采一次就知道了。这一点必须写在界面上，否则就是在骗人。</p>
     */
    private void measureThenConfirm() {
        long[] before = frameCounts();
        status.setText("正在采样……");
        ui.postDelayed(() -> {
            long[] after = frameCounts();
            StringBuilder sb = new StringBuilder();
            String[] keys = {"front", "back", "left", "right"};
            for (int i = 0; i < keys.length; i++) {
                if (after[i] <= 0) {
                    continue;
                }
                sb.append(String.format(Locale.US, "%-6s %.1f fps%n",
                        keys[i], (after[i] - before[i]) / 3f));
            }
            if (sb.length() == 0) {
                sb.append("这三秒里一路都没有出帧（相机可能没在跑）。\n");
            }
            status.setText("");
            confirmWithCountdown(sb.toString());
        }, 3000L);
    }

    private long[] frameCounts() {
        String[] keys = {"front", "back", "left", "right"};
        long[] out = new long[keys.length];
        for (int i = 0; i < keys.length; i++) {
            out[i] = PreviewFrameRates.totalFrames(keys[i]);
        }
        return out;
    }

    private void confirmWithCountdown(String measured) {
        final AlertDialog dialog = new AlertDialog.Builder(this, R.style.AlertDialogTheme)
                .setTitle("保存这份配置？")
                .setMessage(measured
                        + "\n以上是【当前这套】的实测帧率，作为基线。"
                        + "新配置要重启应用才生效。\n\n"
                        + CONFIRM_SECONDS + " 秒内不确认就放弃保存。")
                .setPositiveButton("保存", (d, w) -> {
                    store.save(profile);
                    AppLog.i(TAG, "配置已保存:\n" + profile);
                    status.setText("已保存，重启应用后生效");
                })
                .setNegativeButton(R.string.action_cancel, null)
                .setCancelable(false)
                .create();
        dialog.show();

        final int[] left = {CONFIRM_SECONDS};
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            left[0]--;
            if (!dialog.isShowing()) {
                return;
            }
            if (left[0] <= 0) {
                dialog.dismiss();
                status.setText("超时未确认，没有保存");
                return;
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                    .setText("保存（" + left[0] + "）");
            ui.postDelayed(tick[0], 1000L);
        };
        ui.postDelayed(tick[0], 1000L);
    }

    private void confirmReset() {
        new AlertDialog.Builder(this, R.style.AlertDialogTheme)
                .setTitle("恢复为初值")
                .setMessage("把这份配置改回从旧设置翻译出来的样子，你在这里改的会丢掉。")
                .setPositiveButton("恢复", (d, w) -> {
                    profile = store.reset(profile.id);
                    render();
                    status.setText("已恢复为初值");
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    // ------------------------------------------------------------------ 相机信息

    /** 这一路声明的尺寸。合成流那一路按登记的相机 id 找，座舱按角色顺序。 */
    private List<int[]> declaredSizes(String role) {
        List<int[]> out = new ArrayList<>();
        String cameraId = cameraIdFor(role);
        if (cameraId == null) {
            return out;
        }
        try {
            StreamConfigurationMap map = cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return out;
            }
            Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null) {
                return out;
            }
            for (Size size : sizes) {
                out.add(new int[]{size.getWidth(), size.getHeight()});
            }
            java.util.Collections.sort(out, (a, b) -> Long.compare(
                    (long) b[0] * b[1], (long) a[0] * a[1]));
        } catch (Exception e) {
            AppLog.w(TAG, "读不到 " + role + " 的尺寸列表: " + e);
        }
        return out;
    }

    private String cameraIdFor(String role) {
        String composite = StreamLayoutTable.compositeCameraId();
        try {
            String[] ids = cameraManager.getCameraIdList();
            if (CameraProfile.ROLE_COMPOSITE.equals(role)) {
                return composite != null ? composite : (ids.length > 0 ? ids[0] : null);
            }
            List<String> others = new ArrayList<>();
            for (String id : ids) {
                if (!id.equals(composite)) {
                    others.add(id);
                }
            }
            int index = CameraProfile.ROLE_CABIN_1.equals(role) ? 0 : 1;
            return index < others.size() ? others.get(index) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String cameraSummary(String role) {
        String id = cameraIdFor(role);
        return id == null ? "（找不到对应相机）" : "相机 " + id;
    }

    private static String roleName(String role) {
        switch (role) {
            case CameraProfile.ROLE_COMPOSITE:
                return "环视合成流";
            case CameraProfile.ROLE_CABIN_1:
                return "座舱 1";
            case CameraProfile.ROLE_CABIN_2:
                return "座舱 2";
            default:
                return role;
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
