package com.kooo.evcam.settings;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;
import androidx.preference.SwitchPreferenceCompat;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.kooo.evcam.R;
import com.kooo.evcam.camera.CameraNames;
import com.kooo.evcam.profile.CameraProfile;
import com.kooo.evcam.profile.LaneLayout;
import com.kooo.evcam.ui.CamDialogs;

import java.util.ArrayList;
import java.util.Locale;

/**
 * 摆位：这一路的舞台，和正在改的那一格（位置、旋转、镜像、画面填充）。
 *
 * <p>从配置编辑里那个「摆位」进来。流参数不在这里 —— 那一页问的是「录成什么样」，
 * 这一页问的是「摆在哪」，两件事分开问，各自都短。</p>
 *
 * <p>数据和所有「怎么取值、怎么选、怎么存」都在 {@link ProfileEditorFragment} 里，
 * 这里只按当前选中的相机和格子搭行。</p>
 *
 * <h3>配置编辑在哪</h3>
 *
 * <p>这一页是被「替换」进壳的右栏的，所以它的 parent 是<b>壳</b>，
 * 不是配置编辑 —— 配置编辑被换到返回栈里去了，还活着，但不再是任何人的父子。
 * 所以这里按 tag 去 FragmentManager 里把它找回来（{@link #editor()}）。
 * 上一版先问 parent 是不是它，永远不是，于是这一页是空白的。</p>
 *
 * <p>为什么一定要找那个实例：要改的是它手里<b>还没存盘的那份配置</b>。
 * 自己新开一份就是另一份，改完回去一保存，摆位全丢。</p>
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
        if (screen == null || getContext() == null) {
            return;
        }
        ProfileEditorFragment editor = editor();
        screen.removeAll();
        if (editor == null) {
            info(screen, requireContext(), getString(R.string.editor_lanes_lost), null);
        } else {
            renderLanes(screen, editor);
        }
        // 刚加进来的行还没交给列表（同步是下一帧），这时套样式正好
        PreferenceRows.apply(screen);
    }

    /**
     * 拿着那份配置的配置编辑。
     *
     * <p>两种摆法都认：当子 fragment 用时它就是 parent；
     * 当二级界面用时它在返回栈里，按 tag 找。</p>
     */
    @Nullable
    private ProfileEditorFragment editor() {
        if (getParentFragment() instanceof ProfileEditorFragment) {
            return (ProfileEditorFragment) getParentFragment();
        }
        Fragment found = getParentFragmentManager()
                .findFragmentByTag(ProfileEditorFragment.class.getName());
        return found instanceof ProfileEditorFragment ? (ProfileEditorFragment) found : null;
    }

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

        // 进来之前选的是哪一路，进来之后还得看得见，也得能换一路 ——
        // 否则摆完一路要退出去点另一张卡再进来
        if (editor.profile.cameras.size() > 1) {
            for (CameraProfile option : editor.profile.cameras) {
                ChoiceRow pick = new ChoiceRow(context, option.role.equals(camera.role));
                pick.setTitle(editor.roleName(option.role));
                pick.setSummary(editor.splitsFor(option.role)
                        ? getString(R.string.editor_splits)
                        : getString(R.string.editor_whole_frame));
                pick.setOnPreferenceClickListener(p -> {
                    editor.selectCamera(option.role);
                    render();
                    return true;
                });
                screen.addPreference(pick);
            }
        }

        if (splits) {
            // 拖只开给环视的四格：座舱那两路的位置和大小目前还不由配置决定，
            // 拖了也不会变 —— 那就别给那个手势
            screen.addPreference(new LanePickerPreference(context, camera.lanes, index,
                    this::pickLane, moved -> changed()));
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
                    () -> editNumbers(getString(R.string.editor_position_dialog),
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
            changed();
        });

        SwitchPreferenceCompat mirror = new SwitchPreferenceCompat(context);
        mirror.setPersistent(false);
        mirror.setTitle(R.string.editor_mirror);
        mirror.setSummary(R.string.editor_mirror_summary);
        mirror.setChecked(lane.mirrored);
        mirror.setOnPreferenceChangeListener((p, value) -> {
            lane.mirrored = Boolean.TRUE.equals(value);
            changed();
            return false;
        });
        group.addPreference(mirror);

        row(group, context, R.string.editor_fit, fitName(context, lane.fit), true, () -> {
            // 三档轮着换：点一下换一个，不弹框。和旋转那一行一个手感
            lane.fit = nextFit(lane.fit);
            changed();
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
                () -> editNumbers(getString(R.string.editor_crop_dialog),
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
                () -> editNumbers(getString(R.string.editor_scale_dialog),
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

    // ------------------------------------------------------------------ 改几个数

    private interface Numbers {
        void set(float[] values);
    }

    /**
     * 几个小数一起改。
     *
     * <p>位置、裁切、缩放这些都是<b>一组</b>数，一个一个弹窗改会让人对不上 ——
     * 改完宽还要再点一次改高，中间那一下界面已经动过了。</p>
     *
     * <p>用的是<b>这一页的</b> context。原来这段在配置编辑里，
     * 而那一页现在在返回栈上、没有 context，一点就炋。</p>
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

        CamDialogs.show(new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(R.string.action_save, (d, w) -> {
                    float[] values = new float[inputs.length];
                    for (int i = 0; i < inputs.length; i++) {
                        values[i] = parseFloat(inputs[i].getText().toString(), current[i]);
                    }
                    onOk.set(values);
                    changed();
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

    /** 点了另一格：告诉配置编辑，然后自己重搭。 */
    private void pickLane(int index) {
        ProfileEditorFragment editor = editor();
        if (editor != null) {
            editor.selectLane(index);
        }
        render();
    }

    /**
     * 改完了：存盘，这一栏重搭。
     *
     * <p>没有保存键，所以摆位这边也得自己存 —— 拖完不存的话，
     * 这一页改的东西全是白改。</p>
     *
     * <p>配置编辑那一页不用管 —— 它的 view 已经被摧了，退回去的时候
     * {@code onViewCreated} 会再搭一次。改的都是同一份 Profile，不会对不上。</p>
     */
    private void changed() {
        ProfileEditorFragment editor = editor();
        if (editor != null) {
            editor.commit();
        }
        render();
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
