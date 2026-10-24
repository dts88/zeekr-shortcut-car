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
 * 这里管行为：两个开关就地拨、抽屉不收；去别处的几项交给所在的界面（{@link Host}）。
 * 亮着的一项是当前这个界面，由所在的界面定（{@link #select}）。</p>
 */
public final class NavDrawer {

    /** 所在的界面：点了去别处的那一项（两个开关不经过这里）。 */
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
        layout.addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerOpened(View drawerView) {
                // 超级镜子也可能在设置里、或者悬浮窗自己关掉过
                syncSwitches();
            }
        });
        syncSwitches();
        menu.setNavigationItemSelectedListener(item -> {
            int itemId = item.getItemId();
            // 开关行：点一下就拨，不跳界面、不收抽屉 —— 拨完还看得见它变了没有
            if (itemId == R.id.nav_rearview) {
                toggleRearView();
                return false;
            }
            if (itemId == R.id.nav_floating_button) {
                toggleFloatingButton();
                return false;
            }
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

    /**
     * 「超级镜子」那一行。
     *
     * <p>和设置里那个开关走同一条路（{@code OverlayCoordinator.setRearViewEnabled}）：
     * 悬浮窗权限不够时它会返回 false，这时去申请权限，开关停在原位 ——
     * 开关显示的必须是后视镜真实的状态，不是「刚才按了一下」。</p>
     */
    private void toggleRearView() {
        boolean on = !new AppConfig(activity).isRearViewEnabled();
        if (!OverlayCoordinator.setRearViewEnabled(activity, on)) {
            Toast.makeText(activity, R.string.msg_need_overlay, Toast.LENGTH_SHORT).show();
            WakeUpHelper.requestOverlayPermission(activity);
        } else if (on) {
            Toast.makeText(activity, R.string.msg_rearview_on, Toast.LENGTH_SHORT).show();
            RearViewGuide.showOnce(activity);
        }
        syncSwitches();
    }

    /** 「悬浮按钮」那一行，和超级镜子那一行表现一致。 */
    private void toggleFloatingButton() {
        boolean on = !new AppConfig(activity).isRecordingFloatingEnabled();
        if (!OverlayCoordinator.setRecordButtonEnabled(activity, on)) {
            Toast.makeText(activity, R.string.msg_need_overlay, Toast.LENGTH_SHORT).show();
            WakeUpHelper.requestOverlayPermission(activity);
        }
        syncSwitches();
    }

    /** 行尾那两个开关照各自的真实状态摆。 */
    private void syncSwitches() {
        AppConfig config = new AppConfig(activity);
        syncSwitch(R.id.nav_rearview, config.isRearViewEnabled());
        syncSwitch(R.id.nav_floating_button, config.isRecordingFloatingEnabled());
    }

    private void syncSwitch(int itemId, boolean on) {
        MenuItem item = menu.getMenu().findItem(itemId);
        View action = item != null ? item.getActionView() : null;
        View toggle = action != null ? action.findViewById(R.id.nav_switch) : null;
        if (toggle instanceof CompoundButton) {
            ((CompoundButton) toggle).setChecked(on);
        }
    }
}
