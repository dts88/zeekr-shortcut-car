package com.kooo.evcam.camera;

import android.content.Context;

import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.profile.CameraProfile;
import com.kooo.evcam.profile.Profile;
import com.kooo.evcam.profile.ProfileSizes;
import com.kooo.evcam.profile.ProfileStore;

import java.util.Collection;

/**
 * 前座舱只用一次（暂时的规矩）。项目所有者 2026-10-22 定前后座舱都只用一次，2026-10-23 改成只有前座舱：
 * 环视和后座舱恢复一般的安排，预设「极氪7X（环视 + 前后座舱）」改成「极氪7X（环视 + 后座舱）」。
 *
 * <h3>为什么</h3>
 *
 * <p>带座舱日常用、开着应用锁车离开再回来，App Lab 的资源调度几乎每次出问题，累积下去 App Lab 卡顿、崩溃，
 * 重启车机后消失（发布页「已知问题」）。车机对这几路视频的打开、释放有什么要求还不知道；根因找到之前，前座舱不留到下一次用车：
 * 配置好、重启应用生效（和原来一样），这一次运行里用，离车后自动取消。</p>
 *
 * <h3>两半，都只经通道调度和登记表</h3>
 *
 * <ul>
 *   <li><b>下次启动不带前座舱</b>（这里）：进程里第一次照带前座舱的配置建管线时（{@link CameraManagerHolder}），
 *       把那份配置里的前座舱开关关掉（{@link #consume}）。只关这一个开关：选的是哪份配置、别的参数都不动，这一次运行照旧。
 *       之后开机自启动、启动自动录制、退出再打开，都不开前座舱。</li>
 *   <li><b>这一次运行里离车即关</b>（ChannelRules.retireFrontCabin）：通道眼里熄屏（黑满 30 秒、或者车机要睡）的那一刻 ——
 *       座舱本来就在这一刻放开 —— 把前座舱从这次运行里去掉，亮屏也不回来；主界面回到前台时不再摆它。
 *       后座舱照一般的规矩：熄屏放开、亮屏接回。</li>
 * </ul>
 *
 * <p>超级镜子不受影响：它的后视和后座舱不是录制配置里的一路。</p>
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

    /** 这几路里有前座舱。 */
    static boolean hasFrontCabin(Collection<String> keys) {
        return keys != null && keys.contains(CameraSlots.KEY_CABIN_FRONT);
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
     * 这次运行照带前座舱的配置建了管线（{@link CameraManagerHolder}，进程里第一次建的那一下）：那份配置里的前座舱开关关掉，
     * 下次启动不带它。主线程。
     */
    static void consume(Context app, Profile running, Collection<String> enabled) {
        if (!ACTIVE || running == null || !hasFrontCabin(enabled)) {
            return;
        }
        ProfileStore store = new ProfileStore(app);
        Profile next = store.byId(running.id);
        if (frontCabinOff(next)) {
            store.save(next);
        }
        BlackBox.noteImportant("前座舱只用一次：这次带前座舱启动，配置「" + running.id
                + "」里的前座舱开关已关掉，下次启动不带前座舱");
    }
}
