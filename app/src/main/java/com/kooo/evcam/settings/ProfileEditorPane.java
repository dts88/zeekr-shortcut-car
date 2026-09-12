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
 * 配置编辑的一栏。同一个类，两种：
 *
 * <ul>
 *   <li><b>左栏</b>（{@link #streams()}）：选哪一路相机、这一路的三条流、整份配置的操作；</li>
 *   <li><b>右栏</b>（{@link #lanes()}）：这一路的格子图，和正在改的那一格。</li>
 * </ul>
 *
 * <p>数据和所有「怎么取值、怎么选、怎么存」都在 {@link ProfileEditorFragment} 里，
 * 这里只按当前选中的相机和格子搭行。任何一处改动之后两栏一起重搭，
 * 所以左边改了分辨率、右边的格子图不会停在旧样子上。</p>
 */
public class ProfileEditorPane extends PreferenceFragmentCompat {

    private static final String ARG_PANE = "pane";
    private static final String PANE_STREAMS = "streams";
    private static final String PANE_LANES = "lanes";

    static ProfileEditorPane streams() {
        return of(PANE_STREAMS);
    }

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
        if (PANE_LANES.equals(getArguments() == null ? null : getArguments().getString(ARG_PANE))) {
            renderLanes(screen, editor);
        } else {
            renderStreams(screen, editor);
        }
        // 刚加进来的行还没交给列表（同步是下一帧），这时套样式正好
        PreferenceRows.apply(screen);
    }

    // ------------------------------------------------------------------ 左栏

    private void renderStreams(PreferenceScreen screen, ProfileEditorFragment editor) {
        Context context = requireContext();
        info(screen, context, editor.profileName(), getString(R.string.editor_hint));

        PreferenceCategory cameras = category(screen, context, getString(R.string.editor_group_cameras));
        CameraProfile selected = editor.selectedCamera();
        for (CameraProfile camera : new ArrayList<>(editor.profile.cameras)) {
            ChoiceRow row = new ChoiceRow(context, camera == selected);
            row.setTitle(editor.roleName(camera.role));
            row.setSummary(editor.cameraSummary(camera.role)
                    + (camera.enabled ? "" : " · " + getString(R.string.editor_off)));
            row.setOnPreferenceClickListener(p -> {
                editor.selectCamera(camera.role);
                return true;
            });
            cameras.addPreference(row);
        }

        if (selected != null) {
            PreferenceCategory streams = category(screen, context, getString(
                    R.string.editor_lane_group, editor.roleName(selected.role),
                    getString(R.string.editor_group_streams)));

            SwitchPreferenceCompat enabled = new SwitchPreferenceCompat(context);
            enabled.setPersistent(false);
            enabled.setTitle(R.string.editor_enable);
            enabled.setSummary(R.string.editor_enable_summary);
            enabled.setChecked(selected.enabled);
            enabled.setOnPreferenceChangeListener((p, value) -> {
                selected.enabled = Boolean.TRUE.equals(value);
                editor.refresh();
                return false;
            });
            streams.addPreference(enabled);

            row(streams, context, R.string.editor_preview_res,
                    editor.describeStream(selected, selected.preview), false,
                    () -> editor.pickResolution(selected, selected.preview));
            row(streams, context, R.string.editor_record_res,
                    editor.describeStream(selected, selected.record), false,
                    () -> editor.pickResolution(selected, selected.record));
            row(streams, context, R.string.editor_record_fps,
                    editor.fpsLabel(selected.record.fps), true,
                    () -> editor.pickFps(selected.record));
            row(streams, context, R.string.editor_record_bitrate,
                    editor.bitrateLabel(selected.record.bitrate), true,
                    () -> editor.pickBitrate(selected.record));
            row(streams, context, R.string.editor_record_codec,
                    editor.codecLabel(selected.record.codec), true,
                    () -> editor.pickCodec(selected.record));
            row(streams, context, R.string.editor_record_segment,
                    getString(R.string.share_minutes, selected.record.segmentMinutes), true,
                    () -> editor.pickSegment(selected.record));
            row(streams, context, R.string.editor_photo_res,
                    editor.describeStream(selected, selected.photo), false,
                    () -> editor.pickResolution(selected, selected.photo));
            row(streams, context, R.string.editor_photo_quality,
                    String.valueOf(selected.photo.jpegQuality), true,
                    () -> editor.pickQuality(selected.photo));
            row(streams, context, R.string.editor_remove,
                    getString(R.string.editor_remove_summary), false,
                    () -> editor.confirmRemove(selected));
        }

        PreferenceCategory actions = category(screen, context, getString(R.string.editor_group_profile));
        row(actions, context, R.string.editor_add_camera, null, false, editor::addCamera);
        Preference save = row(actions, context, R.string.editor_save,
                getString(R.string.editor_save_summary), false, editor::saveWithPreview);
        PreferenceRows.markPrimary(save);
        row(actions, context, R.string.editor_reset,
                getString(R.string.editor_reset_summary), false, editor::confirmReset);
    }

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

        row(group, context, R.string.editor_crop,
                getString(R.string.editor_crop_value, ProfileEditorFragment.num(lane.cropTop),
                        ProfileEditorFragment.num(lane.cropBottom),
                        ProfileEditorFragment.num(lane.cropLeft),
                        ProfileEditorFragment.num(lane.cropRight)), false,
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
