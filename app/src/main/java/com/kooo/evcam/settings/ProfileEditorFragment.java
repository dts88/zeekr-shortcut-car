package com.kooo.evcam.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.ImageFormat;
import android.os.Bundle;
import android.text.InputType;
import android.util.Size;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.SwitchPreferenceCompat;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.camera.CameraNames;
import com.kooo.evcam.camera.EncodeSize;
import com.kooo.evcam.profile.CameraProfile;
import com.kooo.evcam.profile.LaneLayout;
import com.kooo.evcam.profile.Profile;
import com.kooo.evcam.profile.ProfilePreviewCheck;
import com.kooo.evcam.profile.ProfileResolution;
import com.kooo.evcam.profile.ProfileSizes;
import com.kooo.evcam.profile.ProfileStore;
import com.kooo.evcam.profile.ProfileValidation;
import com.kooo.evcam.profile.StreamSpec;
import com.kooo.evcam.zeekr.StreamLayoutTable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 视频流配置编辑：哪几路相机、每一路的三条流、以及拆出来的每一格怎么摆。
 *
 * <h3>为什么长在设置里，而不是自己一个界面</h3>
 *
 * <p>它原来是开发者选项里的一个独立 Activity，自己拿 {@code LinearLayout} 摆按钮 ——
 * 和设置界面完全两种样子。现在相机与视频流的参数<b>只有这里一个入口</b>，
 * 它就不再是排查工具而是常规设置，那就该长得和别的设置一样。</p>
 *
 * <h3>为什么每一格分开编辑</h3>
 *
 * <p>环视那一路在 {@link StreamLayoutTable} 里，也就是说它<b>一定</b>被拆成四格。
 * 拆开之后「这一路的旋转」是个说不通的说法：前视要不要转、后视要不要镜像，
 * 是四件互不相干的事。所以位置、大小、旋转、镜像、裁切、缩放平移全部按格子存、按格子改。</p>
 *
 * <h3>为什么没有「排列」这一项</h3>
 *
 * <p>会拆的那一路一定拼成 2×2 落盘 —— 长条那一版每格丢一半细节，回放放大也是按 2×2
 * 取景的。它作为代码里的兜底值还在（{@code StreamSpec.grid}），但不再是一个选项：
 * 给一个只有一个正确答案的选择题，只会让人以为另一个答案也行。</p>
 */
public class ProfileEditorFragment extends PreferenceFragmentCompat {

    private static final String TAG = "ProfileEditor";

    private ProfileStore store;
    private Profile profile;
    private CameraManager cameraManager;

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        Context context = requireContext();
        store = new ProfileStore(context);
        profile = store.current();
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(context));
        render();
    }

    // ------------------------------------------------------------------ 界面

    private void render() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null || getContext() == null) {
            return;
        }
        screen.removeAll();
        Context context = requireContext();

        Preference header = new Preference(context);
        header.setTitle(profile.name.isEmpty() ? profile.id : profile.name);
        header.setSummary("改动要按下面的「保存」才写进去，保存前会先按新配置开一次画面");
        header.setSelectable(false);
        screen.addPreference(header);

        for (CameraProfile camera : new ArrayList<>(profile.cameras)) {
            addCamera(screen, context, camera);
        }

        PreferenceCategory actions = category(screen, context, "这份配置");
        click(actions, context, "加一路相机", null, this::addCamera);
        click(actions, context, "保存", "先看一眼新配置下的实际画面，确认了才写进去",
                this::saveWithPreview);
        click(actions, context, "恢复为初值", "丢掉这里改过的，回到新建时的样子",
                this::confirmReset);
    }

    private void addCamera(PreferenceScreen screen, Context context, CameraProfile camera) {
        PreferenceCategory group = category(screen, context,
                roleName(camera.role) + "   " + cameraSummary(camera.role));

        SwitchPreferenceCompat enabled = new SwitchPreferenceCompat(context);
        enabled.setPersistent(false);
        enabled.setTitle("启用这一路");
        enabled.setSummary("关掉之后这一路不开、不录、不占流");
        enabled.setChecked(camera.enabled);
        enabled.setOnPreferenceChangeListener((p, value) -> {
            camera.enabled = Boolean.TRUE.equals(value);
            render();
            return false;
        });
        group.addPreference(enabled);

        boolean splits = splitsFor(camera.role);

        click(group, context, "预览 · 分辨率", describeStream(camera, camera.preview),
                () -> pickResolution(camera, camera.preview));
        click(group, context, "录制 · 分辨率", describeStream(camera, camera.record),
                () -> pickResolution(camera, camera.record));
        click(group, context, "录制 · 帧率", fpsLabel(camera.record.fps),
                () -> pickFps(camera.record));
        click(group, context, "录制 · 码率", bitrateLabel(camera.record.bitrate),
                () -> pickOne("录制 · 码率",
                        new String[]{"跟随画质等级（中）", "低", "中", "高"},
                        new String[]{StreamSpec.BITRATE_AUTO, "low", "medium", "high"},
                        value -> camera.record.bitrate = value));
        click(group, context, "录制 · 编码", codecLabel(camera.record.codec),
                () -> pickOne("录制 · 编码",
                        new String[]{"自动（优先 H.265）", "H.264"},
                        new String[]{"auto", "h264"},
                        value -> camera.record.codec = value));
        click(group, context, "录制 · 分段时长", camera.record.segmentMinutes + " 分钟",
                () -> pickOne("录制 · 分段时长",
                        new String[]{"1 分钟", "3 分钟", "5 分钟", "10 分钟"},
                        new String[]{"1", "3", "5", "10"},
                        value -> camera.record.segmentMinutes = Integer.parseInt(value)));
        click(group, context, "拍照 · 分辨率", describeStream(camera, camera.photo),
                () -> pickResolution(camera, camera.photo));
        click(group, context, "拍照 · 质量", camera.photo.jpegQuality + "",
                () -> pickOne("拍照 · 质量",
                        new String[]{"95（默认）", "90", "80", "70"},
                        new String[]{"95", "90", "80", "70"},
                        value -> camera.photo.jpegQuality = Integer.parseInt(value)));

        for (int i = 0; i < camera.lanes.size(); i++) {
            addLane(screen, context, camera, camera.lanes.get(i), splits, i);
        }

        click(group, context, "移除这一路", "它的参数会一起丢掉",
                () -> confirmRemove(camera));
    }

    /**
     * 一格的摆法。
     *
     * <p>不拆的那一路也有一格 —— 它就是整幅画面，位置固定铺满，
     * 所以那一格不给「位置与大小」，只留旋转、镜像、裁切、缩放平移。</p>
     */
    private void addLane(PreferenceScreen screen, Context context, CameraProfile camera,
                         LaneLayout lane, boolean splits, int index) {
        String name = splits && lane.laneIndex >= 0
                ? CameraNames.ofLane(context, lane.laneIndex)
                : "整幅画面";
        PreferenceCategory group = category(screen, context,
                roleName(camera.role) + " · " + name);

        if (!splits) {
            // 座舱那两路的画面还是各自的 TextureView，旋转镜像走的是旧的相机矫正，
            // 不读这里。说出来 —— 一个改了不生效的选项比没有更糟。
            Preference note = new Preference(context);
            note.setSelectable(false);
            note.setTitle("这一路的下列调整暂不生效");
            note.setSummary("它的画面还走旧的相机矫正那条路，配置里的值没有人读。"
                    + "环视那一路（拆四格的）是生效的");
            group.addPreference(note);
        }

        if (splits) {
            click(group, context, "位置与大小",
                    String.format(Locale.US, "左上 %.2f, %.2f    大小 %.2f × %.2f",
                            lane.x, lane.y, lane.width, lane.height),
                    () -> editNumbers("位置与大小（容器宽高的比例，0–1）",
                            new String[]{"左 x", "上 y", "宽", "高"},
                            new float[]{lane.x, lane.y, lane.width, lane.height},
                            values -> {
                                lane.x = values[0];
                                lane.y = values[1];
                                lane.width = values[2];
                                lane.height = values[3];
                            }));
        }
        click(group, context, "旋转", lane.rotation + "°", () -> {
            lane.rotation = (lane.rotation + 90) % 360;
            render();
        });

        SwitchPreferenceCompat mirror = new SwitchPreferenceCompat(context);
        mirror.setPersistent(false);
        mirror.setTitle("镜像");
        mirror.setSummary("左右翻转，和真实后视镜一致");
        mirror.setChecked(lane.mirrored);
        mirror.setOnPreferenceChangeListener((p, value) -> {
            lane.mirrored = Boolean.TRUE.equals(value);
            render();
            return false;
        });
        group.addPreference(mirror);

        click(group, context, "裁切",
                String.format(Locale.US, "上 %.2f  下 %.2f  左 %.2f  右 %.2f",
                        lane.cropTop, lane.cropBottom, lane.cropLeft, lane.cropRight),
                () -> editNumbers("裁切（各边切掉多少，画面的比例）",
                        new String[]{"上", "下", "左", "右"},
                        new float[]{lane.cropTop, lane.cropBottom, lane.cropLeft, lane.cropRight},
                        values -> {
                            lane.cropTop = values[0];
                            lane.cropBottom = values[1];
                            lane.cropLeft = values[2];
                            lane.cropRight = values[3];
                        }));
        click(group, context, "缩放与平移",
                String.format(Locale.US, "缩放 %.2f × %.2f    平移 %.2f, %.2f",
                        lane.scaleX, lane.scaleY, lane.translateX, lane.translateY),
                () -> editNumbers("缩放与平移（缩放 1 为原样，平移是这一格的比例）",
                        new String[]{"缩放 x", "缩放 y", "平移 x", "平移 y"},
                        new float[]{lane.scaleX, lane.scaleY, lane.translateX, lane.translateY},
                        values -> {
                            lane.scaleX = values[0];
                            lane.scaleY = values[1];
                            lane.translateX = values[2];
                            lane.translateY = values[3];
                        }));
    }

    // ------------------------------------------------------------------ 尺寸的说明

    /**
     * 一条流的分辨率现在是多少、解出来是多少、落盘是多少。
     *
     * <h3>为什么 auto / max 也要写出数字</h3>
     *
     * <p>它们是<b>意图</b>，配置里存的就是这两个词。但看的人要的是那个数 ——
     * 不写出来，「自动」到底是 1280×5140 还是 1280×800 只能靠猜。</p>
     */
    private String describeStream(CameraProfile camera, StreamSpec spec) {
        StringBuilder sb = new StringBuilder(resolutionLabel(camera.role, spec));
        if (spec == camera.photo
                && !new com.kooo.evcam.AppConfig(requireContext()).isPhotoViaJpegEnabled()) {
            // 关着「拍照走图片通道」时拍照是抓预览画面，这一项根本不参与
            return sb + "（「拍照走图片通道」关着，此项不生效，拍照跟随预览）";
        }
        int[] source = resolvedSource(camera.role, spec);
        if (source == null || !splitsFor(camera.role)) {
            return sb.toString();
        }
        sb.append("    每格 ").append(source[0]).append("×").append(source[1] / 4);
        // 落盘尺寸要走拆分几何，而几何认的是相机 id：相机还没起来时算不出来，
        // 那就不写 —— 编一个数比不写更糟
        if (spec != camera.preview && StreamLayoutTable.compositeCameraId() != null) {
            EncodeSize landing = EncodeSize.forSource(
                    StreamLayoutTable.compositeCameraId(), source[0], source[1],
                    camera.record != null && camera.record.grid);
            sb.append("，落盘 ").append(landing.width).append("×").append(landing.height);
        }
        return sb.toString();
    }

    /** 「自动 → 1280x5140」这种写法：意图在前，解出来的数在后。 */
    private String resolutionLabel(String role, StreamSpec spec) {
        if (ProfileResolution.parse(spec.resolution) != null) {
            return spec.resolution;
        }
        int[] resolved = resolvedSource(role, spec);
        String word = StreamSpec.RESOLUTION_MAX.equals(spec.resolution) ? "最大" : "自动";
        if (resolved == null) {
            // 解不出来就说清楚是谁决定的，不编一个数
            return word + " → 由相机按最接近 1280x800 挑";
        }
        return word + " → " + resolved[0] + "x" + resolved[1];
    }

    /**
     * 这条流最后会向相机要多大；解不出来返回 null。
     *
     * <p>{@code auto} 在会拆的那一路是「每格最清楚的那个声明尺寸」，其他路跟随预览
     * ——和 {@link ProfileSizes} 是同一条规则，两边不能各说各的。</p>
     */
    private int[] resolvedSource(String role, StreamSpec spec) {
        int[] parsed = ProfileResolution.parse(spec.resolution);
        if (parsed != null) {
            return parsed;
        }
        int[] max = ProfileSizes.declaredMax(requireContext(), role);
        if (StreamSpec.RESOLUTION_MAX.equals(spec.resolution)) {
            return max;
        }
        return CameraProfile.ROLE_COMPOSITE.equals(role) ? max : null;
    }

    private String fpsLabel(String fps) {
        return StreamSpec.FPS_UNLIMITED.equals(fps) ? "原始帧率（不限制）" : fps + " fps（上限）";
    }

    private String bitrateLabel(String bitrate) {
        if ("low".equals(bitrate)) {
            return "低";
        }
        if ("high".equals(bitrate)) {
            return "高";
        }
        if ("medium".equals(bitrate)) {
            return "中";
        }
        return "跟随画质等级（中）";
    }

    private String codecLabel(String codec) {
        return "h264".equals(codec) ? "H.264" : "自动（优先 H.265）";
    }

    // ------------------------------------------------------------------ 选择

    private void pickResolution(CameraProfile camera, StreamSpec spec) {
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        values.add(StreamSpec.RESOLUTION_AUTO);
        labels.add("自动 → " + resolvedText(camera.role, StreamSpec.RESOLUTION_AUTO));
        values.add(StreamSpec.RESOLUTION_MAX);
        labels.add("最大 → " + resolvedText(camera.role, StreamSpec.RESOLUTION_MAX));

        boolean grid = camera.record != null && camera.record.grid;
        String compositeId = StreamLayoutTable.compositeCameraId();
        for (int[] size : declaredSizes(camera.role)) {
            values.add(size[0] + "x" + size[1]);
            if (!splitsFor(camera.role)) {
                labels.add(size[0] + "x" + size[1]);
                continue;
            }
            String text = size[0] + "x" + size[1] + "   每格 " + size[0] + "×" + (size[1] / 4);
            if (compositeId != null) {
                EncodeSize landing = EncodeSize.forSource(compositeId, size[0], size[1], grid);
                text += " · 落盘 " + landing.width + "×" + landing.height;
            }
            labels.add(text);
        }
        pickOne(roleName(camera.role) + " · 分辨率",
                labels.toArray(new String[0]), values.toArray(new String[0]),
                value -> spec.resolution = value);
    }

    /** 「自动」「最大」在这一路解出来是多少，选之前就该看见。 */
    private String resolvedText(String role, String intent) {
        StreamSpec probe = new StreamSpec();
        probe.resolution = intent;
        int[] resolved = resolvedSource(role, probe);
        if (resolved == null) {
            return "由相机按最接近 1280x800 挑";
        }
        return resolved[0] + "x" + resolved[1];
    }

    private void pickFps(StreamSpec spec) {
        pickOne("录制 · 帧率",
                new String[]{"原始帧率（不限制）", "30 fps", "24 fps", "20 fps", "15 fps", "10 fps"},
                new String[]{StreamSpec.FPS_UNLIMITED, "30", "24", "20", "15", "10"},
                value -> spec.fps = value);
    }

    private interface Chosen {
        void set(String value);
    }

    private void pickOne(String title, String[] labels, String[] values, Chosen chosen) {
        new AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                .setTitle(title)
                .setItems(labels, (d, which) -> {
                    chosen.set(values[which]);
                    render();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private interface Numbers {
        void set(float[] values);
    }

    /**
     * 几个小数一起改。
     *
     * <p>位置、裁切、缩放这些都是<b>一组</b>数，一个一个弹窗改会让人对不上 ——
     * 改完宽还要再点一次改高，中间那一下界面已经动过了。</p>
     */
    private void editNumbers(String title, String[] labels, float[] current, Numbers onOk) {
        Context context = requireContext();
        LinearLayout box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * context.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);

        EditText[] inputs = new EditText[labels.length];
        for (int i = 0; i < labels.length; i++) {
            TextView label = new TextView(context);
            label.setText(labels[i]);
            box.addView(label);

            EditText input = new EditText(context);
            input.setInputType(InputType.TYPE_CLASS_NUMBER
                    | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
            input.setText(String.format(Locale.US, "%.4f", current[i]));
            input.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            box.addView(input);
            inputs[i] = input;
        }

        new AlertDialog.Builder(context, R.style.AlertDialogTheme)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    float[] values = new float[inputs.length];
                    for (int i = 0; i < inputs.length; i++) {
                        values[i] = parseFloat(inputs[i].getText().toString(), current[i]);
                    }
                    onOk.set(values);
                    render();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private static float parseFloat(String text, float fallback) {
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return fallback;   // 输错了就保持原值，不要把它变成 0
        }
    }

    // ------------------------------------------------------------------ 加 / 删 / 存

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
        pickOne("加一路相机", labels.toArray(new String[0]), roles.toArray(new String[0]),
                role -> profile.cameras.add(newCamera(role)));
    }

    private CameraProfile newCamera(String role) {
        CameraProfile camera = new CameraProfile(role);
        camera.preview = StreamSpec.preview(StreamSpec.RESOLUTION_AUTO);
        camera.record = StreamSpec.record(StreamSpec.RESOLUTION_AUTO,
                StreamSpec.FPS_UNLIMITED, "medium", "auto", 1);
        camera.photo = StreamSpec.photo(StreamSpec.RESOLUTION_MAX, 95);
        if (splitsFor(role)) {
            for (int lane = 0; lane < 4; lane++) {
                camera.lanes.add(LaneLayout.cell(lane,
                        (lane % 2) * 0.5f, (lane / 2) * 0.5f, 0.5f, 0.5f));
            }
        } else {
            camera.lanes.add(LaneLayout.cell(-1, 0f, 0f, 1f, 1f));
        }
        return camera;
    }

    private void confirmRemove(CameraProfile camera) {
        new AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                .setTitle("移除 " + roleName(camera.role))
                .setMessage("从这份配置里去掉这一路。它的参数会一起丢掉。")
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    profile.cameras.remove(camera);
                    render();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void confirmReset() {
        new AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                .setTitle("恢复为初值")
                .setMessage("把这份配置改回新建时的样子，你在这里改的会丢掉。")
                .setPositiveButton("恢复", (d, w) -> {
                    profile = store.reset(profile.id);
                    render();
                    toast("已恢复为初值");
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /**
     * 先查数据，再按新配置开一次画面，看清楚了才保存。
     *
     * <p>一份让人没有画面的配置，是没法用「取消」退出来的 —— 所以确认框默认丢弃，
     * 十秒不点就当没改过。</p>
     */
    private void saveWithPreview() {
        List<ProfileValidation.Issue> issues = ProfileValidation.check(profile, capabilities());
        List<ProfileValidation.Issue> warnings = new ArrayList<>();
        for (ProfileValidation.Issue issue : issues) {
            if (issue.blocking) {
                showIssues("这份配置还不能保存", issues);
                return;
            }
            warnings.add(issue);
        }

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
        int[] resolved = resolvedSource(first.role, first.preview);
        Size size = resolved == null ? null : new Size(resolved[0], resolved[1]);
        boolean split = size != null
                && splitsFor(first.role, size.getWidth(), size.getHeight());

        new ProfilePreviewCheck(requireActivity()).run(cameraId, size, split, cellsOf(first), () -> {
            store.save(profile);
            AppLog.i(TAG, "配置已保存:\n" + profile);
            toast("已保存，重启应用后生效");
        });
    }

    /** 把这一路的格子翻译成容器认识的那份，确认画面才和保存之后一致。 */
    private com.kooo.evcam.zeekr.FourLaneContainer.Cell[] cellsOf(CameraProfile camera) {
        if (camera == null || camera.lanes.isEmpty()) {
            return null;
        }
        com.kooo.evcam.zeekr.FourLaneContainer.Cell[] cells =
                new com.kooo.evcam.zeekr.FourLaneContainer.Cell[camera.lanes.size()];
        for (int i = 0; i < cells.length; i++) {
            LaneLayout lane = camera.lanes.get(i);
            com.kooo.evcam.zeekr.FourLaneContainer.Cell cell =
                    new com.kooo.evcam.zeekr.FourLaneContainer.Cell();
            cell.laneIndex = lane.laneIndex;
            cell.x = lane.x;
            cell.y = lane.y;
            cell.width = lane.width;
            cell.height = lane.height;
            cell.rotation = lane.rotation;
            cell.mirrored = lane.mirrored;
            cell.cropTop = lane.cropTop;
            cell.cropBottom = lane.cropBottom;
            cell.cropLeft = lane.cropLeft;
            cell.cropRight = lane.cropRight;
            cell.scaleX = lane.scaleX;
            cell.scaleY = lane.scaleY;
            cell.translateX = lane.translateX;
            cell.translateY = lane.translateY;
            cells[i] = cell;
        }
        return cells;
    }

    private CameraProfile firstEnabled() {
        for (CameraProfile camera : profile.cameras) {
            if (camera.enabled) {
                return camera;
            }
        }
        return null;
    }

    private void showIssues(String title, List<ProfileValidation.Issue> issues) {
        StringBuilder sb = new StringBuilder();
        for (ProfileValidation.Issue issue : issues) {
            sb.append(issue).append('\n');
        }
        new AlertDialog.Builder(requireContext(), R.style.AlertDialogTheme)
                .setTitle(title)
                .setMessage(sb.length() == 0 ? "（没有更多信息）" : sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private ProfileValidation.Capabilities capabilities() {
        return new ProfileValidation.Capabilities() {
            @Override
            public int[][] declaredSizes(String role) {
                List<int[]> sizes = ProfileEditorFragment.this.declaredSizes(role);
                return sizes.isEmpty() ? null : sizes.toArray(new int[0][]);
            }

            @Override
            public boolean splits(String role, int width, int height) {
                return splitsFor(role, width, height);
            }
        };
    }

    // ------------------------------------------------------------------ 相机信息

    /**
     * 这一路拆不拆。
     *
     * <p>环视那一路在 {@link StreamLayoutTable} 里，也就是<b>一定</b>拆 —— 和分辨率无关，
     * 也和「这次相机开没开起来」无关。以前这里还要求表里已经登记了相机 id，
     * 于是相机还没起来时进设置，编辑器会把环视说成不拆的，连每一格都不给编。</p>
     */
    private boolean splitsFor(String role) {
        return CameraProfile.ROLE_COMPOSITE.equals(role);
    }

    private boolean splitsFor(String role, int width, int height) {
        String cameraId = CameraProfile.ROLE_COMPOSITE.equals(role)
                ? StreamLayoutTable.compositeCameraId() : null;
        return StreamLayoutTable.stackingFor(cameraId, width, height)
                != com.kooo.evcam.zeekr.CompositeStreamGeometry.Stacking.NOT_COMPOSITE;
    }

    private List<int[]> declaredSizes(String role) {
        List<int[]> out = new ArrayList<>();
        String cameraId = cameraIdFor(role);
        if (cameraId == null || cameraManager == null) {
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
            if (cameraManager == null) {
                return null;
            }
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
            AppLog.w(TAG, "读不到相机列表: " + e);
            return null;
        }
    }

    private String cameraSummary(String role) {
        String cameraId = cameraIdFor(role);
        if (cameraId == null) {
            return "（找不到对应的相机）";
        }
        List<int[]> sizes = declaredSizes(role);
        String largest = sizes.isEmpty() ? "尺寸未知" : sizes.get(0)[0] + "x" + sizes.get(0)[1];
        return "相机 " + cameraId + " · 最大 " + largest
                + (splitsFor(role) ? " · 拆四格" : "");
    }

    private String roleName(String role) {
        if (CameraProfile.ROLE_COMPOSITE.equals(role)) {
            return getString(R.string.slot_surround);
        }
        if (CameraProfile.ROLE_CABIN_1.equals(role)) {
            return getString(R.string.slot_cabin_1);
        }
        return getString(R.string.slot_cabin_2);
    }

    // ------------------------------------------------------------------ 小工具

    private PreferenceCategory category(PreferenceScreen screen, Context context, String title) {
        PreferenceCategory category = new PreferenceCategory(context);
        category.setTitle(title);
        screen.addPreference(category);
        return category;
    }

    private void click(PreferenceCategory parent, Context context, String title,
                       String summary, Runnable action) {
        Preference preference = new Preference(context);
        preference.setPersistent(false);
        preference.setTitle(title);
        if (summary != null) {
            preference.setSummary(summary);
        }
        preference.setOnPreferenceClickListener(p -> {
            action.run();
            return true;
        });
        parent.addPreference(preference);
    }

    private void toast(String text) {
        if (getContext() != null) {
            Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
        }
    }
}

