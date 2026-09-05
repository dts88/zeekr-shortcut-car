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
import com.kooo.evcam.camera.EncodeSize;
import com.kooo.evcam.R;
import com.kooo.evcam.camera.CameraNames;
import com.kooo.evcam.zeekr.StreamLayoutTable;

import java.util.ArrayList;
import java.util.List;

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
 *   <li><b>看画面</b>：按新配置真开一次相机。会话配得起来不等于跑得动，
 *       而拆分对不对、位置对不对，只有看一眼才知道。</li>
 * </ol>
 *
 * <p>看画面那一步在 {@link ProfilePreviewCheck}：按新配置真的开一次相机，
 * 拆四格的尺寸就用真正的四宫格容器显示，看到的排布和主界面一致。
 * 倒计时结束不保存 —— 配错了最坏是黑屏，那时候人是点不动屏幕的。</p>
 */
public class ProfileEditorActivity extends Activity {

    private static final String TAG = "ProfileEditor";

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

        root.addView(button("加一路相机", v -> addCamera()));
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
        row.addView(smallButton("移除", v -> removeCamera(camera)));
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
        // auto / max 是意图，不是数。不写出它到底解成多少，
        // 用户只能猜 —— 而环视那一路的 max 并不是像素最多的那个。
        String resolved = describeResolved(camera.role, spec);
        if (resolved != null) {
            sb.append("  → ").append(resolved);
        }
        if (spec == camera.record) {
            sb.append("   帧率 ").append(spec.fps)
                    .append("   码率 ").append(spec.bitrate)
                    .append("   分段 ").append(spec.segmentMinutes).append(" 分钟");
            if (showsSplit) {
                // 排列只对会拆的那一路有意义：不拆的画面本来就只有一格
                sb.append("   排列 ").append(spec.grid ? "四宫格" : "原始长条");
            }
        }
        if (spec == camera.photo) {
            sb.append("   质量 ").append(spec.jpegQuality);
            // 图片通道关着时，拍照走的是抓预览画面那条路，
            // 这一项根本不起作用 —— 不写出来就又是一个「显示值≠实际值」。
            if (!new com.kooo.evcam.AppConfig(this).isPhotoViaJpegEnabled()) {
                sb.append('\n').append("        开发者选项里的「拍照走图片通道」没开，"
                        + "此项不生效（拍照跟随预览分辨率）");
            }
        }
        if (showsSplit) {
            sb.append('\n').append("        ").append(splitStatus(camera.role, spec));
            String landing = describeLanding(camera, spec);
            if (landing != null) {
                sb.append('\n').append("        ").append(landing);
            }
        }
        text.setText(sb.toString());
        text.setTextColor(0xFFCCCCCC);
        text.setTextSize(14f);
        row.addView(text);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(smallButton("分辨率", v -> pickResolution(camera, spec)));
        if (spec == camera.record) {
            buttons.addView(smallButton("帧率", v -> pickFps(spec)));
            buttons.addView(smallButton("码率", v -> pickBitrate(spec)));
            buttons.addView(smallButton("分段", v -> pickSegment(spec)));
            if (showsSplit) {
                buttons.addView(smallButton("排列", v -> {
                    spec.grid = !spec.grid;
                    render();
                }));
            }
        }
        if (spec == camera.preview && !camera.lanes.isEmpty()) {
            buttons.addView(smallButton("旋转", v -> cycleRotation(camera)));
            buttons.addView(smallButton("镜像", v -> toggleMirror(camera)));
        }
        row.addView(buttons);
        return row;
    }

    /**
     * 旋转和镜像作用在<b>每一格</b>上，不是整路相机。
     *
     * <p>合成流拆出来的四格各自可以有自己的方向 —— 后视那一格默认镜像，
     * 和真实后视镜一致，其余三格不镜像。所以这里改的是所有格子还是某一格，
     * 取决于这一路拆没拆：没拆就只有一格，改它就是改这一路。</p>
     */
    private void cycleRotation(CameraProfile camera) {
        if (camera.lanes.size() == 1) {
            LaneLayout lane = camera.lanes.get(0);
            lane.rotation = (lane.rotation + 90) % 360;
            render();
            return;
        }
        pickLane(camera, "旋转哪一格", lane -> {
            lane.rotation = (lane.rotation + 90) % 360;
            render();
        });
    }

    private void toggleMirror(CameraProfile camera) {
        if (camera.lanes.size() == 1) {
            LaneLayout lane = camera.lanes.get(0);
            lane.mirrored = !lane.mirrored;
            render();
            return;
        }
        pickLane(camera, "镜像哪一格", lane -> {
            lane.mirrored = !lane.mirrored;
            render();
        });
    }

    private interface LaneAction {
        void apply(LaneLayout lane);
    }

    /** 拆成四格时得先问改哪一格。格子的名字就是方位。 */
    private void pickLane(CameraProfile camera, String title, LaneAction action) {
        String[] labels = new String[camera.lanes.size()];
        for (int i = 0; i < labels.length; i++) {
            LaneLayout lane = camera.lanes.get(i);
            labels[i] = CameraNames.ofLane(this, lane.laneIndex) + "   " + lane;
        }
        new AlertDialog.Builder(this, R.style.AlertDialogTheme)
                .setTitle(title)
                .setItems(labels, (d, which) -> action.apply(camera.lanes.get(which)))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void pickFps(StreamSpec spec) {
        String[] values = {StreamSpec.FPS_UNLIMITED, "30", "24", "20", "15", "10"};
        // 措辞和设置里那一项保持一致：只写数字，不加「最高」
        String[] labels = {"原始帧率（不限制）", "30 fps", "24 fps",
                "20 fps", "15 fps", "10 fps"};
        pickOne("录制帧率", labels, values, value -> {
            spec.fps = value;
            render();
        });
    }

    private void pickBitrate(StreamSpec spec) {
        String[] values = {"low", "medium", "high"};
        String[] labels = {"低", "中", "高"};
        pickOne("录制码率", labels, values, value -> {
            spec.bitrate = value;
            render();
        });
    }

    private void pickSegment(StreamSpec spec) {
        String[] values = {"1", "3", "5", "10"};
        String[] labels = {"1 分钟", "3 分钟", "5 分钟", "10 分钟"};
        pickOne("分段时长", labels, values, value -> {
            spec.segmentMinutes = Integer.parseInt(value);
            render();
        });
    }

    private interface Chosen {
        void set(String value);
    }

    private void pickOne(String title, String[] labels, String[] values, Chosen chosen) {
        new AlertDialog.Builder(this, R.style.AlertDialogTheme)
                .setTitle(title)
                .setItems(labels, (d, which) -> chosen.set(values[which]))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private Button smallButton(String text, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        view.setTextSize(13f);
        view.setOnClickListener(listener);
        return view;
    }

    /** auto / max 实际解成多少；已经是具体值时返回 null（不用重复）。 */
    private String describeResolved(String role, StreamSpec spec) {
        if (ProfileResolution.parse(spec.resolution) != null) {
            return null;
        }
        int[] max = ProfileSizes.declaredMax(this, role);
        if (StreamSpec.RESOLUTION_MAX.equals(spec.resolution)) {
            return max == null ? "读不到尺寸" : max[0] + "x" + max[1];
        }
        // auto：会拆的那一路取每格最清楚的那个尺寸，其他路跟随预览
        if (!CameraProfile.ROLE_COMPOSITE.equals(role)) {
            return "跟随预览";
        }
        return max == null ? "读不到尺寸" : max[0] + "x" + max[1] + "（每格最清楚的那个）";
    }

    /**
     * 这个尺寸拆完之后每格多大、最后落盘多大。
     *
     * <h3>为什么必须写出来</h3>
     *
     * <p>配置里写的是<b>向相机要多大</b>，而拆四格会把它重排成 2×2 —— 两个数不一样。
     * 不写出来就会出现「我明明选了 1920×1024，录出来却是 3840×512」：
     * 那一路的每格是 1920×256，拼成 2×2 正好是 3840×512，一步都没错，
     * 但界面上从来没说过这件事。</p>
     *
     * <p>顺带把每格尺寸也写出来 —— 每格被压成 1920×256 这种 7:1 的形状，
     * 画面看起来就是扁的，只有看到这个数才知道为什么。</p>
     */
    private String describeLanding(CameraProfile camera, StreamSpec spec) {
        int[] source = resolvedSource(camera.role, spec);
        if (source == null) {
            return null;
        }
        if (!splitsFor(camera.role, source[0], source[1])) {
            return null;
        }
        int laneWidth = source[0];
        int laneHeight = source[1] / 4;
        StringBuilder sb = new StringBuilder();
        sb.append("每格 ").append(laneWidth).append("×").append(laneHeight);
        if (spec == camera.preview) {
            return sb.toString();
        }
        // 录制和拍照都会按录制那一路的排列重排后落盘
        boolean grid = camera.record != null && camera.record.grid;
        EncodeSize landing = EncodeSize.forSource(
                StreamLayoutTable.compositeCameraId(), source[0], source[1], grid);
        sb.append("，落盘 ").append(landing.width).append("×").append(landing.height);
        return sb.toString();
    }

    /**
     * 这条流最后会向相机要多大。
     *
     * <p>{@code auto} 与 {@code max} 都要解成具体的数才能算落盘尺寸；解不出来
     * （相机还没探测到）就返回 null，不编一个数。</p>
     */
    private int[] resolvedSource(String role, StreamSpec spec) {
        int[] parsed = ProfileResolution.parse(spec.resolution);
        if (parsed != null) {
            return parsed;
        }
        int[] max = ProfileSizes.declaredMax(this, role);
        if (StreamSpec.RESOLUTION_MAX.equals(spec.resolution)) {
            return max;
        }
        // auto：会拆的那一路取每格最清楚的那个（和 ProfileSizes 同一条规则），
        // 不拆的那一路交给相机自己挑，这里说不准
        return CameraProfile.ROLE_COMPOSITE.equals(role) ? max : null;
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
        labels.add("自动（" + (CameraProfile.ROLE_COMPOSITE.equals(camera.role)
                ? "每格最清楚的那个尺寸" : "跟随预览") + "）");
        values.add(StreamSpec.RESOLUTION_MAX);
        labels.add("最大（这一路声明的最大尺寸）");
        boolean grid = camera.record != null && camera.record.grid;
        for (int[] size : declaredSizes(camera.role)) {
            String text = size[0] + "x" + size[1];
            values.add(text);
            if (!splitsFor(camera.role, size[0], size[1])) {
                labels.add(text);
                continue;
            }
            // 选的是「向相机要多大」，而拆四格之后落盘是另一个数。
            // 两个数都摆在这里，省得选完才发现录出来的是 3840×512。
            EncodeSize landing = EncodeSize.forSource(
                    StreamLayoutTable.compositeCameraId(), size[0], size[1], grid);
            labels.add(text + "   拆四格 · 每格 " + size[0] + "×" + (size[1] / 4)
                    + " · 落盘 " + landing.width + "×" + landing.height);
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

    /**
     * 往这份配置里加一路相机。
     *
     * <p>「用哪几路」本来就该是配置的一部分。之前只能改分辨率，是因为编辑器
     * 只做了一半 —— 单路那份配置里锁着一路环视，加不进座舱。</p>
     */
    private void addCamera() {
        List<String> roles = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (String role : new String[]{CameraProfile.ROLE_COMPOSITE,
                CameraProfile.ROLE_CABIN_1, CameraProfile.ROLE_CABIN_2}) {
            if (profile.camera(role) == null) {
                roles.add(role);
                labels.add(roleName(role) + "   " + cameraSummary(role));
            }
        }
        if (roles.isEmpty()) {
            toast("三路都已经在这份配置里了");
            return;
        }
        new AlertDialog.Builder(this, R.style.AlertDialogTheme)
                .setTitle("加一路相机")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    profile.cameras.add(newCamera(roles.get(which)));
                    render();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private CameraProfile newCamera(String role) {
        CameraProfile camera = new CameraProfile(role);
        camera.preview = StreamSpec.preview(StreamSpec.RESOLUTION_AUTO);
        camera.record = StreamSpec.record(StreamSpec.RESOLUTION_AUTO,
                StreamSpec.FPS_UNLIMITED, "medium", "auto", 3);
        camera.photo = StreamSpec.photo(StreamSpec.RESOLUTION_MAX, 95);
        if (CameraProfile.ROLE_COMPOSITE.equals(role)) {
            for (int lane = 0; lane < 4; lane++) {
                camera.lanes.add(LaneLayout.cell(lane,
                        (lane % 2) * 0.5f, (lane / 2) * 0.5f, 0.5f, 0.5f));
            }
        } else {
            camera.lanes.add(LaneLayout.cell(-1, 0f, 0f, 1f, 1f));
        }
        return camera;
    }

    private void removeCamera(CameraProfile camera) {
        new AlertDialog.Builder(this, R.style.AlertDialogTheme)
                .setTitle("移除 " + roleName(camera.role))
                .setMessage("从这份配置里去掉这一路。它的参数会一起丢掉。")
                .setPositiveButton("移除", (d, w) -> {
                    profile.cameras.remove(camera);
                    render();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void toast(String text) {
        android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show();
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
            showIssues("这份配置存不了", issues);
            return;
        }
        // 检查通过就直接往下走。原来这里还弹一个「检查通过，接下来实测」——
        // 那个框不承载任何信息，只是让人多点一次。
        previewThenSave(issues);
    }

    private void showIssues(String title, List<ProfileValidation.Issue> issues) {
        StringBuilder sb = new StringBuilder();
        for (ProfileValidation.Issue issue : issues) {
            sb.append(issue).append('\n');
        }
        new AlertDialog.Builder(this, R.style.AlertDialogTheme)
                .setTitle(title)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.action_got_it, null)
                .show();
    }

    /**
     * 按新配置真的开一次画面，看清楚再决定保不保存。
     *
     * <p>原来这里采一段帧率再把数字摆出来 —— 那个数字<b>永远是 0</b>：编辑界面是
     * 另一个 Activity，主界面已经退到后台、相机已经被关掉了，根本没有帧在走。</p>
     *
     * <p>而且就算数字是真的，它也回答不了真正要问的问题：画面出得来吗、拆分对吗、
     * 位置对吗。这三件事只有看一眼才知道。</p>
     */
    private void previewThenSave(List<ProfileValidation.Issue> warnings) {
        CameraProfile first = firstEnabled();
        if (first == null) {
            showIssues("没有启用任何相机", warnings);
            return;
        }
        String cameraId = cameraIdFor(first.role);
        if (cameraId == null) {
            showIssues("找不到 " + roleName(first.role) + " 对应的相机", warnings);
            return;
        }

        int[] probed = null;
        int[] parsed = ProfileResolution.parse(first.preview.resolution);
        if (parsed != null) {
            probed = parsed;
        }
        ProfileResolution.Size resolved = ProfileResolution.resolve(
                first.preview.resolution, probed,
                ProfileSizes.declaredMax(this, first.role));
        Size size = resolved.specified()
                ? new Size(resolved.width, resolved.height) : null;
        boolean split = size != null
                && splitsFor(first.role, size.getWidth(), size.getHeight());

        new ProfilePreviewCheck(this).run(cameraId, size, split, () -> {
            store.save(profile);
            AppLog.i(TAG, "配置已保存:\n" + profile);
            status.setText("已保存，重启应用后生效");
        });
    }

    private CameraProfile firstEnabled() {
        for (CameraProfile camera : profile.cameras) {
            if (camera.enabled) {
                return camera;
            }
        }
        return null;
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
