package com.kooo.evcam.settings;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.text.InputType;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
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
import com.kooo.evcam.ui.CamDialogs;
import com.kooo.evcam.zeekr.CompositeStreamGeometry;
import com.kooo.evcam.zeekr.FourLaneContainer;
import com.kooo.evcam.zeekr.StreamLayoutTable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 视频流配置编辑：哪几路相机、每一路的三条流、以及拆出来的每一格怎么摆。
 *
 * <h3>两栏：先选一个，再改它</h3>
 *
 * <p>以前是一列竖排：三路相机 × 三条流，再加环视四格 × 五行，二十多行一路滚下去，
 * 改的是哪一格只能看分组标题。现在左栏选相机、看它的三条流，右栏画出这一路的格子图，
 * 点一格改那一格 —— 一次只摆出正在改的那一路、那一格（两栏见 {@link ProfileEditorPane}）。</p>
 *
 * <p>这个类是两栏共同的「后台」：持有配置和当前的选中，所有取值、选择、校验和保存都在这里。
 * 任何改动之后调 {@link #refresh()}，两栏一起按新状态重搭。</p>
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
public class ProfileEditorFragment extends Fragment {

    private static final String TAG = "ProfileEditor";
    private static final String STATE_ROLE = "role";
    private static final String STATE_LANE = "lane";

    private ProfileStore store;
    Profile profile;
    private CameraManager cameraManager;
    private String selectedRole;
    private int selectedLane;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = requireContext();
        store = new ProfileStore(context);
        profile = store.current();
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (savedInstanceState != null) {
            selectedRole = savedInstanceState.getString(STATE_ROLE);
            selectedLane = savedInstanceState.getInt(STATE_LANE);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile_editor, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        if (getChildFragmentManager().findFragmentById(R.id.editor_streams) == null) {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.editor_streams, ProfileEditorPane.streams())
                    .replace(R.id.editor_lanes, ProfileEditorPane.lanes())
                    .commit();
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_ROLE, selectedRole);
        outState.putInt(STATE_LANE, selectedLane);
    }

    // ------------------------------------------------------------------ 选中

    /** 两栏按当前状态一起重搭。 */
    void refresh() {
        for (Fragment child : getChildFragmentManager().getFragments()) {
            if (child instanceof ProfileEditorPane) {
                ((ProfileEditorPane) child).render();
            }
        }
    }

    /**
     * 编辑器顶上显示的配置名。
     *
     * <p>预设的那三份按 id 显示本地化的名字：存进配置里的名字是迁移时写的中文，
     * 英文界面下照抄它就是一行中文。自己起过名的配置照原样显示。</p>
     */
    String profileName() {
        if (Profile.PRESET_COMPOSITE.equals(profile.id)) {
            return getString(R.string.opt_model_zeekr);
        }
        if (Profile.PRESET_COMPOSITE_MULTI.equals(profile.id)) {
            return getString(R.string.opt_model_zeekr_multi);
        }
        if (Profile.PRESET_CUSTOM.equals(profile.id)) {
            return getString(R.string.opt_model_custom);
        }
        return profile.name.isEmpty() ? profile.id : profile.name;
    }

    /** 正在改的那一路。刚删掉的、或者还没选过，就落到第一路上。 */
    @Nullable
    CameraProfile selectedCamera() {
        if (profile.cameras.isEmpty()) {
            return null;
        }
        CameraProfile camera = selectedRole == null ? null : profile.camera(selectedRole);
        if (camera == null) {
            camera = profile.cameras.get(0);
            selectedRole = camera.role;
            selectedLane = 0;
        }
        return camera;
    }

    /** 正在改的那一格（这一路 lanes 的下标）；这一路没有格子时是 -1。 */
    int selectedLaneIndex(@Nullable CameraProfile camera) {
        if (camera == null || camera.lanes.isEmpty()) {
            return -1;
        }
        if (selectedLane < 0 || selectedLane >= camera.lanes.size()) {
            selectedLane = 0;
        }
        return selectedLane;
    }

    void selectCamera(String role) {
        if (!role.equals(selectedRole)) {
            selectedRole = role;
            selectedLane = 0;
            refresh();
        }
    }

    void selectLane(int index) {
        if (index != selectedLane) {
            selectedLane = index;
            refresh();
        }
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
    String describeStream(CameraProfile camera, StreamSpec spec) {
        StringBuilder sb = new StringBuilder(resolutionLabel(camera.role, spec));
        if (spec == camera.photo && !new AppConfig(requireContext()).isPhotoViaJpegEnabled()) {
            // 关着「拍照走图片通道」时拍照是抓预览画面，这一项根本不参与
            return sb + getString(R.string.editor_photo_off);
        }
        int[] source = resolvedSource(camera.role, spec);
        if (source == null || !splitsFor(camera.role)) {
            return sb.toString();
        }
        sb.append(" · ").append(getString(R.string.editor_per_cell,
                source[0] + "×" + (source[1] / 4)));
        // 落盘尺寸要走拆分几何，而几何认的是相机 id：相机还没起来时算不出来，
        // 那就不写 —— 编一个数比不写更糟
        if (spec != camera.preview && StreamLayoutTable.compositeCameraId() != null) {
            EncodeSize landing = EncodeSize.forSource(
                    StreamLayoutTable.compositeCameraId(), source[0], source[1],
                    camera.record != null && camera.record.grid);
            sb.append(" · ").append(getString(R.string.editor_landing,
                    landing.width + "×" + landing.height));
        }
        return sb.toString();
    }

    /** 「自动 → 1280x5140」这种写法：意图在前，解出来的数在后。 */
    private String resolutionLabel(String role, StreamSpec spec) {
        if (ProfileResolution.parse(spec.resolution) != null) {
            return spec.resolution;
        }
        String word = getString(StreamSpec.RESOLUTION_MAX.equals(spec.resolution)
                ? R.string.editor_max : R.string.editor_auto);
        return getString(R.string.editor_resolved, word, resolvedText(role, spec.resolution));
    }

    /** 「自动」「最大」在这一路解出来是多少；解不出来就说清楚是谁决定的，不编一个数。 */
    private String resolvedText(String role, String intent) {
        StreamSpec probe = new StreamSpec();
        probe.resolution = intent;
        int[] resolved = resolvedSource(role, probe);
        if (resolved == null) {
            return getString(R.string.editor_resolved_by_camera);
        }
        return resolved[0] + "x" + resolved[1];
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

    String fpsLabel(String fps) {
        return StreamSpec.FPS_UNLIMITED.equals(fps)
                ? getString(R.string.editor_fps_unlimited)
                : getString(R.string.editor_fps_cap, fps);
    }

    String bitrateLabel(String bitrate) {
        if (StreamSpec.BITRATE_VERY_LOW.equals(bitrate)) {
            return getString(R.string.editor_very_low);
        }
        if (StreamSpec.BITRATE_LOW.equals(bitrate)) {
            return getString(R.string.editor_low);
        }
        if (StreamSpec.BITRATE_HIGH.equals(bitrate)) {
            return getString(R.string.editor_high);
        }
        if (StreamSpec.BITRATE_MEDIUM.equals(bitrate)) {
            return getString(R.string.editor_medium);
        }
        return getString(R.string.editor_bitrate_auto);
    }

    String codecLabel(String codec) {
        return "h264".equals(codec) ? "H.264" : getString(R.string.editor_codec_auto);
    }

    /** 小数统一两位、统一用点：不跟着系统语言变成逗号。 */
    static String num(float value) {
        return String.format(Locale.US, "%.2f", value);
    }

    // ------------------------------------------------------------------ 选择

    void pickResolution(CameraProfile camera, StreamSpec spec) {
        List<String> values = new ArrayList<>();
        List<String> labels = new ArrayList<>();

        values.add(StreamSpec.RESOLUTION_AUTO);
        labels.add(getString(R.string.editor_resolved, getString(R.string.editor_auto),
                resolvedText(camera.role, StreamSpec.RESOLUTION_AUTO)));
        values.add(StreamSpec.RESOLUTION_MAX);
        labels.add(getString(R.string.editor_resolved, getString(R.string.editor_max),
                resolvedText(camera.role, StreamSpec.RESOLUTION_MAX)));

        boolean grid = camera.record != null && camera.record.grid;
        String compositeId = StreamLayoutTable.compositeCameraId();
        for (int[] size : declaredSizes(camera.role)) {
            values.add(size[0] + "x" + size[1]);
            if (!splitsFor(camera.role)) {
                labels.add(size[0] + "x" + size[1]);
                continue;
            }
            String text = size[0] + "x" + size[1] + "   "
                    + getString(R.string.editor_per_cell, size[0] + "×" + (size[1] / 4));
            if (compositeId != null) {
                EncodeSize landing = EncodeSize.forSource(compositeId, size[0], size[1], grid);
                text += " · " + getString(R.string.editor_landing,
                        landing.width + "×" + landing.height);
            }
            labels.add(text);
        }
        pickOne(getString(R.string.editor_lane_group, roleName(camera.role),
                        getString(R.string.editor_resolution)),
                labels.toArray(new String[0]), values.toArray(new String[0]),
                value -> spec.resolution = value);
    }

    void pickFps(StreamSpec spec) {
        pickOne(getString(R.string.editor_record_fps),
                new String[]{getString(R.string.editor_fps_unlimited),
                        "30 fps", "24 fps", "20 fps", "15 fps", "10 fps"},
                new String[]{StreamSpec.FPS_UNLIMITED, "30", "24", "20", "15", "10"},
                value -> spec.fps = value);
    }

    void pickBitrate(StreamSpec spec) {
        pickOne(getString(R.string.editor_record_bitrate),
                new String[]{getString(R.string.editor_bitrate_auto),
                        getString(R.string.editor_very_low), getString(R.string.editor_low),
                        getString(R.string.editor_medium), getString(R.string.editor_high)},
                new String[]{StreamSpec.BITRATE_AUTO, StreamSpec.BITRATE_VERY_LOW,
                        StreamSpec.BITRATE_LOW, StreamSpec.BITRATE_MEDIUM,
                        StreamSpec.BITRATE_HIGH},
                value -> spec.bitrate = value);
    }

    void pickCodec(StreamSpec spec) {
        pickOne(getString(R.string.editor_record_codec),
                new String[]{getString(R.string.editor_codec_auto), "H.264"},
                new String[]{"auto", "h264"},
                value -> spec.codec = value);
    }

    void pickSegment(StreamSpec spec) {
        int[] minutes = {1, 3, 5, 10};
        String[] labels = new String[minutes.length];
        String[] values = new String[minutes.length];
        for (int i = 0; i < minutes.length; i++) {
            labels[i] = getString(R.string.share_minutes, minutes[i]);
            values[i] = String.valueOf(minutes[i]);
        }
        pickOne(getString(R.string.editor_record_segment), labels, values,
                value -> spec.segmentMinutes = Integer.parseInt(value));
    }

    void pickQuality(StreamSpec spec) {
        pickOne(getString(R.string.editor_photo_quality),
                new String[]{getString(R.string.editor_quality_default), "90", "80", "70"},
                new String[]{"95", "90", "80", "70"},
                value -> spec.jpegQuality = Integer.parseInt(value));
    }

    private interface Chosen {
        void set(String value);
    }

    private void pickOne(String title, String[] labels, String[] values, Chosen chosen) {
        CamDialogs.show(new MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(title)
                .setItems(labels, (d, which) -> {
                    chosen.set(values[which]);
                    refresh();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    interface Numbers {
        void set(float[] values);
    }

    /**
     * 几个小数一起改。
     *
     * <p>位置、裁切、缩放这些都是<b>一组</b>数，一个一个弹窗改会让人对不上 ——
     * 改完宽还要再点一次改高，中间那一下界面已经动过了。</p>
     */
    void editNumbers(String title, String[] labels, float[] current, Numbers onOk) {
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

        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    float[] values = new float[inputs.length];
                    for (int i = 0; i < inputs.length; i++) {
                        values[i] = parseFloat(inputs[i].getText().toString(), current[i]);
                    }
                    onOk.set(values);
                    refresh();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    private static float parseFloat(String text, float fallback) {
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException e) {
            return fallback;   // 输错了就保持原值，不要把它变成 0
        }
    }

    // ------------------------------------------------------------------ 加 / 删 / 存

    void addCamera() {
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
            toast(getString(R.string.editor_all_added));
            return;
        }
        pickOne(getString(R.string.editor_add_camera),
                labels.toArray(new String[0]), roles.toArray(new String[0]),
                role -> {
                    profile.cameras.add(newCamera(role));
                    // 加进来的那一路直接选中：加它就是为了改它
                    selectedRole = role;
                    selectedLane = 0;
                });
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

    void confirmRemove(CameraProfile camera) {
        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(getString(R.string.editor_remove_title, roleName(camera.role)))
                .setMessage(R.string.editor_remove_msg)
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    profile.cameras.remove(camera);
                    refresh();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    void confirmReset() {
        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.editor_reset)
                .setMessage(R.string.editor_reset_msg)
                .setPositiveButton(R.string.editor_reset_ok, (d, w) -> {
                    profile = store.reset(profile.id);
                    refresh();
                    toast(getString(R.string.editor_reset_done));
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    /**
     * 先查数据，再按新配置开一次画面，看清楚了才保存。
     *
     * <p>一份让人没有画面的配置，是没法用「取消」退出来的 —— 所以确认框默认丢弃，
     * 十秒不点就当没改过。</p>
     */
    void saveWithPreview() {
        List<ProfileValidation.Issue> issues = ProfileValidation.check(profile, capabilities());
        List<ProfileValidation.Issue> warnings = new ArrayList<>();
        for (ProfileValidation.Issue issue : issues) {
            if (issue.blocking) {
                showIssues(getString(R.string.editor_cannot_save), issues);
                return;
            }
            warnings.add(issue);
        }

        CameraProfile first = firstEnabled();
        if (first == null) {
            showIssues(getString(R.string.editor_none_enabled), warnings);
            return;
        }
        String cameraId = cameraIdFor(first.role);
        if (cameraId == null) {
            showIssues(getString(R.string.editor_no_camera_for, roleName(first.role)), warnings);
            return;
        }
        int[] resolved = resolvedSource(first.role, first.preview);
        Size size = resolved == null ? null : new Size(resolved[0], resolved[1]);
        boolean split = size != null
                && splitsFor(first.role, size.getWidth(), size.getHeight());

        new ProfilePreviewCheck(requireActivity()).run(cameraId, size, split, cellsOf(first), () -> {
            store.save(profile);
            AppLog.i(TAG, "配置已保存:\n" + profile);
            toast(getString(R.string.editor_saved));
        });
    }

    /** 把这一路的格子翻译成容器认识的那份，确认画面才和保存之后一致。 */
    private FourLaneContainer.Cell[] cellsOf(CameraProfile camera) {
        if (camera == null || camera.lanes.isEmpty()) {
            return null;
        }
        FourLaneContainer.Cell[] cells = new FourLaneContainer.Cell[camera.lanes.size()];
        for (int i = 0; i < cells.length; i++) {
            LaneLayout lane = camera.lanes.get(i);
            FourLaneContainer.Cell cell = new FourLaneContainer.Cell();
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
            sb.append(describe(issue)).append('\n');
        }
        CamDialogs.show(new MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(title)
                .setMessage(sb.length() == 0 ? getString(R.string.editor_no_more_info) : sb.toString())
                .setPositiveButton(android.R.string.ok, null));
    }

    /** 一条问题按当前语言说出来。 */
    private String describe(ProfileValidation.Issue issue) {
        String text;
        switch (issue.kind) {
            case NO_CAMERAS:
                text = getString(R.string.editor_issue_no_cameras);
                break;
            case NONE_ENABLED:
                text = getString(R.string.editor_issue_none_enabled);
                break;
            case UNDECLARED_SIZE:
                text = getString(R.string.editor_issue_undeclared_size, roleName(issue.role),
                        streamName(issue.stream), issue.width, issue.height);
                break;
            case PREVIEW_SPLIT_ONLY:
                text = getString(R.string.editor_issue_preview_split_only, roleName(issue.role));
                break;
            default:
                text = getString(R.string.editor_issue_record_split_only, roleName(issue.role));
                break;
        }
        return (issue.blocking ? "✗ " : "⚠ ") + text;
    }

    private String streamName(ProfileValidation.Issue.Stream stream) {
        switch (stream) {
            case RECORD:
                return getString(R.string.editor_stream_record);
            case PHOTO:
                return getString(R.string.editor_stream_photo);
            default:
                return getString(R.string.editor_stream_preview);
        }
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
    boolean splitsFor(String role) {
        return CameraProfile.ROLE_COMPOSITE.equals(role);
    }

    private boolean splitsFor(String role, int width, int height) {
        String cameraId = CameraProfile.ROLE_COMPOSITE.equals(role)
                ? StreamLayoutTable.compositeCameraId() : null;
        return StreamLayoutTable.stackingFor(cameraId, width, height)
                != CompositeStreamGeometry.Stacking.NOT_COMPOSITE;
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
            Collections.sort(out, (a, b) -> Long.compare(
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

    String cameraSummary(String role) {
        String cameraId = cameraIdFor(role);
        if (cameraId == null) {
            return getString(R.string.editor_camera_missing);
        }
        List<int[]> sizes = declaredSizes(role);
        String largest = sizes.isEmpty()
                ? getString(R.string.editor_size_unknown)
                : sizes.get(0)[0] + "x" + sizes.get(0)[1];
        return getString(R.string.editor_camera_summary, cameraId, largest)
                + (splitsFor(role) ? " · " + getString(R.string.editor_splits) : "");
    }

    String roleName(String role) {
        if (CameraProfile.ROLE_COMPOSITE.equals(role)) {
            return getString(R.string.slot_surround);
        }
        if (CameraProfile.ROLE_CABIN_1.equals(role)) {
            return getString(R.string.slot_cabin_front);
        }
        return getString(R.string.slot_cabin_rear);
    }

    // ------------------------------------------------------------------ 小工具

    private void toast(String text) {
        if (getContext() != null) {
            Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
        }
    }
}
