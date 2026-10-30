package com.kooo.evcam.camera;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.profile.CameraProfile;
import com.kooo.evcam.profile.Profile;
import com.kooo.evcam.profile.ProfileSizes;
import com.kooo.evcam.profile.ProfileStore;

/**
 * 前座舱只用一次（暂时的规矩）。项目所有者 2026-10-22 定前后座舱都只用一次，2026-10-23 改成只有前座舱：
 * 环视和后座舱恢复一般的安排，预设「极氪7X（环视 + 前后座舱）」改成「极氪7X（环视 + 后座舱）」。
 *
 * <h3>为什么</h3>
 *
 * <p>带座舱日常用、开着应用锁车离开再回来，App Lab 的资源调度几乎每次出问题，累积下去 App Lab 卡顿、崩溃，
 * 重启车机后消失（发布页「已知问题」）。车机对这几路视频的打开、释放有什么要求还不知道；根因找到之前，前座舱不留到下一次用车：
 * 打开开关、回到主界面生效（和别的配置一样），这一次用车里用，离车后自动取消。</p>
 *
 * <h3>开关照实显示（项目所有者 2026-10-30）</h3>
 *
 * <p>开关开着就是在用。配置改动回到主界面就生效以后（CameraManagerHolder），不能再一启动就把开关关掉、这一次照样用：
 * 回到主界面一比，前座舱就被换掉了。下面几条路关掉它，都只经通道调度和配置：</p>
 *
 * <ul>
 *   <li><b>离车即关</b>（ChannelRules.retireFrontCabin）：通道眼里熄屏（黑满 30 秒、或者车机要睡）的那一刻 ——
 *       座舱本来就在这一刻放开 —— 前座舱去掉，亮屏也不回来；那份配置里的开关关掉（{@link #retire}），生效的配置就地一起改，
 *       主界面回到前台时不再摆它。后座舱照一般的规矩：熄屏放开、亮屏接回。</li>
 *   <li><b>没等到离车就结束的</b>（退出、车机结束进程、进程被杀）：带着前座舱建管线时记下「在用」（{@link #built}，运行状态，不导出），
 *       下一次进程起来、主界面摆布局和建管线之前，把那份配置里的开关关掉（{@link #settleAtStart}）。之后开机自启动、启动自动录制、
 *       退出再打开，都不开前座舱。退出途中马上又打开（同一个进程接着用）不算重新启动，照旧用着。</li>
 *   <li><b>换了另一份视频流配置</b>：前一份的前座舱这一次用完了，换上的那一刻关掉它的开关（{@link #built}）。</li>
 * </ul>
 *
 * <p>只关这一个开关：选的是哪份配置、别的参数都不动。超级镜子不受影响：它的后视和后座舱不是录制配置里的一路。</p>
 *
 * <p>找到根因后，这个类连同 ChannelRules 的那一条（retireFrontCabin、World.frontCabinOneShot / frontCabinRetired）
 * 和它们的调用一起拿掉。</p>
 */
public final class FrontCabinOneShot {

    /** 这条规矩开着。 */
    public static final boolean ACTIVE = true;

    /** 配置里前座舱那一路的角色（槽位和角色的对照只在 {@link ProfileSizes}）。 */
    public static final String ROLE = ProfileSizes.roleForCameraKey(CameraSlots.KEY_CABIN_FRONT);

    private FrontCabinOneShot() {
    }

    /**
     * 这份配置里的前座舱开关关掉，别的不动。
     *
     * @return true：改了
     */
    static boolean frontCabinOff(Profile profile) {
        CameraProfile front = profile.camera(ROLE);
        if (front == null || !front.enabled) {
            return false;
        }
        front.enabled = false;
        return true;
    }

    /**
     * 进程起来时（ZeekrShortcutApp.onCreate，主界面摆布局、建管线之前）：上一次运行带着前座舱、没等到离车就结束了，
     * 这次启动不带它 —— 那份配置里的前座舱开关关掉，记号清掉。
     */
    public static void settleAtStart(Context app) {
        if (!ACTIVE) {
            return;
        }
        AppConfig config = new AppConfig(app);
        String id = config.getFrontCabinInUse();
        if (id == null) {
            return;
        }
        if (switchOff(app, id)) {
            BlackBox.noteImportant("前座舱只用一次：上一次运行带着前座舱、没等到离车就结束了，配置「" + id
                    + "」里的前座舱开关已关掉，这次启动不带前座舱");
        }
        config.setFrontCabinInUse(null);
    }

    /**
     * 一份管线建了（CameraManagerHolder，主线程）：带着前座舱的，记下「在用」（哪份配置）—— 没等到离车就结束的话下一次启动关掉它；
     * 记着的是另一份配置（换了视频流配置）：那一份的前座舱这一次用完了，开关关掉。
     */
    static void built(Context app, Profile profile, boolean withFrontCabin) {
        if (!ACTIVE || profile == null) {
            return;
        }
        AppConfig config = new AppConfig(app);
        String marked = config.getFrontCabinInUse();
        if (marked != null && !marked.equals(profile.id) && switchOff(app, marked)) {
            BlackBox.noteImportant("前座舱只用一次：换成配置「" + profile.id + "」，配置「" + marked
                    + "」里的前座舱这一次用完了，开关已关掉");
        }
        config.setFrontCabinInUse(withFrontCabin ? profile.id : null);
    }

    /**
     * 离车（CameraManagerHolder.frontCabinRetired，主线程）：那份配置里的前座舱开关关掉，「在用」的记号清掉。
     *
     * @param running 生效的那份配置；null 时照记号关
     * @return {@code running} 关掉前座舱之后的样子（拷贝，生效的配置就地换成它）；{@code running} 为 null 时是 null
     */
    static Profile retire(Context app, Profile running) {
        AppConfig config = new AppConfig(app);
        String id = running != null ? running.id : config.getFrontCabinInUse();
        // 开关先落盘、再清记号：车机几秒后结束进程的话，下一次启动要么照记号关，要么开关已经关了
        if (id != null) {
            switchOff(app, id);
        }
        config.setFrontCabinInUse(null);
        if (running == null) {
            return null;
        }
        Profile off = Profile.fromMap(running.toMap());
        frontCabinOff(off);
        return off;
    }

    /** 存下的那份配置里前座舱的开关关掉，写到盘上才返回；没有这份配置、开关本来就关着的不算改。 */
    private static boolean switchOff(Context app, String id) {
        ProfileStore store = new ProfileStore(app);
        Profile profile = store.load(id);
        if (profile.cameras.isEmpty() || !frontCabinOff(profile)) {
            return false;
        }
        store.saveNow(profile);
        return true;
    }
}
