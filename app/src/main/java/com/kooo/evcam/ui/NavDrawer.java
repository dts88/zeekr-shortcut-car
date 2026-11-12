/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import android.app.Activity;
import android.content.Intent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.overlay.OverlayCoordinator;

/**
 * 抽屉（侧边菜单）：主界面、录像回放、照片回放共用这一份（项目所有者 2026-10-24：回放界面上点菜单键，
 * 抽屉就地滑出，不先退回主界面）。
 *
 * <p>菜单、头、样式各只有一份（{@code navigation_menu}、{@code nav_header}、{@code layout_nav_drawer}），
 * 这里管行为：去别处的几项交给所在的界面（{@link Host}）。抽屉只管去哪 —— 原来的两行开关 4.0 起在主界面动作栏上
 * （{@link QuickSwitches}，项目所有者 2026-11-12）。
 * 亮着的一项是当前这个界面，由所在的界面定（{@link #select}）。</p>
 */
public final class NavDrawer {

    /** 所在的界面：点了去别处的那一项。 */
    public interface Host {
        void onNavigate(int itemId);
    }

    private final Activity activity;
    private final DrawerLayout layout;
    private final NavigationView menu;

    private NavDrawer(Activity activity) {
        this.activity = activity;
        layout = activity.findViewById(R.id.drawer_layout);
        menu = activity.findViewById(R.id.nav_view);
    }

    /**
     * 接上这个界面布局里的抽屉（{@code drawer_layout}、{@code nav_view}）。
     *
     * @param current 这个界面是菜单里的哪一项，先亮着它
     */
    public static NavDrawer attach(Activity activity, int current, Host host) {
        NavDrawer drawer = new NavDrawer(activity);
        drawer.wire(host);
        drawer.select(current);
        return drawer;
    }

    /**
     * 回放界面上的抽屉：主界面上才办得了的几项回主界面去办。
     *
     * <ul>
     *   <li>当前这一项：只收起抽屉；</li>
     *   <li>录像回放、照片回放：直接换过去，关掉这一个 —— 返回键照旧回主界面；</li>
     *   <li>关于与致谢：在这个界面上面打开，返回回到这里；</li>
     *   <li>主界面、设置、退出应用：回到主界面，由它去办（{@link MainActivity#EXTRA_NAV_ITEM}）。</li>
     * </ul>
     *
     * @param current 这个回放界面是哪一项（{@code nav_playback} 或 {@code nav_photo_playback}）
     */
    public static NavDrawer attachToPlayback(Activity activity, int current) {
        return attach(activity, current, itemId -> {
            if (itemId == current) {
                return;
            }
            if (itemId == R.id.nav_about) {
                activity.startActivity(new Intent(activity, com.kooo.evcam.zeekr.AboutActivity.class));
            } else if (itemId == R.id.nav_playback || itemId == R.id.nav_photo_playback) {
                activity.startActivity(new Intent(activity, itemId == R.id.nav_playback
                        ? com.kooo.evcam.zeekr.TimelinePlayerActivity.class
                        : com.kooo.evcam.playback.PhotoPlaybackActivity.class));
                activity.finish();
            } else {
                activity.startActivity(new Intent(activity, MainActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(MainActivity.EXTRA_NAV_ITEM, itemId));
                activity.finish();
            }
        });
    }

    private void wire(Host host) {
        showVersion();
        menu.setNavigationItemSelectedListener(item -> {
            int itemId = item.getItemId();
            host.onNavigate(itemId);
            layout.closeDrawer(GravityCompat.START);
            return false;   // 亮哪一项由所在的界面定（select）
        });
    }

    public void toggle() {
        if (layout.isDrawerOpen(GravityCompat.START)) {
            layout.closeDrawer(GravityCompat.START);
        } else {
            layout.openDrawer(GravityCompat.START);
        }
    }

    public void open() {
        layout.openDrawer(GravityCompat.START);
    }

    /** 锁住：从屏幕边上拉不开（初始设置在屏幕上时，那几步还没走完）；开着的先收起。 */
    public void setLocked(boolean locked) {
        layout.setDrawerLockMode(locked ? DrawerLayout.LOCK_MODE_LOCKED_CLOSED : DrawerLayout.LOCK_MODE_UNLOCKED);
    }

    /** 开着就收起来，返回 true —— 返回键先收抽屉。 */
    public boolean closeIfOpen() {
        if (!layout.isDrawerOpen(GravityCompat.START)) {
            return false;
        }
        layout.closeDrawer(GravityCompat.START);
        return true;
    }

    /**
     * 亮着哪一项：这个界面现在显示的那一项。
     *
     * <p>跨组选中要先把别的清掉，否则会同时亮着两项。</p>
     */
    public void select(int itemId) {
        Menu items = menu.getMenu();
        for (int i = 0; i < items.size(); i++) {
            MenuItem item = items.getItem(i);
            item.setChecked(false);
            if (item.hasSubMenu()) {
                SubMenu subMenu = item.getSubMenu();
                for (int j = 0; j < subMenu.size(); j++) {
                    subMenu.getItem(j).setChecked(false);
                }
            }
        }
        menu.setCheckedItem(itemId);
    }

    /** 头里的版本号；读不到就留着默认的字。 */
    private void showVersion() {
        View header = menu.getHeaderView(0);
        TextView version = header != null ? header.findViewById(R.id.nav_header_version) : null;
        if (version == null) {
            return;
        }
        try {
            String name = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
            version.setText(activity.getString(R.string.nav_header_version, name));
        } catch (Exception e) {
            // 保持默认文本
        }
    }

}
