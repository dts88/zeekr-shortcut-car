/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.setup;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.profile.ProfileStore;
import com.kooo.evcam.profile.QualityPreset;
import com.kooo.evcam.settings.Languages;
import com.kooo.evcam.settings.SettingSpec;
import com.kooo.evcam.settings.SettingsPreferenceFragment;
import com.kooo.evcam.settings.SettingsRegistry;
import com.kooo.evcam.storage.StorageState;
import com.kooo.evcam.ui.Motion;
import com.kooo.evcam.ui.MotionPolicy;

/**
 * 初始设置（ui-spec §十五，项目所有者 2026-11-12）：首次启动按用车的先后问一遍，七步 ——
 * 语言、开始之前、驾驶座位置、U 盘、录制、常用功能、完成。4.0 以前是两个框（语言、方向盘在哪边）加一个说明框。
 *
 * <p>用五个区里的 ① ② ③：① 写「初始设置」、右边「跳过设置」；② 是七步的名字，做过的打勾、现在这一步垫灰底；
 * ③ 是这一步的标题、说明和要选的东西，右下角「上一步」「下一步」。</p>
 *
 * <p><b>不另写一套设置。</b>第五、六步摆的就是设置页那几行（{@link SettingsPreferenceFragment#forRows}）：同一份行、同一份值、
 * 同一份接线，权限也在拨那一行时由它自己去要。语言和驾驶座位置写的是设置里那两项的同一份值。任何一步跳过，没选的照默认值。</p>
 *
 * <p>只走一次（{@link AppConfig#needsSetup}）；「设置 → 通用 → 初始设置」可以再走一遍，值从现在的设置起。
 * 选了别的语言时系统会重建界面：走到哪一步存在状态里，重建以后回到那一步。</p>
 */
public class SetupFragment extends Fragment implements StorageState.Listener {

    /** 所在的界面。 */
    public interface Host {
        /** 走完了，或者跳过了：回主界面。 */
        void onSetupFinished();

        /** 初始设置在不在屏幕上：在的时候抽屉拉不开。 */
        void onSetupShowing(boolean showing);
    }

    static final int LANGUAGE = 0;
    static final int BEFORE = 1;
    static final int SIDE = 2;
    static final int DRIVE = 3;
    static final int RECORD = 4;
    static final int FEATURES = 5;
    static final int DONE = 6;
    static final int STEPS = 7;

    /** 左边那一列上每一步的名字。 */
    private static final int[] NAMES = {R.string.setup_step_language, R.string.guide_title, R.string.setup_step_side,
            R.string.setup_step_drive, R.string.set_section_recording, R.string.setup_step_features,
            R.string.setup_step_done};
    /** 右边这一步的标题。 */
    private static final int[] TITLES = {R.string.dlg_language_title, R.string.guide_title, R.string.dlg_rail_side_title,
            R.string.setup_step_drive, R.string.set_section_recording, R.string.setup_step_features,
            R.string.setup_done_title};
    /** 标题下面那一句；0 = 没有。 */
    private static final int[] NOTES = {R.string.setup_language_note, 0, R.string.setup_side_note, 0,
            R.string.setup_record_note, R.string.setup_features_note, 0};

    /** 第五步摆的设置行（「设置 → 录制」里的那几行）。 */
    private static final String[] RECORD_ROWS = {"pref_auto_record", "pref_screen_off_keep_recording",
            "pref_footage_audio", "pref_car_model", "pref_record_quality"};
    /** 第六步摆的设置行（超级镜子、盲区画面、悬浮按钮各自的第一行）。 */
    private static final String[] FEATURE_ROWS = {"pref_rearview", "pref_side_view", "pref_recording_floating"};

    private static final String STATE_STEP = "step";
    private static final String STATE_REACHED = "reached";

    private AppConfig config;
    private int step;
    /** 走到过的最远一步：它和它前面的，在左边那一列上能点回去。 */
    private int reached;
    /** 正在换步（旧的一面还在退）：这时再点不接。 */
    private boolean moving;
    private boolean listening;

    private final View[] stepRows = new View[STEPS];
    private View block;
    private TextView title;
    private TextView note;
    private View scroll;
    private ViewGroup body;
    private View rows;
    private TextView back;
    private TextView next;
    private View skip;
    /** U 盘那一步的两行状态；不在那一步时是 null。 */
    private TextView driveTitle;
    private TextView driveNote;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_setup, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        config = new AppConfig(requireContext());
        if (savedInstanceState != null) {
            step = savedInstanceState.getInt(STATE_STEP, 0);
            reached = savedInstanceState.getInt(STATE_REACHED, step);
        }
        block = view.findViewById(R.id.setup_block);
        title = view.findViewById(R.id.setup_step_title);
        note = view.findViewById(R.id.setup_step_note);
        scroll = view.findViewById(R.id.setup_scroll);
        body = view.findViewById(R.id.setup_body);
        rows = view.findViewById(R.id.setup_rows);
        back = view.findViewById(R.id.setup_back);
        next = view.findViewById(R.id.setup_next);
        skip = view.findViewById(R.id.setup_skip);

        ViewGroup column = view.findViewById(R.id.setup_steps);
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        for (int i = 0; i < STEPS; i++) {
            View row = inflater.inflate(R.layout.item_setup_step, column, false);
            ((TextView) row.findViewById(R.id.setup_step_number)).setText(String.valueOf(i + 1));
            ((TextView) row.findViewById(R.id.setup_step_name)).setText(NAMES[i]);
            final int target = i;
            row.setOnClickListener(v -> {
                if (target <= reached) {
                    go(target);
                }
            });
            column.addView(row);
            stepRows[i] = row;
        }
        skip.setOnClickListener(v -> finish());
        back.setOnClickListener(v -> go(step - 1));
        next.setOnClickListener(v -> {
            if (step == DONE) {
                finish();
            } else {
                go(step + 1);
            }
        });
        render();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_STEP, step);
        outState.putInt(STATE_REACHED, reached);
    }

    @Override
    public void onStart() {
        super.onStart();
        Host host = host();
        if (host != null) {
            host.onSetupShowing(true);
        }
        listenStorage(step == DRIVE);
    }

    @Override
    public void onStop() {
        listenStorage(false);
        Host host = host();
        if (host != null) {
            host.onSetupShowing(false);
        }
        super.onStop();
    }

    @Override
    public void onDestroyView() {
        block.animate().cancel();
        moving = false;
        driveTitle = null;
        driveNote = null;
        super.onDestroyView();
    }

    /** 返回键：回上一步。已经在第一步，返回 false（所在的界面照主界面那样退到后台）。 */
    public boolean back() {
        if (step == 0) {
            return false;
        }
        go(step - 1);
        return true;
    }

    /** 直接摆出某一步，不放动效（截图用）。 */
    void jumpTo(int target) {
        step = target;
        reached = Math.max(reached, target);
        render();
    }

    // ------------------------------------------------------------------ 换步

    /** 往前一步、往回一步（ui-spec §十四）：往前，旧的向左退、新的从右边进来；往回反过来。不放动效时直接换。 */
    private void go(int target) {
        if (target < 0 || target >= STEPS || target == step || moving || getView() == null) {
            return;
        }
        int direction = target > step ? 1 : -1;
        step = target;
        reached = Math.max(reached, target);
        if (!MotionPolicy.decorative(requireContext())) {
            render();
            return;
        }
        moving = true;
        float shift = direction * 48f * getResources().getDisplayMetrics().density;
        block.animate().alpha(0f).translationX(-shift).setDuration(Motion.LEAVE).setInterpolator(Motion.exit())
                .withEndAction(() -> {
                    if (getView() == null) {
                        moving = false;
                        return;
                    }
                    render();
                    block.setTranslationX(shift);
                    block.animate().alpha(1f).translationX(0f).setDuration(Motion.SLOW - Motion.LEAVE)
                            .setInterpolator(Motion.enter()).withEndAction(() -> moving = false).start();
                }).start();
    }

    /** 照现在这一步把整个界面摆好。 */
    private void render() {
        for (int i = 0; i < STEPS; i++) {
            boolean done = i != step && i <= reached;
            stepRows[i].setActivated(i == step);
            stepRows[i].setClickable(i <= reached);
            stepRows[i].findViewById(R.id.setup_step_number).setVisibility(done ? View.INVISIBLE : View.VISIBLE);
            stepRows[i].findViewById(R.id.setup_step_check).setVisibility(done ? View.VISIBLE : View.GONE);
        }
        title.setText(TITLES[step]);
        if (NOTES[step] != 0) {
            note.setText(NOTES[step]);
            note.setVisibility(View.VISIBLE);
        } else {
            note.setVisibility(View.GONE);
        }
        back.setVisibility(step == 0 ? View.INVISIBLE : View.VISIBLE);
        next.setText(step == BEFORE ? R.string.setup_continue : step == DONE ? R.string.setup_enter : R.string.setup_next);
        skip.setVisibility(step == DONE ? View.INVISIBLE : View.VISIBLE);

        body.removeAllViews();
        driveTitle = null;
        driveNote = null;
        boolean settingRows = step == RECORD || step == FEATURES;
        scroll.setVisibility(settingRows ? View.GONE : View.VISIBLE);
        rows.setVisibility(settingRows ? View.VISIBLE : View.GONE);
        showRows(step == RECORD ? RECORD_ROWS : step == FEATURES ? FEATURE_ROWS : null);
        switch (step) {
            case LANGUAGE:
                language();
                break;
            case BEFORE:
                body.addView(paragraph(R.string.guide_1, body));
                break;
            case SIDE:
                side();
                break;
            case DRIVE:
                drive();
                break;
            case DONE:
                done();
                break;
            default:
        }
        listenStorage(step == DRIVE && isStarted());
    }

    private boolean isStarted() {
        return getLifecycle().getCurrentState().isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED);
    }

    /** 第五、六步：把设置里那几行摆进来；别的步把它拿走。 */
    private void showRows(@Nullable String[] keys) {
        FragmentManager manager = getChildFragmentManager();
        if (manager.isStateSaved()) {
            return;
        }
        Fragment shown = manager.findFragmentById(R.id.setup_rows);
        if (keys == null) {
            if (shown != null) {
                manager.beginTransaction().remove(shown).commit();
            }
            return;
        }
        String tag = "rows-" + step;
        if (shown != null && tag.equals(shown.getTag())) {
            return;
        }
        manager.beginTransaction().replace(R.id.setup_rows, SettingsPreferenceFragment.forRows(keys), tag).commit();
    }

    // ------------------------------------------------------------------ 每一步的内容

    /** 语言：四样一列，选了马上生效（语言真变了，系统重建界面，回到这一步）。 */
    private void language() {
        SettingSpec spec = SettingsRegistry.LANGUAGE;
        String current = spec.sanitize(config.getLanguageMode());
        LinearLayout list = new LinearLayout(requireContext());
        list.setOrientation(LinearLayout.VERTICAL);
        String[] values = spec.values();
        View[] cards = new View[values.length];
        for (int i = 0; i < values.length; i++) {
            String value = values[i];
            View card = choice(list, spec.label(requireContext(), value), null, null);
            card.setActivated(value.equals(current));
            card.setOnClickListener(v -> {
                for (View other : cards) {
                    other.setActivated(other == v);
                }
                config.setLanguageMode(value);
                Languages.apply(value);
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = dp(8);
            list.addView(card, params);
            cards[i] = card;
        }
        body.addView(list);
    }

    /** 驾驶座位置：左侧、右侧两块，各带一张小图（操作按钮那一列在哪边）。回主界面时那一列照它摆。 */
    private void side() {
        LinearLayout pair = new LinearLayout(requireContext());
        pair.setOrientation(LinearLayout.HORIZONTAL);
        String[] sides = {"left", "right"};
        int[] names = {R.string.opt_rail_left, R.string.opt_rail_right};
        int[] notes = {R.string.setup_side_lhd, R.string.setup_side_rhd};
        View[] cards = new View[sides.length];
        String current = config.getActionRailSide();
        for (int i = 0; i < sides.length; i++) {
            String value = sides[i];
            RailSideSketch sketch = new RailSideSketch(requireContext());
            sketch.setRailOnLeft(i == 0);
            View card = choice(pair, getString(names[i]), getString(notes[i]), sketch);
            card.setActivated(value.equals(current));
            card.setOnClickListener(v -> {
                for (View other : cards) {
                    other.setActivated(other == v);
                }
                config.setActionRailSide(value);
                config.setRailSideChosen();
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(320), ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd(dp(16));
            pair.addView(card, params);
            cards[i] = card;
        }
        body.addView(pair);
    }

    /** U 盘：不用选。两段说明，下面一块写此刻检测到没有（跟着插拔变）。没插也能往下走。 */
    private void drive() {
        LinearLayout column = new LinearLayout(requireContext());
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(paragraph(R.string.guide_2, column));
        column.addView(paragraph(R.string.set_storage_port_note, column));
        View status = LayoutInflater.from(requireContext()).inflate(R.layout.item_setup_choice, column, false);
        status.setBackgroundResource(R.drawable.bg_pref_row_static);
        status.setClickable(false);
        status.setFocusable(false);
        driveTitle = status.findViewById(R.id.setup_choice_title);
        driveNote = status.findViewById(R.id.setup_choice_note);
        driveNote.setVisibility(View.VISIBLE);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(8);
        column.addView(status, params);
        body.addView(column);
        showDrive(StorageState.current());
    }

    private void showDrive(StorageState.Snapshot state) {
        if (driveTitle == null || driveNote == null) {
            return;
        }
        boolean found = state.known && state.root != null;
        driveTitle.setText(found ? R.string.setup_drive_found : R.string.setup_drive_missing);
        if (!found) {
            driveNote.setText(R.string.setup_drive_later);
        } else if (state.freeBytes >= 0 && state.totalBytes > 0) {
            driveNote.setText(getString(R.string.setup_drive_space, StorageHelper.formatSize(state.freeBytes),
                    StorageHelper.formatSize(state.totalBytes)));
        } else {
            driveNote.setText("");
        }
    }

    @Override
    public void onStorageChanged(StorageState.Snapshot snapshot) {
        showDrive(snapshot);
    }

    /** 只在 U 盘那一步、界面看得见时听插拔；先要一次新的（进程刚起来时快照还是空的）。 */
    private void listenStorage(boolean on) {
        if (on == listening || getContext() == null) {
            return;
        }
        listening = on;
        if (on) {
            StorageState.addListener(requireContext(), this);
            StorageState.refresh(requireContext(), "setup");
        } else {
            StorageState.removeListener(this);
        }
    }

    /** 完成：列出现在的选择（两列），下面是出问题怎么办那一段。 */
    private void done() {
        Context context = requireContext();
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setBaselineAligned(false);
        card.setBackgroundResource(R.drawable.bg_pref_row_static);
        card.setPadding(dp(20), dp(10), dp(20), dp(10));
        LinearLayout left = new LinearLayout(context);
        left.setOrientation(LinearLayout.VERTICAL);
        LinearLayout right = new LinearLayout(context);
        right.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        card.addView(left, half);
        LinearLayout.LayoutParams other = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        other.setMarginStart(dp(40));
        card.addView(right, other);

        summary(left, R.string.set_language_title, SettingsRegistry.LANGUAGE.label(context, config.getLanguageMode()));
        summary(left, R.string.set_rail_side_title,
                SettingsRegistry.ACTION_RAIL_SIDE.label(context, config.getActionRailSide()));
        summary(left, R.string.set_auto_record_title, onOff(config.isAutoStartRecording()));
        summary(left, R.string.set_screen_off_keep_title, onOff(config.isScreenOffKeepRecording()));
        summary(left, R.string.set_footage_audio_title, onOff(config.isFootageAudioEnabled() && hasMic()));
        summary(right, R.string.set_car_model_title, SettingsRegistry.CAR_MODEL.label(context, config.getCarModel()));
        summary(right, R.string.editor_quality_title, quality());
        summary(right, R.string.set_section_rearview, config.isRearViewEnabled()
                ? SettingsRegistry.REARVIEW_VIEWS.label(context, config.getRearViewViews().key)
                : getString(R.string.opt_off));
        summary(right, R.string.set_side_view_title, config.isSideViewEnabled()
                ? SettingsRegistry.SIDE_VIEW_MODE.label(context, config.getSideViewMode())
                : getString(R.string.opt_off));
        summary(right, R.string.set_section_floating, onOff(config.isRecordingFloatingEnabled()));

        column.addView(card, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        View help = paragraph(R.string.guide_4, column);
        ((LinearLayout.LayoutParams) help.getLayoutParams()).topMargin = dp(20);
        column.addView(help);
        body.addView(column);
    }

    private String quality() {
        QualityPreset preset = new ProfileStore(requireContext()).current().quality;
        return getString(preset == QualityPreset.SAVE_SPACE ? R.string.editor_quality_space
                : preset == QualityPreset.SHARPEST ? R.string.editor_quality_sharp : R.string.editor_quality_balanced);
    }

    private String onOff(boolean on) {
        return getString(on ? R.string.quick_on : R.string.quick_off);
    }

    private boolean hasMic() {
        return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------ 零件

    /** 一块能选的：上面可以有一张图，下面名字，再下面可以有一行小字。选中的描一圈能量色。 */
    private View choice(ViewGroup parent, CharSequence name, @Nullable CharSequence small, @Nullable View art) {
        View card = LayoutInflater.from(requireContext()).inflate(R.layout.item_setup_choice, parent, false);
        ((TextView) card.findViewById(R.id.setup_choice_title)).setText(name);
        TextView smallView = card.findViewById(R.id.setup_choice_note);
        if (small != null) {
            smallView.setText(small);
            smallView.setVisibility(View.VISIBLE);
        }
        if (art != null) {
            ViewGroup frame = card.findViewById(R.id.setup_choice_art);
            frame.addView(art);
            frame.setVisibility(View.VISIBLE);
        }
        return card;
    }

    /** 一段正文。 */
    private View paragraph(int text, ViewGroup parent) {
        TextView view = (TextView) LayoutInflater.from(requireContext())
                .inflate(R.layout.item_setup_paragraph, parent, false);
        view.setText(text);
        return view;
    }

    /** 「完成」那一步的一行：左边是哪一项，右边是现在的值。 */
    private void summary(ViewGroup parent, int label, CharSequence value) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.item_setup_summary, parent, false);
        ((TextView) row.findViewById(R.id.setup_summary_label)).setText(label);
        ((TextView) row.findViewById(R.id.setup_summary_value)).setText(value);
        parent.addView(row);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ------------------------------------------------------------------ 收尾

    /** 走完或者跳过：记下「问过了」，回主界面。新装的不用再看「设置已重新整理」那个框（那是给升级上来的人看的）。 */
    private void finish() {
        boolean fresh = config.isFirstLaunch();
        config.setSetupDone();
        if (fresh) {
            config.setRegroupNoteSeen();
        }
        com.kooo.evcam.blackbox.BlackBox.noteImportant("setup: " + (step == DONE ? "done" : "skipped at " + (step + 1)));
        Host host = host();
        if (host != null) {
            host.onSetupFinished();
        }
    }

    @Nullable
    private Host host() {
        return getActivity() instanceof Host ? (Host) getActivity() : null;
    }
}
