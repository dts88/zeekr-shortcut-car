package com.kooo.evcam.camera;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.blackbox.BlackBox;
import com.kooo.evcam.profile.CameraProfile;
import com.kooo.evcam.profile.Profile;
import com.kooo.evcam.profile.ProfileMigration;
import com.kooo.evcam.profile.ProfileStore;

import java.util.Collection;

/**
 * 前后座舱只用一次（暂时的规矩，项目所有者 2026-10-22）。
 *
 * <h3>为什么</h3>
 *
 * <p>「环视 + 前后座舱」日常用、开着应用锁车离开再回来，App Lab 的资源调度几乎每次出问题，累积下去 App Lab 卡顿、崩溃，
 * 重启车机后消失（发布页「已知问题」）。车机对这几路视频的打开、释放有什么要求还不知道；根因找到之前，座舱不留到下一次用车：
 * 配置好、重启应用生效（和原来一样），这一次运行里用，离车后自动取消。</p>
 *
 * <h3>两半，都只经通道调度和登记表</h3>
 *
 * <ul>
 *   <li><b>下次启动只有环视</b>（这里）：进程里第一次照带座舱的配置建管线时（{@link CameraManagerHolder}），把下次启动用的配置
 *       改成只有环视（{@link #consume}）。之后开机自启动、启动自动录制、退出再打开，都不开座舱。</li>
 *   <li><b>这一次运行里离车即关</b>（ChannelRules.retireCabins）：通道眼里熄屏（黑满 30 秒、或者车机要睡）的那一刻 ——
 *       座舱本来就在这一刻放开 —— 把它们从这次运行里去掉，亮屏也不回来；主界面回到前台时只剩环视。</li>
 * </ul>
 *
 * <h3>下次启动的配置怎么改（项目所有者 2026-10-22）</h3>
 *
 * <ul>
 *   <li>选的是「极氪7X（环视 + 前后座舱）」、没在「编辑视频流配置」里改过：选择改回「极氪7X（环视）」，那份配置不动 ——
 *       下次想带座舱，再选它、重启就行（{@link Restore#SELECT_SURROUND}）。改选择不在生效那一刻做，在离车时（座舱去掉那一刻）、
 *       或者下一次启动时（{@link #applyPendingRevert}）：一改选择，「此刻的配置」就成了「环视」那一份，这一次运行剩下的录像、
 *       画面参数都会照它读。</li>
 *   <li>改过的「环视 + 前后座舱」、或者在别的配置里手动开了座舱：生效那一刻就把这份配置里的两路座舱关掉
 *       （{@link Restore#TURN_OFF}）。只改开关，参数不动，这一次运行照旧。</li>
 * </ul>
 *
 * <p>两种都保证下次启动的那一份环视开着（「恢复到只有环视」）。超级镜子的后视和后座舱不受影响：它不是录制配置里的一路。</p>
 *
 * <p>找到根因后，这个类连同 ChannelRules 的那一条（retireCabins、World.cabinsOneShot / cabinsRetired）和它们的调用一起拿掉。</p>
 */
public final class CabinsOneShot {

    private static final String TAG = "CabinsOneShot";

    /** 这条规矩开着。 */
    public static final boolean ACTIVE = true;

    /** 下次启动的配置怎么改。 */
    enum Restore {
        /** 这次运行没开座舱：不改。 */
        NONE,
        /** 没改过的「环视 + 前后座舱」：选择改回「极氪7X（环视）」（离车时、或者下一次启动时）。 */
        SELECT_SURROUND,
        /** 改过的那份、或者别的配置里手动开了座舱：生效那一刻关掉这份配置里的座舱开关。 */
        TURN_OFF
    }

    private CabinsOneShot() {
    }

    /**
     * 纯判断（测试直接调）：这次运行启用了 {@code enabled} 这几路，用的是 {@code profileId} 这份配置。
     *
     * @param untouchedPreset 那份是没在编辑器里改过的「环视 + 前后座舱」
     */
    static Restore restoreFor(String profileId, Collection<String> enabled, boolean untouchedPreset) {
        if (!ACTIVE || !hasCabin(enabled)) {
            return Restore.NONE;
        }
        return Profile.PRESET_COMPOSITE_MULTI.equals(profileId) && untouchedPreset
                ? Restore.SELECT_SURROUND : Restore.TURN_OFF;
    }

    /** 这几路里有座舱（不是环视的那几路）。 */
    static boolean hasCabin(Collection<String> keys) {
        if (keys == null) {
            return false;
        }
        for (String key : keys) {
            if (!CameraSlots.KEY_SURROUND.equals(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这份配置改成只有环视：座舱开关关掉、环视开着。
     *
     * @return true：改了
     */
    static boolean surroundOnly(Profile profile) {
        boolean changed = false;
        for (CameraProfile camera : profile.cameras) {
            boolean want = CameraProfile.ROLE_COMPOSITE.equals(camera.role);
            if (camera.enabled != want) {
                camera.enabled = want;
                changed = true;
            }
        }
        return changed;
    }

    /**
     * 这次运行照带座舱的配置建了管线（{@link CameraManagerHolder}，进程里第一次建的那一下）：照 {@link #restoreFor} 改下次启动的配置。
     * 主线程。
     */
    static void consume(Context app, Profile running, Collection<String> enabled) {
        if (!ACTIVE || running == null || !hasCabin(enabled)) {
            return;
        }
        ProfileStore store = new ProfileStore(app);
        boolean untouched = Profile.PRESET_COMPOSITE_MULTI.equals(running.id) && untouched(app, store);
        Restore restore = restoreFor(running.id, enabled, untouched);
        if (restore == Restore.SELECT_SURROUND) {
            // 生效那一刻只记下来（落盘，断电也不丢）：离车时、或者下一次启动时改选择（见类说明）
            new AppConfig(app).setCabinsRevertPending(true);
            BlackBox.noteImportant("前后座舱只用一次：这次照「极氪7X（环视 + 前后座舱）」启动，离车后（或下一次启动时）"
                    + "视频流配置改回「极氪7X（环视）」");
            return;
        }
        if (restore == Restore.TURN_OFF) {
            Profile next = store.byId(running.id);
            if (surroundOnly(next)) {
                store.save(next);
            }
            BlackBox.noteImportant("前后座舱只用一次：这次带座舱启动，配置「" + running.id
                    + "」里的座舱开关已关掉，下次启动只有环视");
        }
    }

    /**
     * 有没改完的「改回选择」（{@link Restore#SELECT_SURROUND}）就现在改：离车（座舱去掉）时，和下一次启动、建管线之前。
     *
     * @param atStart 是下一次启动时改的（上一次运行没等到离车就结束了）
     * @return 改了之后的车型；没有要改的是 null
     */
    static String applyPendingRevert(Context app, boolean atStart) {
        AppConfig config = new AppConfig(app);
        if (!config.isCabinsRevertPending()) {
            return null;
        }
        ProfileStore store = new ProfileStore(app);
        config.setCarModel(ProfileMigration.carModelFor(Profile.PRESET_COMPOSITE));
        store.select(Profile.PRESET_COMPOSITE);
        Profile surround = store.byId(Profile.PRESET_COMPOSITE);
        if (surroundOnly(surround)) {
            store.save(surround);
        }
        config.setCabinsRevertPending(false);
        BlackBox.noteImportant("前后座舱只用一次（" + (atStart ? "启动时，上一次运行没等到离车" : "离车") + "）："
                + "视频流配置改回「极氪7X（环视）」");
        return config.getCarModel();
    }

    /** 「环视 + 前后座舱」那一份没在编辑器里改过：和它的初值一样。读不出来当改过（照关开关那一种来，参数不动）。 */
    private static boolean untouched(Context app, ProfileStore store) {
        try {
            ProfileMigration.Snapshot snapshot = ProfileStore.snapshot(app);
            snapshot.carModel = ProfileMigration.carModelFor(Profile.PRESET_COMPOSITE_MULTI);
            Profile fresh = ProfileMigration.migrate(snapshot);
            return store.load(Profile.PRESET_COMPOSITE_MULTI).toMap().equals(fresh.toMap());
        } catch (RuntimeException e) {
            AppLog.w(TAG, "比不出「环视 + 前后座舱」改没改过，当改过: " + e);
            return false;
        }
    }
}
