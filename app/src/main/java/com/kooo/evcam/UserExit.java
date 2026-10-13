package com.kooo.evcam;

import android.content.Context;
import android.content.SharedPreferences;

import com.kooo.evcam.blackbox.BlackBox;

/**
 * 用户主动退出之后，把所有「自己把自己拉起来」的路都停下。
 *
 * <h3>定义（2026-09-26 项目所有者）</h3>
 *
 * <p>用户主动退出后，暂停自启动，<b>直到下一次真正开机，或者用户手动打开</b>。</p>
 *
 * <h3>为什么以前退不干净</h3>
 *
 * <p>{@code exitApp()} 停了前台服务、然后 {@code System.exit(0)}，但从没取消 WorkManager
 * 那个十五分钟的保活任务。任务登记在系统那边，进程死了照样到点把进程拉起来 ——
 * 2026-09-23 21:10:32 退出，21:14:43 进程起来，第一行就是「保活任务执行」。
 * 另外，停前台服务时它的 {@code onDestroy} 会发 {@code ACTION_KEEP_ALIVE}，
 * 清单里的保活广播接收器收到又去启动它；系统也会重启 START_STICKY 的服务。</p>
 *
 * <h3>做法</h3>
 *
 * <p>退出时<b>先</b>写一个持久的标记、取消保活任务，再停服务。之后凡是会把我们拉起来的入口
 * （前台服务的启动口、保活广播、最早启动的 ContentProvider、保活任务、各服务被系统重启）
 * 都先问这里；标记在就什么也不做。主界面被创建时清掉 —— 我们自己拉主界面的那几条路
 * 都在这些入口后面，被拦住了，所以标记还在时主界面被创建，只能是人点开的。
 * 真正开机时也清掉（虽然实测这个容器不把开机广播送给我们，见平台笔记 §3.6）。</p>
 */
public final class UserExit {

    private static final String PREFS = "user_exit";
    private static final String KEY_EXITED_AT = "exited_at";
    /** 退出那一刻车机的开机时刻（墙钟减去开机以来的毫秒数）：下次进程起来，开机时刻变了才算真正开机过。 */
    private static final String KEY_EXITED_BOOT_AT = "exited_boot_at";
    /** 车机时钟会跳二十来秒（平台笔记），开机时刻差这么多以内算同一次开机。 */
    private static final long SAME_BOOT_TOLERANCE_MS = 60_000L;

    private UserExit() {
    }

    /**
     * 用户点了退出。同步落盘 —— 后面紧跟着 {@code System.exit}，异步写会丢。
     */
    public static void markExited(Context context) {
        prefs(context).edit().putLong(KEY_EXITED_AT, System.currentTimeMillis())
                .putLong(KEY_EXITED_BOOT_AT, bootAt()).commit();
        KeepAliveManager.stopKeepAliveWork(context);
        BlackBox.noteImportant("用户退出：暂停自启动，已取消保活任务，直到真正开机或手动打开");
    }

    public static boolean isExited(Context context) {
        return context != null && prefs(context).getLong(KEY_EXITED_AT, 0L) != 0L;
    }

    /**
     * 人点开了主界面，或者真正开机了：恢复自启动。
     *
     * @param why 给黑匣子看的原因，用 ASCII（调用方都是代码里的常量）
     */
    public static void clear(Context context, String why) {
        if (!isExited(context)) {
            return;
        }
        prefs(context).edit().remove(KEY_EXITED_AT).apply();
        BlackBox.noteImportant("恢复自启动：" + why);
    }

    /**
     * 某个入口要把我们拉起来之前先问一句。
     *
     * @return true 表示用户已经退出，这个入口应该什么也不做
     */
    public static boolean blocks(Context context, String who) {
        if (!isExited(context)) {
            return false;
        }
        // 开机广播这个容器不送（平台笔记 §3.6），「真正开机」看退出那一刻和现在的开机时刻是不是同一次：
        // 退出之后车机重启过，退出标记就作废（规格 1.4）。以前看的是黑匣子的「上次运行后重启过」——
        // 那是进程级的，车机重启后开的那个进程里，退出 6 秒就被它当成「又开机了」拉回来（2026-10-08 23:18）
        if (rebootedSinceExit(context)) {
            clear(context, "reboot (" + who + ")");
            return false;
        }
        return true;
    }

    /** 退出之后车机有没有真正重启过：开机时刻变了。老记录里没有开机时刻的按重启过算（和以前一样）。 */
    private static boolean rebootedSinceExit(Context context) {
        long exitedBootAt = prefs(context).getLong(KEY_EXITED_BOOT_AT, 0L);
        return exitedBootAt == 0L || Math.abs(bootAt() - exitedBootAt) > SAME_BOOT_TOLERANCE_MS;
    }

    private static long bootAt() {
        return System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
