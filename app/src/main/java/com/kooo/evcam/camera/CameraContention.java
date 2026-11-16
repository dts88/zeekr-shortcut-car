/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.camera;

import android.app.ActivityManager;
import android.content.Context;
import android.os.SystemClock;

import com.kooo.evcam.MainActivity;
import com.kooo.evcam.blackbox.BlackBox;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 相机争用的调试日志：谁在什么时候拿了哪一路、我们哪一路紧跟着被断开、我们重开之后谁被顶掉。
 *
 * <p>项目所有者 2026-09-27：车机原生的后座画面逻辑上不该和环视冲突，先照常拿相机，
 * 用日志把过程记清楚。这里只记，不改任何行为。每一行都带上我们当时的处境：
 * 进程重要性（相机服务按它排优先级）、主界面在不在前台、我们开着哪几路。</p>
 *
 * <p>要回答的问题：车机的座舱画面拿相机 1 时，我们的相机 2 是不是紧跟着被断开（两路在相机服务里冲突）；
 * 我们重开相机 2 之后，相机 1 是不是马上空出来（我们把原厂画面顶掉了）；
 * 以及这两件事跟主界面在不在前台有没有关系。</p>
 *
 * <p>是谁拿的，相机服务不说。开发者选项开着时，别的程序拿、放的每一次另记一行嫌疑应用
 * （{@link CameraHolderSuspects}，几秒后才写）。</p>
 *
 * <p>熄屏 v3（项目所有者 2026-10-12 D4：记清楚）：拿走、空出来、我们被断开那几行熄屏时带「，熄屏后 +N ms」
 * （{@link BlackBox#afterScreenOff}）—— 熄屏约 98 秒后相机 1 用不了，以前要对着「熄屏」那一行手算；别的程序拿走一路之后
 * 30 秒，管理器再写一行环视那段时间里怎样（MultiCameraManager.watchSurroundAfterTake）。</p>
 *
 * <p>项目所有者 2026-11-16：一路不可用、不是我们时，那一刻查一下相机列表（{@link CameraAvailabilityWatch#listed}），
 * 列表里没有它就写「从相机列表消失」、回来时写「回到相机列表」，不写成别的程序拿了 —— 安卓对两种发的是同一声，
 * 离车后约 98 秒后座舱那一下多半是断电或被系统锁住，不是哪个应用一直在用。从列表消失的不查嫌疑应用（没有谁打开它）。</p>
 */
public final class CameraContention {

    /** 两件事隔多久以内算「紧跟着」。 */
    private static final long LINK_MS = 5_000L;

    private static final Map<String, Long> OTHERS_TOOK_AT = new ConcurrentHashMap<>();
    /** 表上那几路里，不可用的那一刻已经不在相机列表里的（{@link #othersTook}、{@link #othersTookAt} 当场查的）。只影响黑匣子的说法。 */
    private static final Set<String> VANISHED = ConcurrentHashMap.newKeySet();
    private static volatile String lastOthersId = "";
    private static volatile long lastOthersAt;
    private static volatile String lastOurOpenId = "";
    private static volatile long lastOurOpenAt;

    private CameraContention() {
    }

    /**
     * 相机服务报：这一路不可用了，不是我们 —— 别的程序拿了它，或者它从相机列表消失了。
     *
     * @param initial  注册回调时报的当前状态：什么时候拿的不知道，前后几秒的嫌疑应用对不上，不查
     * @param vanished 那一刻它已经不在相机列表里（{@link CameraAvailabilityWatch#listed}）：没有谁打开它，嫌疑应用不查
     */
    static void othersTook(String cameraId, boolean initial, boolean vanished) {
        long now = SystemClock.elapsedRealtime();
        OTHERS_TOOK_AT.put(cameraId, now);
        markVanished(cameraId, vanished);
        lastOthersId = cameraId;
        lastOthersAt = now;
        BlackBox.noteImportant("争用：" + (vanished ? "相机 " + cameraId + " 从相机列表消失" : "别的程序拿了相机 " + cameraId)
                + "；我们此刻 " + describeUs() + BlackBox.afterScreenOff());
        if (!initial) {
            if (!vanished) {
                CameraHolderSuspects.lookAround(cameraId, true);
            }
            watchSurroundAfter(cameraId, now);
        }
    }

    /**
     * 我们的一路丢了（或者开不起来）、关完了才认出是别的程序拿走的（2.11 起由通道调度判，判成 TAKEN 时调；
     * 主动认出的不调）：按丢的那一刻记，嫌疑应用也按那一刻查 —— 那一次关在相机服务里能卡 5–8 秒。只有 {@link #othersTook}
     * 没记上的才走这里（{@link #othersHoldNow}）：相机服务那一声「被占用」到得比断开还早（那时设备还是我们的，被记成
     * 「我们开着」），或者根本没有那一声（被占用只是换了个主人，状态没变）。
     *
     * @param atElapsed 丢的那一刻（开机起算，含深睡）
     */
    static void othersTookAt(String cameraId, long atElapsed) {
        long ago = Math.max(0, SystemClock.elapsedRealtime() - atElapsed);
        // 认出来的这一刻查相机列表（同 othersTook）：我们开着它时它没了，相机服务一声都不报（「不可用 → 不在」不回调）
        boolean vanished = !CameraAvailabilityWatch.listed(cameraId);
        OTHERS_TOOK_AT.put(cameraId, atElapsed);
        markVanished(cameraId, vanished);
        lastOthersId = cameraId;
        lastOthersAt = atElapsed;
        BlackBox.noteImportant("争用：" + (vanished ? "相机 " + cameraId + " 从相机列表消失" : "别的程序拿了相机 " + cameraId)
                + "（" + ago + " ms 前我们丢了它，关完才认出来）；我们此刻 " + describeUs() + BlackBox.afterScreenOff());
        if (!vanished) {
            CameraHolderSuspects.lookAround(cameraId, true, System.currentTimeMillis() - ago);
        }
        watchSurroundAfter(cameraId, atElapsed);
    }

    /**
     * 别的程序拿走了一路：拿走之后 30 秒管理器写一行环视那段时间里怎样（设计 v3 §6 L5；拿走的是环视、环视没开着的不写）。
     * 注册时报的初始状态不在此列：不知道是什么时候拿的。
     */
    private static void watchSurroundAfter(String cameraId, long atElapsed) {
        try {
            MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
            if (manager != null) {
                manager.watchSurroundAfterTake(cameraId, atElapsed);
            }
        } catch (Throwable t) {
            com.kooo.evcam.AppLog.w("CameraContention", "watchSurroundAfter: " + t);
        }
    }

    /** 这一路此刻在表上记着「别的程序拿了」（相机服务那一声当时就记过、还没报空出来）：调度判出被拿走时不再记一遍。 */
    static boolean othersHoldNow(String cameraId) {
        return OTHERS_TOOK_AT.containsKey(cameraId);
    }

    /**
     * 相机服务报：这一路空出来了。别的程序（多半是车机自己，醒来时那 1 秒）拿过它又放开 = 车机重新初始化了它：
     * 这一台的来历从头数（{@link CameraHistory.Reset#TAKEN}，2.11.5 第 D1 项，只记录）。从相机列表消失又回来的，
     * 写「回到相机列表」，来历按「没了又回来」数（{@link CameraHistory.Reset#CAME_BACK}，和问出来的「它回来了」一样）。
     */
    static void othersReleased(String cameraId) {
        Long took = OTHERS_TOOK_AT.remove(cameraId);
        boolean vanished = VANISHED.remove(cameraId);
        if (took == null) {
            return;
        }
        StepLog.cameraReset(cameraId, vanished ? CameraHistory.Reset.CAME_BACK : CameraHistory.Reset.TAKEN);
        long now = SystemClock.elapsedRealtime();
        long sinceOurOpen = lastOurOpenAt == 0 ? -1 : now - lastOurOpenAt;
        BlackBox.noteImportant("争用：相机 " + cameraId + (vanished ? " 回到相机列表，消失了 " : " 空出来了，别的程序拿了 ")
                + (now - took) + " ms"
                + (!vanished && sinceOurOpen >= 0 && sinceOurOpen < LINK_MS
                        ? "；距我们重开相机 " + lastOurOpenId + " 才 " + sinceOurOpen + " ms —— 多半是被我们顶掉的" : "")
                + BlackBox.afterScreenOff());
        if (!vanished) {
            CameraHolderSuspects.lookAround(cameraId, false);
        }
    }

    /** 我们的一路被相机服务断开了（onDisconnected）。 */
    public static void ourCameraDisconnected(String cameraId) {
        long since = lastOthersAt == 0 ? -1 : SystemClock.elapsedRealtime() - lastOthersAt;
        BlackBox.noteImportant("争用：我们的相机 " + cameraId + " 被断开"
                + (since >= 0 && since < LINK_MS
                        ? "，距别的程序拿相机 " + lastOthersId + " 才 " + since + " ms —— 两路在相机服务里冲突" : "")
                + "；我们此刻 " + describeUs() + BlackBox.afterScreenOff());
    }

    /** 我们打开（或重开）成功了。表上还有别的程序占着、或者不在相机列表的相机时才值一行。 */
    public static void ourCameraOpened(String cameraId) {
        lastOurOpenId = cameraId;
        lastOurOpenAt = SystemClock.elapsedRealtime();
        if (!OTHERS_TOOK_AT.isEmpty()) {
            String taken = ids(OTHERS_TOOK_AT.keySet(), VANISHED, false);
            String gone = ids(OTHERS_TOOK_AT.keySet(), VANISHED, true);
            BlackBox.noteImportant("争用：" + (taken.isEmpty() ? "" : "别的程序占着相机 " + taken + (gone.isEmpty() ? "" : "、"))
                    + (gone.isEmpty() ? "" : "相机 " + gone + " 不在相机列表")
                    + "，我们开相机 " + cameraId + " 成功；我们此刻 " + describeUs());
        }
    }

    /** 我们打开失败了。表上还有别的程序占着、或者不在相机列表的相机时才值一行。 */
    public static void ourOpenFailed(String cameraId, String error) {
        if (!OTHERS_TOOK_AT.isEmpty()) {
            String taken = ids(OTHERS_TOOK_AT.keySet(), VANISHED, false);
            String gone = ids(OTHERS_TOOK_AT.keySet(), VANISHED, true);
            BlackBox.noteImportant("争用：我们开相机 " + cameraId + " 失败：" + error + "；"
                    + (taken.isEmpty() ? "" : "别的程序占着 " + taken + (gone.isEmpty() ? "" : "、"))
                    + (gone.isEmpty() ? "" : "相机 " + gone + " 不在相机列表")
                    + "；我们此刻 " + describeUs());
        }
    }

    /**
     * 表上那几路的编号，按编号排好、用于黑匣子：{@code vanished} 为真只要不在相机列表的，为假只要别的程序占着的；一个都没有是 ""。
     *
     * @param took        表上记着的那几路（{@link #OTHERS_TOOK_AT}）
     * @param vanishedIds 其中不在相机列表的（{@link #VANISHED}）
     */
    static String ids(Collection<String> took, Set<String> vanishedIds, boolean vanished) {
        TreeSet<String> out = new TreeSet<>();
        for (String id : took) {
            if (vanishedIds.contains(id) == vanished) {
                out.add(id);
            }
        }
        return out.isEmpty() ? "" : out.toString();
    }

    /** 记下这一路此刻在不在相机列表（{@link #VANISHED}）。 */
    private static void markVanished(String cameraId, boolean vanished) {
        if (vanished) {
            VANISHED.add(cameraId);
        } else {
            VANISHED.remove(cameraId);
        }
    }

    /** 相机服务说访问优先级变了（前后台切换那种）。 */
    static void prioritiesChanged() {
        BlackBox.noteImportant("相机服务：访问优先级变了；我们此刻 " + describeUs());
    }

    /** 我们此刻的处境，ASCII 一行：importance=… main=… open=[…]。 */
    public static String describeUs() {
        StringBuilder sb = new StringBuilder();
        try {
            ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
            ActivityManager.getMyMemoryState(info);
            sb.append("importance=").append(info.importance);
        } catch (Throwable t) {
            sb.append("importance=?");
        }
        sb.append(" main=").append(MainActivity.getInstance() == null ? "none"
                : (StallWatch.isForeground() ? "foreground" : "background"));
        try {
            MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
            StringBuilder open = new StringBuilder();
            if (manager != null) {
                for (String key : new String[]{CameraSlots.KEY_SURROUND, CameraSlots.KEY_CABIN_FRONT,
                        CameraSlots.KEY_CABIN_REAR}) {
                    SingleCamera camera = manager.getCamera(key);
                    if (camera != null && camera.holdsOrIsOpening()) {
                        open.append(open.length() > 0 ? "," : "").append(camera.getCameraId());
                    }
                }
            }
            sb.append(" open=[").append(open).append("]");
        } catch (Throwable t) {
            sb.append(" open=?");
        }
        return sb.toString();
    }
}
