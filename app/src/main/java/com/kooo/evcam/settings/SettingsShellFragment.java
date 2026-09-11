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

    /** 打开设置时默认停在哪一段。 */
    static final String DEFAULT_SECTION = "screen_recording";

    private SlidingPaneLayout slidingPane;
    private String currentSection = DEFAULT_SECTION;
    /** 当前分区在左栏的行号，决定下一次切换往上还是往下走。 */
    private int currentOrder = -1;

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

        if (savedInstanceState != null) {
            String saved = savedInstanceState.getString(STATE_SECTION);
            if (saved != null) {
                currentSection = saved;
            }
        }

        if (savedInstanceState == null) {
            getChildFragmentManager().beginTransaction()
                    .replace(R.id.settings_headers, new SettingsHeadersFragment())
                    .commit();
            showSection(currentSection);
        }
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
        boolean down = order < 0 || order >= currentOrder;
        currentSection = screenKey;
        if (order >= 0) {
            currentOrder = order;
        }
        Fragment next = SettingsPreferenceFragment.forSection(screenKey);
        Fragment shown = getChildFragmentManager().findFragmentById(R.id.settings_detail);
        if (shown != null) {
            // 换分区沿纵轴走：左栏是竖着排的，点下面一行内容就从下面上来，
            // 点上面一行就从上面下来 —— 动作的方向和手指在列表里移动的方向一致。
            // 平级切换不用横向（那读起来像「进了下一级」），也不用 Z 轴（那是进二级界面）
            shown.setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.Y, down));
            next.setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.Y, down));
        }
        getChildFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.settings_detail, next)
                .commit();
        openDetail();
    }

    /**
     * 分区内部再往下走（权限、相机映射这些自带界面的）。
     *
     * <p>只换右栏，左栏不动 —— 这正是两栏布局的意义：
     * 进了二级界面还看得见自己在设置的哪一块。进返回栈，返回键回到分区。</p>
     */
    void openDetail(Fragment fragment) {
        // 进二级界面沿 Z 轴：新的一层从稍小放大到位，旧的一层稍放大并淡出；
        // 返回时两者各自倒放 —— 深了一层还是退回一层，看动作就知道
        Fragment shown = getChildFragmentManager().findFragmentById(R.id.settings_detail);
        if (shown != null) {
            shown.setExitTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, true));
            shown.setReenterTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, false));
        }
        fragment.setEnterTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, true));
        fragment.setReturnTransition(new MaterialSharedAxis(MaterialSharedAxis.Z, false));
        getChildFragmentManager().beginTransaction()
                .setReorderingAllowed(true)
                .replace(R.id.settings_detail, fragment)
                .addToBackStack(null)
                .commit();
        openDetail();
    }

    /** 窄屏时把右栏滑到前面；宽屏时两栏本来就并排，这一步不做任何事。 */
    private void openDetail() {
        if (slidingPane != null) {
            slidingPane.openPane();
        }
    }
}
