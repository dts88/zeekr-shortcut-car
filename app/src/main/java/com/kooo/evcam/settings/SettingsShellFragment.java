/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.slidingpanelayout.widget.SlidingPaneLayout;

import com.google.android.material.transition.MaterialSharedAxis;
import com.kooo.evcam.R;
import com.kooo.evcam.ui.MotionPolicy;

/**
 * 设置界面的外壳：左侧分区列表，右侧该分区的内容。
 *
 * <h3>为什么要分两栏</h3>
 *
 * <p>车机内置屏是 3200px 宽的横屏。一列设置横铺过去，一行标题加一行说明，
 * 右边两千多像素基本是空的，眼睛还要沿着很长的距离来回扫。</p>
 *
 * <h3>为什么是换 fragment 而不是滚动到某一段</h3>
 *
 * <p>换 fragment 是 Android 自己的做法（系统设置在平板和折叠屏上就是这样），
 * 而且更准：分区拆开之后每一段都短到不用滚动，比"滑到大概位置"落点确定。</p>
 *
 * <h3>分区从哪来</h3>
 *
 * <p>{@code preferences.xml} 里每个分区都是一个嵌套的 {@code PreferenceScreen}，
 * 右栏用 {@code setPreferencesFromResource(res, rootKey)} 按 key 取出其中一段。
 * 所以分区只在那个 XML 里声明一次，这里不再另列一份 —— 两处各写一份迟早会对不上。</p>
 */
public class SettingsShellFragment extends Fragment {

    private static final String STATE_SECTION = "section";
    private static final String ARG_SECTION = "section";
    private static final String ARG_ROW = "row";
    private static final String ARG_FALLBACK = "fallback";

    /** 打开设置时默认停在哪一段。 */
    static final String DEFAULT_SECTION = "screen_recording";
    /** 开发者选项那一段：没打开时不显示（左栏也没有这一行）。 */
    static final String DEVELOPER_SECTION = "screen_developer";

    private SlidingPaneLayout slidingPane;
    private com.google.android.material.button.MaterialButton navButton;
    private android.widget.TextView titleText;
    private String currentSection = DEFAULT_SECTION;
    /** 当前分区在左栏的行号，决定下一次切换往上还是往下走。 */
    private int currentOrder = -1;

    /** 打开设置就停在某个分区、指着某一行（从主界面的状态点过来，{@link #showRow}）。 */
    public static SettingsShellFragment at(String sectionKey, String prefKey, @Nullable String fallbackKey) {
        SettingsShellFragment fragment = new SettingsShellFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SECTION, sectionKey);
        args.putString(ARG_ROW, prefKey);
        args.putString(ARG_FALLBACK, fallbackKey);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_settings_two_pane, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        slidingPane = view.findViewById(R.id.settings_sliding_pane);
        navButton = view.findViewById(R.id.settings_nav);
        titleText = view.findViewById(R.id.settings_title);
        if (navButton != null) {
            navButton.setOnClickListener(v -> onNavClick());
        }
        // 进出二级界面时标题区要跟着变
        getChildFragmentManager().addOnBackStackChangedListener(this::refreshTitle);
        com.kooo.evcam.ui.StatusLine.fill(view);
        com.kooo.evcam.ui.StatusLine.link(view, this::showRow);

        if (savedInstanceState != null) {
            String saved = savedInstanceState.getString(STATE_SECTION);
            if (saved != null) {
                currentSection = saved;
            }
        }

        Bundle args = getArguments();
        if (savedInstanceState == null && args != null && args.getString(ARG_SECTION) != null) {
            currentSection = args.getString(ARG_SECTION);
        }
        if (savedInstanceState == null) {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.settings_headers, new SettingsHeadersFragment())
                    .commit();
            showSection(currentSection);
            if (args != null && args.getString(ARG_ROW) != null) {
                String row = args.getString(ARG_ROW);
                String fallback = args.getString(ARG_FALLBACK);
                view.post(() -> showRow(currentSection, row, fallback));
            }
        }
        refreshTitle();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_SECTION, currentSection);
    }

    /**
     * 右栏切到某个分区。
     *
     * <p>不进返回栈：左栏一直看得见，切分区就像换个标签页，
     * 不该让返回键一段一段倒回去。分区里再往下的子界面才进返回栈。</p>
     */
    void showSection(String screenKey) {
        showSection(screenKey, -1);
    }

    /**
     * @param order 这个分区在左栏里是第几行；不知道就传 -1（按「往下」处理）
     */
    void showSection(String screenKey, int order) {
        // 开发者选项关着时右栏不显示那一段：关掉的那一刻正停在那里，回来时换到默认分区 ——
        // 留着的话那一页没接线，点里面的选项会崩，开关也只是空写
        if (DEVELOPER_SECTION.equals(screenKey) && !DeveloperMode.isUnlocked()) {
            screenKey = DEFAULT_SECTION;
            order = -1;
        }
        boolean down = order < 0 || order >= currentOrder;
        currentSection = screenKey;
        if (order >= 0) {
            currentOrder = order;
        }
        // 右边是这个分区，一列排到底（ui-spec §三「单列」）
        Fragment next = SettingsPreferenceFragment.forSection(screenKey);
        Fragment shown = getChildFragmentManager().findFragmentById(R.id.settings_detail);
        if (shown != null && MotionPolicy.decorative(requireContext())) {
            // 换分区沿纵轴走：左栏是竖着排的，点下面一行内容就从下面上来，
            // 点上面一行就从上面下来 —— 动作的方向和手指在列表里移动的方向一致。
            // 平级切换不用横向（那是往前一步、往回一步），也不用放大缩小（那是换界面）
            shown.setExitTransition(axis(MaterialSharedAxis.Y, down));
            next.setEnterTransition(axis(MaterialSharedAxis.Y, down));
        }
        getChildFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.settings_detail, next)
                .commit();
        // 左栏跟着把选中块挪过去（第一次进来时它还没建好，建的时候会来问 currentSection）
        Fragment headers = getChildFragmentManager().findFragmentById(R.id.settings_headers);
        if (headers instanceof SettingsHeadersFragment) {
            ((SettingsHeadersFragment) headers).markSelected(screenKey);
        }
        openDetail();
        refreshTitle();
    }

    /** 右栏正在显示的分区。左栏建列表时用它决定哪一项是选中的。 */
    String currentSection() {
        return currentSection;
    }

    /**
     * 标题区左键：分区层是菜单（拉开抽屉），二级界面是返回（退一层）。
     *
     * <p>位置不变、图标变 —— 手记住的是位置。抽屉长在主界面上，所以这里要问它。</p>
     */
    private void onNavClick() {
        if (getChildFragmentManager().getBackStackEntryCount() > 0) {
            getChildFragmentManager().popBackStack();
            return;
        }
        if (getActivity() instanceof com.kooo.evcam.MainActivity) {
            ((com.kooo.evcam.MainActivity) getActivity()).openDrawer();
        }
    }

    /** 标题区按当前深度重画。左栏建好、换分区、进出二级界面都要叫一次。 */
    void refreshTitle() {
        if (titleText == null || navButton == null) {
            return;
        }
        int depth = getChildFragmentManager().getBackStackEntryCount();
        if (depth > 0) {
            String name = getChildFragmentManager().getBackStackEntryAt(depth - 1).getName();
            titleText.setText(name != null ? name : getString(R.string.nav_settings));
            navButton.setIconResource(R.drawable.ic_back);
            navButton.setContentDescription(getString(R.string.action_back));
            return;
        }
        CharSequence section = null;
        Fragment headers = getChildFragmentManager().findFragmentById(R.id.settings_headers);
        if (headers instanceof SettingsHeadersFragment) {
            section = ((SettingsHeadersFragment) headers).titleOf(currentSection);
        }
        titleText.setText(section != null ? section : getString(R.string.nav_settings));
        navButton.setIconResource(R.drawable.ic_menu);
        navButton.setContentDescription(getString(R.string.cd_menu));
    }

    /** 设置里的过渡都是这一种做法、一个时长（ui-spec §十四：最长的一档）。 */
    private static MaterialSharedAxis axis(int axis, boolean forward) {
        MaterialSharedAxis transition = new MaterialSharedAxis(axis, forward);
        transition.setDuration(com.kooo.evcam.ui.Motion.SLOW);
        return transition;
    }

    /**
     * 分区内部再往下走（权限、相机映射这些自带界面的）。
     *
     * <p>只换右栏，左栏不动 —— 这正是两栏布局的意义：
     * 进了二级界面还看得见自己在设置的哪一块。进返回栈，返回键回到分区。</p>
     */
    void openDetail(Fragment fragment, CharSequence title) {
        // 往前一步、往回一步（ui-spec §十四）：进下一层，新的从右边进来、旧的向左退；返回时倒放 ——
        // 深了一层还是退回一层，看动作就知道。和初始设置里换步是同一个动作
        Fragment shown = getChildFragmentManager().findFragmentById(R.id.settings_detail);
        boolean animate = MotionPolicy.decorative(requireContext());
        if (shown != null) {
            shown.setExitTransition(animate ? axis(MaterialSharedAxis.X, true) : null);
            shown.setReenterTransition(animate ? axis(MaterialSharedAxis.X, false) : null);
        }
        if (animate) {
            fragment.setEnterTransition(axis(MaterialSharedAxis.X, true));
            fragment.setReturnTransition(axis(MaterialSharedAxis.X, false));
        }
        // 打上类名当 tag：被换下去的那一层还在返回栈里活着，
        // 有 tag 才找得回来。摆位那一页要改的正是被它换下去的配置编辑里
        // 那份没存的配置 —— 找不回来就只能重读磁盘，改了一半的东西会没
        getChildFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.settings_detail, fragment, fragment.getClass().getName())
                .addToBackStack(title == null ? null : title.toString())
                .commit();
        openDetail();
    }

    /** 窄屏时把右栏滑到前面；宽屏时两栏本来就并排，这一步不做任何事。 */
    private void openDetail() {
        if (slidingPane != null) {
            slidingPane.openPane();
        }
    }

    /** 左栏每个分区名下面那一行状态照现在的设置重写（右边改了一项之后，ui-spec §十三「左栏的状态」）。 */
    void refreshHeaders() {
        if (!isAdded()) {
            return;
        }
        Fragment headers = getChildFragmentManager().findFragmentById(R.id.settings_headers);
        if (headers instanceof SettingsHeadersFragment) {
            ((SettingsHeadersFragment) headers).refreshStatus();
        }
    }

    /**
     * 状态能点（ui-spec §十三）：换到某个分区，滚到某一行、闪两下。
     *
     * @param fallbackKey 那一行此刻收着（它的功能关着）时改闪这一行，一般是那个功能的第一行；可以是 null
     */
    public void showRow(String sectionKey, String prefKey, @Nullable String fallbackKey) {
        View root = getView();
        if (root == null || !isAdded()) {
            return;
        }
        if (getChildFragmentManager().getBackStackEntryCount() > 0) {
            getChildFragmentManager().popBackStackImmediate(null,
                    androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE);
        }
        Fragment shown = getChildFragmentManager().findFragmentById(R.id.settings_detail);
        if (!sectionKey.equals(currentSection) || !(shown instanceof SettingsPreferenceFragment)) {
            Fragment headers = getChildFragmentManager().findFragmentById(R.id.settings_headers);
            int order = headers instanceof SettingsHeadersFragment
                    ? ((SettingsHeadersFragment) headers).orderOf(sectionKey) : -1;
            showSection(sectionKey, order);
        }
        // 等这一页换好、列表排好再找那一行
        root.postDelayed(() -> {
            if (!isAdded()) {
                return;
            }
            Fragment now = getChildFragmentManager().findFragmentById(R.id.settings_detail);
            if (now instanceof SettingsPreferenceFragment) {
                SettingsPreferenceFragment section = (SettingsPreferenceFragment) now;
                if (!section.flash(prefKey) && fallbackKey != null) {
                    section.flash(fallbackKey);
                }
            }
        }, com.kooo.evcam.ui.Motion.SLOW);
    }
}
