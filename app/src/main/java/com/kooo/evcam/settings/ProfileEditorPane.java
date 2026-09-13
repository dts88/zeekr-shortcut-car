package com.kooo.evcam.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

import com.kooo.evcam.R;
import com.kooo.evcam.camera.CameraNames;
import com.kooo.evcam.profile.CameraProfile;
import com.kooo.evcam.profile.LaneLayout;

import java.util.ArrayList;

/**
 * 摆位：这一路的格子图，和正在改的那一格（位置、旋转、镜像、画面填充）。
 *
 * <p>从配置编辑里那个「摆位」进来。流参数不在这里 —— 那一页问的是「录成什么样」，
 * 这一页问的是「摆在哪」，两件事分开问，各自都短。</p>
 *
 * <p>数据和所有「怎么取值、怎么选、怎么存」都在 {@link ProfileEditorFragment} 里，
 * 这里只按当前选中的相机和格子搭行。</p>
 */
public class ProfileEditorPane extends PreferenceFragmentCompat {

    private static final String ARG_PANE = "pane";
    private static final String PANE_LANES = "lanes";

    static ProfileEditorPane lanes() {
        return of(PANE_LANES);
    }

    private static ProfileEditorPane of(String pane) {
        ProfileEditorPane fragment = new ProfileEditorPane();
        Bundle args = new Bundle();
        args.putString(ARG_PANE, pane);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
        render();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        PreferenceRows.styleList(this, 20, 16);
    }

    /** 按当前的选中重搭这一栏。 */
    void render() {
        PreferenceScreen screen = getPreferenceScreen();
        if (screen == null || getContext() == null
                || !(getParentFragment() instanceof ProfileEditorFragment)) {
            return;
        }
        ProfileEditorFragment editor = (ProfileEditorFragment) getParentFragment();
        screen.removeAll();
        renderLanes(screen, editor);
        // 刚加进来的行还没交给列表（同步是下一帧），这时套样式正好
        PreferenceRows.apply(screen);
    }

    // ------------------------------------------------------------------ 左栏
    // ------------------------------------------------------------------ 右栏

    private void renderLanes(PreferenceScreen screen, ProfileEditorFragment editor) {
        Context context = requireContext();
        CameraProfile camera = editor.selectedCamera();
        int index = editor.selectedLaneIndex(camera);
        if (camera == null || index < 0) {
            info(screen, context, getString(R.string.editor_no_camera), null);
            return;
        }
        boolean splits = editor.splitsFor(camera.role);
        LaneLayout lane = camera.lanes.get(index);

        if (splits) {
            screen.addPreference(new LanePickerPreference(context, camera.lanes, index,
                    editor::selectLane));
        }

        String laneName = splits && lane.laneIndex >= 0
                ? CameraNames.ofLane(context, lane.laneIndex)
                : getString(R.string.editor_whole_frame);
        PreferenceCategory group = category(screen, context,
                getString(R.string.editor_lane_group, editor.roleName(camera.role), laneName));

        if (!splits) {
            // 旋转、镜像、裁剪、缩放平移这一路现在生效了（0.44.0）；位置和大小还不行
            // —— 那两项要等主界面的版面由配置驱动。说出来，一个改了不生效的选项
            // 比没有更糟。
            info(group, context, getString(R.string.editor_cabin_note_title),
                    getString(R.string.editor_cabin_note_summary));
        }

        if (splits) {
            row(group, context, R.string.editor_position,
                    getString(R.string.editor_position_value, ProfileEditorFragment.num(lane.x),
                            ProfileEditorFragment.num(lane.y), ProfileEditorFragment.num(lane.width),
                            ProfileEditorFragment.num(lane.height)), false,
                    () -> editor.editNumbers(getString(R.string.editor_position_dialog),
                            new String[]{getString(R.string.editor_x), getString(R.string.editor_y),
                                    getString(R.string.editor_w), getString(R.string.editor_h)},
                            new float[]{lane.x, lane.y, lane.width, lane.height},
                            values -> {
                                lane.x = values[0];
                                lane.y = values[1];
                                lane.width = values[2];
                                lane.height = values[3];
                            }));
        }
        row(group, context, R.string.editor_rotation, lane.rotation + "°", true, () -> {
            lane.rotation = (lane.rotation + 90) % 360;
            editor.refresh();
        });

        SwitchPreferenceCompat mirror = new SwitchPreferenceCompat(context);
        mirror.setPersistent(false);
        mirror.setTitle(R.string.editor_mirror);
        mirror.setSummary(R.string.editor_mirror_summary);
        mirror.setChecked(lane.mirrored);
        mirror.setOnPreferenceChangeListener((p, value) -> {
            lane.mirrored = Boolean.TRUE.equals(value);
            editor.refresh();
            return false;
        });
        group.addPreference(mirror);

        row(group, context, R.string.editor_fit, fitName(context, lane.fit), true, () -> {
            // 三档轮着换：点一下换一个，不弹框。和旋转那一行一个手感
            lane.fit = nextFit(lane.fit);
            editor.refresh();
        });

        // 裁剪停用中：一个改了不生效的选项比没有更糟，所以这一行明说
        boolean cropOn = com.kooo.evcam.camera.LaneOrientation.CROP_SUPPORTED;
        Preference cropRow = row(group, context, R.string.editor_crop,
                cropOn ? getString(R.string.editor_crop_value,
                        ProfileEditorFragment.num(lane.cropTop),
                        ProfileEditorFragment.num(lane.cropBottom),
                        ProfileEditorFragment.num(lane.cropLeft),
                        ProfileEditorFragment.num(lane.cropRight))
                        : getString(R.string.editor_crop_off), cropOn,
                () -> editor.editNumbers(getString(R.string.editor_crop_dialog),
                        new String[]{getString(R.string.editor_top), getString(R.string.editor_bottom),
                                getString(R.string.editor_left), getString(R.string.editor_right)},
                        new float[]{lane.cropTop, lane.cropBottom, lane.cropLeft, lane.cropRight},
                        values -> {
                            lane.cropTop = values[0];
                            lane.cropBottom = values[1];
                            lane.cropLeft = values[2];
                            lane.cropRight = values[3];
                        }));
        // 灰掉而不是藏起来：藏起来的话，看到的人不知道这个功能存在过，
        // 也不知道它为什么不在
        cropRow.setEnabled(cropOn);
        row(group, context, R.string.editor_scale,
                getString(R.string.editor_scale_value, ProfileEditorFragment.num(lane.scaleX),
                        ProfileEditorFragment.num(lane.scaleY),
                        ProfileEditorFragment.num(lane.translateX),
                        ProfileEditorFragment.num(lane.translateY)), false,
                () -> editor.editNumbers(getString(R.string.editor_scale_dialog),
                        new String[]{getString(R.string.editor_scale_x),
                                getString(R.string.editor_scale_y),
                                getString(R.string.editor_pan_x), getString(R.string.editor_pan_y)},
                        new float[]{lane.scaleX, lane.scaleY, lane.translateX, lane.translateY},
                        values -> {
                            lane.scaleX = values[0];
                            lane.scaleY = values[1];
                            lane.translateX = values[2];
                            lane.translateY = values[3];
                        }));
    }

    // ------------------------------------------------------------------ 小工具

    private static PreferenceCategory category(PreferenceScreen screen, Context context, String title) {
        PreferenceCategory category = new PreferenceCategory(context);
        category.setTitle(title);
        screen.addPreference(category);
        return category;
    }

    /** 一段说明，不可点。 */
    private static void info(androidx.preference.PreferenceGroup parent, Context context,
                             String title, @Nullable String summary) {
        Preference preference = new Preference(context);
        preference.setPersistent(false);
        preference.setSelectable(false);
        preference.setTitle(title);
        if (summary != null) {
            preference.setSummary(summary);
        }
        parent.addPreference(preference);
    }

    /**
     * 一行可点的设置。
     *
     * @param value summary 是一个短的当前值（「30 fps」「90°」）时为 true：值放行尾
     */
    /** 「适应」和「填充」来回换。 */
    private static String nextFit(String fit) {
        return com.kooo.evcam.profile.LaneLayout.FILL.equals(
                com.kooo.evcam.profile.LaneLayout.normaliseFit(fit))
                ? com.kooo.evcam.profile.LaneLayout.FIT
                : com.kooo.evcam.profile.LaneLayout.FILL;
    }

    private static String fitName(Context context, String fit) {
        String value = com.kooo.evcam.profile.LaneLayout.normaliseFit(fit);
        if (com.kooo.evcam.profile.LaneLayout.FILL.equals(value)) {
            return context.getString(R.string.editor_fit_fill);
        }
        return context.getString(R.string.editor_fit_fit);
    }

    private static Preference row(PreferenceCategory parent, Context context, int title,
                                  @Nullable String summary, boolean value, Runnable action) {
        Preference preference = new Preference(context);
        preference.setPersistent(false);
        preference.setTitle(title);
        if (summary != null) {
            preference.setSummary(summary);
        }
        if (value) {
            PreferenceRows.markValue(preference);
        }
        preference.setOnPreferenceClickListener(p -> {
            action.run();
            return true;
        });
        parent.addPreference(preference);
        return preference;
    }

    /** 左栏里选哪一路相机的那一行：选中的描一圈能量色。 */
    static final class ChoiceRow extends Preference implements PreferenceRows.OwnLayout {

        private final boolean chosen;

        ChoiceRow(Context context, boolean chosen) {
            super(context);
            this.chosen = chosen;
            setLayoutResource(R.layout.pref_row_choice);
            setPersistent(false);
        }

        @Override
        public void onBindViewHolder(@NonNull PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            holder.itemView.setActivated(chosen);
        }
    }
}
