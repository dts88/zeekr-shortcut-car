package com.kooo.evcam.settings;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
import com.kooo.evcam.camera.StorageBudget;
import com.kooo.evcam.camera.TargetBitrate;
import com.kooo.evcam.profile.CameraProfile;
import com.kooo.evcam.profile.LaneLayout;
import com.kooo.evcam.profile.Profile;
import com.kooo.evcam.profile.ProfileResolution;
import com.kooo.evcam.profile.ProfileSizes;
import com.kooo.evcam.profile.ProfileStore;
import com.kooo.evcam.profile.ProfileValidation;
import com.kooo.evcam.profile.QualityPreset;
import com.kooo.evcam.profile.StreamSpec;
import com.kooo.evcam.ui.CamDialogs;
import com.kooo.evcam.ui.SegmentedBar;
import com.kooo.evcam.zeekr.CompositeStreamGeometry;
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

    /** 这台车就这三路，顺序也固定。 */
    private static final String[] ROLES = {CameraProfile.ROLE_COMPOSITE,
            CameraProfile.ROLE_CABIN_1, CameraProfile.ROLE_CABIN_2};

    private ProfileStore store;
    Profile profile;
    private CameraManager cameraManager;
    private LinearLayout presetRow;
    private LinearLayout cameraRow;
    private LinearLayout detailBox;
    private TextView budgetLine;
    private TextView issueLine;
    private TextView camerasTitle;
    private String selectedRole;
    private int selectedLane;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = requireContext();
        store = new ProfileStore(context);
        profile = store.current();
        ensureAllRoles();
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        if (savedInstanceState != null) {
            selectedRole = savedInstanceState.getString(STATE_ROLE);
            selectedLane = savedInstanceState.getInt(STATE_LANE);
        }
    }

    /**
     * 三路永远都在配置里。
     *
     * <p>「加一路相机」这个概念没了：“加”不是一件真实发生的事，
     * 开和关才是。老配置里缺的那几路在这里补齐，默认关着 ——
     * 关着的一路不开相机、不占空间，和它不在没有区别。</p>
     */
    private void ensureAllRoles() {
        for (String role : ROLES) {
            if (profile.camera(role) == null) {
                CameraProfile camera = newCamera(role);
                camera.enabled = false;
                profile.cameras.add(camera);
            }
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
        presetRow = view.findViewById(R.id.editor_presets);
        cameraRow = view.findViewById(R.id.editor_cameras);
        detailBox = view.findViewById(R.id.editor_detail);
        budgetLine = view.findViewById(R.id.editor_budget);
        issueLine = view.findViewById(R.id.editor_issues);
        camerasTitle = view.findViewById(R.id.editor_cameras_title);
        view.findViewById(R.id.editor_open_lanes).setOnClickListener(v -> openLanes());
        view.findViewById(R.id.editor_reset).setOnClickListener(v -> confirmReset());
        refresh();
    }

    /**
     * 摆位是另一件事，走另一个界面：这里管「录成什么样」，那里管「摆在哪」。
     *
     * <p>那一页会把这一页换下去，但这个实例还在返回栈里活着，
     * 手里那份没存盘的配置也还在 —— 摆位改的就是它（按类名 tag 找回来）。</p>
     */
    private void openLanes() {
        ProfileEditorPane lanes = ProfileEditorPane.lanes();
        String title = getString(R.string.editor_open_lanes);
        if (getParentFragment() instanceof SettingsShellFragment) {
            ((SettingsShellFragment) getParentFragment()).openDetail(lanes, title);
            return;
        }
        if (getActivity() == null) {
            return;
        }
        getActivity().getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, lanes, lanes.getClass().getName())
                .addToBackStack(title)
                .commit();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_ROLE, selectedRole);
        outState.putInt(STATE_LANE, selectedLane);
    }

    // ------------------------------------------------------------------ 选中

    /**
     * 改了就存，然后重搭。
     *
     * <h3>为什么没有保存键</h3>
     *
     * <p>一个只有按了「保存」才生效的界面，等于让人全程在
     * 「看到的」和「生效的」之间猜。改坏了还有「重置」。</p>
     *
     * <p>不能写在 {@link #refresh()} 里：摆位那一页把这一页换下去了，
     * 那时 refresh 没有 view 可搭会直接返回 —— 而摆位同样要存。</p>
     */
    void commit() {
        store.save(profile);
        refresh();
    }

    /** 按当前状态整页重搭。 */
    void refresh() {
        if (presetRow == null || getContext() == null) {
            return;
        }
        renderPresets();
        renderCameras();
        renderDetail();
        renderBudget();
        // 摆位那一页不在这里重搭：它把这一页换下去了，两者不会同时在屏上。
        // 它改完自己重搭，退回来时这一页的 onViewCreated 会再叫一次 refresh。
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 进摆位时这一页被换下去，view 摧了人还在。不放手的话，
        // 持的就是一棵已经死了的控件树，而且 refresh() 会往里面白写
        presetRow = null;
        cameraRow = null;
        detailBox = null;
        budgetLine = null;
        issueLine = null;
        camerasTitle = null;
    }

    // ------------------------------------------------------------------ 三档

    private void renderPresets() {
        Context context = requireContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        QualityPreset current = QualityPreset.of(profile);
        long free = freeBytes();
        presetRow.removeAllViews();
        for (QualityPreset preset : QualityPreset.values()) {
            View card = inflater.inflate(R.layout.item_quality_preset, presetRow, false);
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (presetRow.getChildCount() > 0) {
                params.setMarginStart(dp(12));
            }
            card.setLayoutParams(params);
            card.setBackgroundResource(preset == current
                    ? R.drawable.bg_editor_card_on : R.drawable.bg_editor_card);
            ((TextView) card.findViewById(R.id.preset_name)).setText(presetName(preset));
            ((TextView) card.findViewById(R.id.preset_note)).setText(presetNote(preset));
            card.findViewById(R.id.preset_current)
                    .setVisibility(preset == current ? View.VISIBLE : View.GONE);

            long perHour = bytesPerHourFor(preset);
            ((TextView) card.findViewById(R.id.preset_gb)).setText(String.format(Locale.US,
                    "%.1f", StorageBudget.gigabytesPerHour(perHour)));
            TextView hours = card.findViewById(R.id.preset_hours);
            TextView hoursUnit = card.findViewById(R.id.preset_hours_unit);
            float canRecord = StorageBudget.hours(free, perHour);
            if (canRecord > 0f) {
                hours.setText(String.format(Locale.US, "%.0f", canRecord));
                hoursUnit.setText(R.string.editor_quality_hours_unit);
                hours.setVisibility(View.VISIBLE);
                hoursUnit.setVisibility(View.VISIBLE);
            } else {
                // 没插 U 盘就不编一个数：这几行字的全部意义是让人敢照着它做决定
                hours.setVisibility(View.GONE);
                hoursUnit.setVisibility(View.GONE);
            }
            card.setOnClickListener(v -> {
                preset.applyTo(profile);
                commit();
            });
            presetRow.addView(card);
        }
    }

    private String presetName(QualityPreset preset) {
        switch (preset) {
            case SAVE_SPACE: return getString(R.string.editor_quality_space);
            case SHARPEST: return getString(R.string.editor_quality_sharp);
            default: return getString(R.string.editor_quality_balanced);
        }
    }

    private String presetNote(QualityPreset preset) {
        switch (preset) {
            case SAVE_SPACE: return getString(R.string.editor_quality_space_note);
            case SHARPEST: return getString(R.string.editor_quality_sharp_note);
            default: return getString(R.string.editor_quality_balanced_note);
        }
    }

    // ------------------------------------------------------------------ 相机

    private void renderCameras() {
        Context context = requireContext();
        LayoutInflater inflater = LayoutInflater.from(context);
        QualityPreset current = QualityPreset.of(profile);
        int on = 0;
        for (CameraProfile camera : profile.cameras) {
            if (camera.enabled) {
                on++;
            }
        }
        camerasTitle.setText(getString(R.string.editor_cameras_title, on));
        cameraRow.removeAllViews();
        CameraProfile selected = selectedCamera();
        for (String role : ROLES) {
            CameraProfile camera = profile.camera(role);
            if (camera == null) {
                continue;   // ensureAllRoles 之后不会发生，保险起见
            }
            View card = inflater.inflate(R.layout.item_camera_card, cameraRow, false);
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (cameraRow.getChildCount() > 0) {
                params.setMarginStart(dp(12));
            }
            card.setLayoutParams(params);
            card.setBackgroundResource(camera == selected
                    ? R.drawable.bg_editor_card_on : R.drawable.bg_editor_card);
            ((TextView) card.findViewById(R.id.camera_name)).setText(roleName(camera.role));
            ((TextView) card.findViewById(R.id.camera_summary)).setText(recordSummary(camera));
            // 关着的那张把字压暗：三张卡永远都在，开没开得一眼看得出来。
            // 压的只是字，不是整张卡 —— 开关本身得看着是能按的
            float dim = camera.enabled ? 1f : 0.45f;
            card.findViewById(R.id.camera_name).setAlpha(dim);
            card.findViewById(R.id.camera_summary).setAlpha(dim);
            // 「我选了均衡，但后座舱不是」—— 这件事必须看得见
            boolean tuned = current != null && !current.matches(camera.record);
            card.findViewById(R.id.camera_tuned)
                    .setVisibility(tuned ? View.VISIBLE : View.GONE);
            android.widget.CompoundButton toggle = card.findViewById(R.id.camera_enabled);
            toggle.setChecked(camera.enabled);
            toggle.setOnClickListener(v -> {
                camera.enabled = toggle.isChecked();
                // 开一路就是要改它：顺手选中，细调框直接就是它的
                if (camera.enabled) {
                    selectedRole = camera.role;
                    selectedLane = 0;
                }
                commit();
            });
            card.setOnClickListener(v -> selectCamera(camera.role));
            cameraRow.addView(card);
        }
    }

    /** 这一路录成什么样，一行写完。 */
    private String recordSummary(CameraProfile camera) {
        int bitrate = estimatedBitrate(camera, camera.record.fps, camera.record.bitrate);
        int[] size = resolvedSource(camera.role, camera.record);
        String landing = size == null ? getString(R.string.editor_resolved_by_camera)
                : landingSize(camera, size);
        return landing + " · " + fpsLabel(camera.record.fps)
                + " · " + TargetBitrate.format(bitrate);
    }

    private String landingSize(CameraProfile camera, int[] source) {
        String compositeId = StreamLayoutTable.compositeCameraId();
        boolean grid = camera.record != null && camera.record.grid;
        EncodeSize landing = EncodeSize.forSource(compositeId, source[0], source[1], grid);
        return landing.width + "×" + landing.height;
    }

    // ------------------------------------------------------------------ 细调

    private void renderDetail() {
        Context context = requireContext();
        detailBox.removeAllViews();
        CameraProfile camera = selectedCamera();
        if (camera == null) {
            detailBox.setVisibility(View.GONE);
            return;
        }
        detailBox.setVisibility(View.VISIBLE);
        StreamSpec record = camera.record;

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setText(getString(R.string.editor_detail_title, roleName(camera.role)));
        title.setTextAppearance(R.style.TextAppearance_Cam_Row);
        title.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.energy_text));
        header.addView(title);
        TextView which = new TextView(context);
        which.setText("  " + cameraSummary(camera.role));
        which.setTextAppearance(R.style.TextAppearance_Cam_Caption);
        which.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_secondary));
        LinearLayout.LayoutParams grow =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        which.setLayoutParams(grow);
        header.addView(which);
        detailBox.addView(header);

        knob(context, R.string.editor_knob_fps,
                new String[]{getString(R.string.editor_fps_unlimited), "30", "24", "20", "15", "10"},
                new String[]{StreamSpec.FPS_UNLIMITED, "30", "24", "20", "15", "10"},
                record.fps, value -> { record.fps = value; commit(); });

        knob(context, R.string.editor_knob_bitrate,
                new String[]{getString(R.string.editor_very_low), getString(R.string.editor_low),
                        getString(R.string.editor_medium), getString(R.string.editor_high)},
                new String[]{StreamSpec.BITRATE_VERY_LOW, StreamSpec.BITRATE_LOW,
                        StreamSpec.BITRATE_MEDIUM, StreamSpec.BITRATE_HIGH},
                record.bitrate, value -> { record.bitrate = value; commit(); });

        knob(context, R.string.editor_knob_segment,
                new String[]{"1", "3", "5", "10"}, new String[]{"1", "3", "5", "10"},
                String.valueOf(record.segmentMinutes),
                value -> { record.segmentMinutes = Integer.parseInt(value); commit(); });

        knob(context, R.string.editor_knob_photo,
                new String[]{getString(R.string.editor_quality_default), "90", "80", "70"},
                new String[]{"95", "90", "80", "70"},
                String.valueOf(camera.photo.jpegQuality),
                value -> { camera.photo.jpegQuality = Integer.parseInt(value); commit(); });

        knob(context, R.string.editor_knob_codec,
                new String[]{getString(R.string.editor_codec_auto), "H.264"},
                new String[]{"auto", "h264"},
                record.codec, value -> { record.codec = value; commit(); });

        // 分辨率和拍照参数不常改，收在一行里，点开还是原来那套选择框
        TextView more = new TextView(context);
        more.setText(getString(R.string.editor_detail_more,
                describeStream(camera, camera.record)));
        more.setTextAppearance(R.style.TextAppearance_Cam_Caption);
        more.setTextColor(androidx.core.content.ContextCompat.getColor(context, R.color.text_secondary));
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        moreParams.topMargin = dp(14);
        more.setLayoutParams(moreParams);
        more.setOnClickListener(v -> pickResolution(camera, camera.record));
        detailBox.addView(more);
    }

    private void knob(Context context, int labelRes, String[] labels, String[] values,
                      String current, SegmentedBar.OnPick onPick) {
        View row = LayoutInflater.from(context).inflate(R.layout.item_knob_row, detailBox, false);
        ((TextView) row.findViewById(R.id.knob_label)).setText(labelRes);
        ((SegmentedBar) row.findViewById(R.id.knob_bar)).bind(labels, values, current, onPick);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(14);
        row.setLayoutParams(params);
        detailBox.addView(row);
    }

    // ------------------------------------------------------------------ 代价

    private void renderBudget() {
        long perHour = bytesPerHourFor(null);
        long free = freeBytes();
        float hours = StorageBudget.hours(free, perHour);
        String size = String.format(Locale.US, "%.1f", StorageBudget.gigabytesPerHour(perHour));
        budgetLine.setText(hours > 0f
                ? getString(R.string.editor_budget_with_space, size,
                        String.format(Locale.US, "%.0f", hours))
                : getString(R.string.editor_budget, size));
        renderIssues();
    }

    /**
     * 这份配置现在有什么毛病。
     *
     * <p>以前这些是按下保存时弹出来的。没了保存键就没了那一刻，
     * 所以改成一直摆在这里 —— 有问题的时候看得见，没问题的时候不占地方。</p>
     */
    private void renderIssues() {
        StringBuilder sb = new StringBuilder();
        for (ProfileValidation.Issue issue : ProfileValidation.check(profile, capabilities())) {
            if (sb.length() > 0) {
                sb.append('
');
            }
            sb.append(describe(issue));
        }
        issueLine.setText(sb);
        issueLine.setVisibility(sb.length() == 0 ? View.GONE : View.VISIBLE);
    }

    /**
     * 这份配置一小时落盘多少字节。
     *
     * @param preset 不为 null 时按这一档算（三张卡上那两个数），null 表示按现在的设置算
     */
    private long bytesPerHourFor(QualityPreset preset) {
        List<Integer> rates = new ArrayList<>();
        for (CameraProfile camera : profile.cameras) {
            if (!camera.enabled) {
                continue;
            }
            String fps = preset == null ? camera.record.fps : preset.fps;
            String bitrate = preset == null ? camera.record.bitrate : preset.bitrate;
            rates.add(estimatedBitrate(camera, fps, bitrate));
        }
        int[] bits = new int[rates.size()];
        for (int i = 0; i < bits.length; i++) {
            bits[i] = rates.get(i);
        }
        return StorageBudget.bytesPerHour(bits);
    }

    /**
     * 这一路按这组参数会配多少码率。
     *
     * <p>走的是录制那边同一个 {@link TargetBitrate} —— 界面写的数和实际配下去的
     * 必须是同一个，不能在这里另算一遍。</p>
     */
    private int estimatedBitrate(CameraProfile camera, String fps, String bitrate) {
        int[] source = resolvedSource(camera.role, camera.record);
        if (source == null) {
            return 0;
        }
        EncodeSize size = EncodeSize.forSource(StreamLayoutTable.compositeCameraId(),
                source[0], source[1], camera.record.grid);
        int max = com.kooo.evcam.camera.CameraCapabilities.declaredMaxFps();
        int nominal = com.kooo.evcam.profile.RecordSpecs.nominal(fps,
                max > 0 ? max : AppConfig.RECORDER_MAX_FPS);
        boolean h264 = com.kooo.evcam.profile.RecordSpecs.forceH264(camera.record.codec)
                || new AppConfig(requireContext()).isForceH264Encoding();
        return TargetBitrate.compute(
                com.kooo.evcam.profile.RecordSpecs.qualityLevel(bitrate),
                size.width, size.height, nominal, !h264);
    }

    private long freeBytes() {
        java.io.File sdCard =
                com.kooo.evcam.StorageHelper.getExternalSdCardRoot(requireContext());
        return sdCard == null ? 0 : com.kooo.evcam.StorageHelper.getAvailableSpace(sdCard);
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
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
            refresh();   // 只是选中变了，没改配置
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
        if (CameraProfile.ROLE_COMPOSITE.equals(role)) {
            return max;
        }
        // 座舱那两路的 auto：拍照用声明的最大值，预览按「最接近 1280×800」
        // 挑，录制跟着预览走。这三条以前在这里一律返回 null，于是卡上的码率
        // 写成 0 kbps，「还能录多久」也完全不受这两路开关的影响 ——
        // 而这个数是算得出来的，算得出来就不能写 0。
        CameraProfile camera = profile.camera(role);
        if (camera == null) {
            return previewDefaultSize(role);
        }
        if (spec == camera.photo) {
            return max;
        }
        if (spec != camera.preview && camera.preview != null) {
            return resolvedSource(role, camera.preview);
        }
        return previewDefaultSize(role);
    }

    /**
     * 配置里写 auto 时，预览会挑中的那个尺寸。
     *
     * <p>和 {@code SingleCamera.chooseOptimalSize} 同一条规则：先找 1280×800，
     * 找不到就找最接近的。两边必须一致 —— 界面上写的数就是实际配下去的数。</p>
     */
    private int[] previewDefaultSize(String role) {
        int[] best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (int[] size : declaredPreviewSizes(role)) {
            if (size[0] == 1280 && size[1] == 800) {
                return size;
            }
            int diff = Math.abs(1280 - size[0]) + Math.abs(800 - size[1]);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = size;
            }
        }
        return best;
    }

    /** 预览走的是 PRIVATE / SurfaceTexture 那一份声明，不是 JPEG 那一份。 */
    private List<int[]> declaredPreviewSizes(String role) {
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
            Size[] sizes = map.getOutputSizes(ImageFormat.PRIVATE);
            if (sizes == null || sizes.length == 0) {
                sizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
            }
            if (sizes == null) {
                return out;
            }
            for (Size size : sizes) {
                out.add(new int[]{size.getWidth(), size.getHeight()});
            }
        } catch (Exception e) {
            AppLog.w(TAG, "读不到 " + role + " 的预览尺寸列表: " + e);
        }
        return out;
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



    void pickCodec(StreamSpec spec) {
        pickOne(getString(R.string.editor_record_codec),
                new String[]{getString(R.string.editor_codec_auto), "H.264"},
                new String[]{"auto", "h264"},
                value -> spec.codec = value);
    }



    private interface Chosen {
        void set(String value);
    }

    private void pickOne(String title, String[] labels, String[] values, Chosen chosen) {
        CamDialogs.show(new MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(title)
                .setItems(labels, (d, which) -> {
                    chosen.set(values[which]);
                    commit();
                })
                .setNegativeButton(R.string.action_cancel, null));
    }

    // ------------------------------------------------------------------ 加 / 删 / 存

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

    void confirmReset() {
        CamDialogs.showDestructive(new MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.editor_reset)
                .setMessage(R.string.editor_reset_msg)
                .setPositiveButton(R.string.editor_reset_ok, (d, w) -> {
                    profile = store.reset(profile.id);
                    ensureAllRoles();
                    commit();
                    toast(getString(R.string.editor_reset_done));
                })
                .setNegativeButton(R.string.action_cancel, null));
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
