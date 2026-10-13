package com.kooo.evcam.storage;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.blackbox.BlackBox;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 存储状态的快照：主线程只读它，探测盘的活全在后台线程上做。
 *
 * <h3>为什么</h3>
 *
 * <p>以前状态条、录制键、开录前的检查、黑匣子的那几行，各自在主线程上 stat U 盘、读 /proc/mounts、
 * mkdirs —— 恰好在盘掉线（2026-09-26 固态盘掉过 5 秒）时最容易把主线程卡住。
 * 现在主线程上没有任何一处去碰盘：要什么就读 {@link #current()}，要新的就 {@link #refresh}。</p>
 *
 * <h3>规则</h3>
 *
 * <p>一份快照 {@link Snapshot}。刷新时机：U 盘事件、开录（查完再开；盘不能用拒了的，头 2 分钟每 2 秒再查一次，
 * RecordingStops.DiskWait）、停录、换盘、改存储设置、主界面回到前台，以及主界面在前台时每 {@link #PERIODIC_MS} 一次
 * （这台车机收不到 U 盘插拔广播 —— 09-26 到 10-13 的黑匣子里一行「U 盘事件」都没有，见平台笔记，定时是兜底）。
 * 结果回主线程通知 {@link Listener}。</p>
 */
public final class StorageState {

    private static final String TAG = "StorageState";
    private static final long PERIODIC_MS = 30_000L;

    /** 某一刻的存储状态。 */
    public static final class Snapshot {
        /** 还没探测过（进程刚起来）。 */
        public final boolean known;
        /** 录像盘根目录；null = 没有 U 盘。 */
        public final File root;
        /**
         * 按当前设置算出来的录像目录。没有 U 盘时：开发者选项开着是内置存储上的那个，
         * 关着是 null（没有地方可存，{@code StorageHelper.footageRoot}）。
         */
        public final File videoDir;
        /**
         * 录制器实际写进的目录：中转写入开着是内置存储上的临时目录（录完再搬到 {@link #videoDir}），否则就是 {@link #videoDir}；
         * null = 没有地方可存。开录照它和 {@link #videoDir} 定写到哪 —— 主线程不再自己去找目录、建目录（盘掉线时会卡住，
         * 审查 2026-10-11）。
         */
        public final File recordDir;
        /** 录像目录所在盘的剩余空间；-1 = 不知道。 */
        public final long freeBytes;
        /** 此刻能不能开录、拍照（和拒录、拒拍用的是同一个判断）。 */
        public final boolean available;
        /** 选的是 U 盘、此刻没检测到：能录的话（开发者选项放行）落在内置存储上。 */
        public final boolean sdFellBack;
        /** 此刻挂着的盘，黑匣子那种写法。 */
        public final String mounts;

        static final Snapshot EMPTY = new Snapshot(false, null, null, null, -1L, false, false, "?");

        Snapshot(boolean known, File root, File videoDir, File recordDir, long freeBytes, boolean available,
                 boolean sdFellBack, String mounts) {
            this.known = known;
            this.root = root;
            this.videoDir = videoDir;
            this.recordDir = recordDir;
            this.freeBytes = freeBytes;
            this.available = available;
            this.sdFellBack = sdFellBack;
            this.mounts = mounts;
        }

        /** 除了剩余空间，别的都一样。 */
        boolean sameShape(Snapshot other) {
            return known == other.known && available == other.available && sdFellBack == other.sdFellBack
                    && mounts.equals(other.mounts)
                    && (root == null ? other.root == null
                            : other.root != null && root.getAbsolutePath().equals(other.root.getAbsolutePath()));
        }

        /** 给黑匣子看的一行（ASCII）。 */
        public String describe() {
            return "root=" + (root == null ? "none" : root.getName()) + " available=" + (available ? "yes" : "no")
                    + " internal=" + (sdFellBack ? "yes" : "no")
                    + " free=" + (freeBytes < 0 ? "?" : StorageHelper.formatSize(freeBytes))
                    + " mounts=" + mounts;
        }
    }

    /** 快照变了（主线程）。 */
    public interface Listener {
        void onStorageChanged(Snapshot snapshot);
    }

    /** 一次刷新的结果（主线程）。 */
    public interface Callback {
        void onSnapshot(Snapshot snapshot);
    }

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "storage-state");
        thread.setDaemon(true);
        return thread;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static volatile Snapshot current = Snapshot.EMPTY;
    private static volatile Context app;
    private static final Runnable PERIODIC = new Runnable() {
        @Override
        public void run() {
            Context context = app;
            if (LISTENERS.isEmpty() || context == null) {
                return;
            }
            refresh(context, "periodic");
            MAIN.postDelayed(this, PERIODIC_MS);
        }
    };

    private StorageState() {
    }

    /** 最近一次探测的结果；进程刚起来时 {@code known == false}。 */
    public static Snapshot current() {
        return current;
    }

    public static void refresh(Context context, String why) {
        refresh(context, why, null);
    }

    /** 后台探测一次，结果回主线程：先通知监听者，再调 {@code callback}。 */
    public static void refresh(Context context, String why, Callback callback) {
        final Context c = context.getApplicationContext();
        app = c;
        EXECUTOR.execute(() -> {
            Snapshot before = current;
            Snapshot now;
            try {
                now = take(c);
            } catch (Throwable t) {
                AppLog.w(TAG, "探测存储失败: " + t);
                now = before;
            }
            current = now;
            if (!now.sameShape(before)) {
                BlackBox.noteImportant("存储（" + why + "）: " + now.describe());
            }
            final Snapshot result = now;
            MAIN.post(() -> {
                for (Listener listener : new ArrayList<>(LISTENERS)) {
                    listener.onStorageChanged(result);
                }
                if (callback != null) {
                    callback.onSnapshot(result);
                }
            });
        });
    }

    /** 真正去碰盘的地方 —— 只在后台线程上跑。 */
    private static Snapshot take(Context context) {
        // 探测结果的缓存是给主线程上的老调用留的；这里每次都要新鲜的
        StorageHelper.clearCache();
        File root = StorageHelper.getExternalSdCardRoot(context);
        File videoDir = StorageHelper.getVideoDir(context);
        // 中转写入关着时就是 videoDir：不再去碰一遍盘（盘卡住时一次探测就是几秒）
        File recordDir = new com.kooo.evcam.AppConfig(context).shouldUseRelayWrite()
                ? StorageHelper.getRecordingDir(context) : videoDir;
        long free = -1L;
        if (videoDir != null) {
            try {
                free = StorageHelper.getAvailableSpace(videoDir);
            } catch (Exception e) {
                AppLog.w(TAG, "读不到剩余空间: " + e);
            }
        }
        boolean available = StorageHelper.isRecordingStorageAvailable(context);
        boolean fellBack = StorageHelper.isSdCardFallback(context);
        return new Snapshot(true, root, videoDir, recordDir, free, available, fellBack, StorageHelper.describeMounts());
    }

    /** 主线程调。第一个监听者到了就开始定时刷新。 */
    public static void addListener(Context context, Listener listener) {
        app = context.getApplicationContext();
        if (!LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
        if (LISTENERS.size() == 1) {
            MAIN.removeCallbacks(PERIODIC);
            MAIN.postDelayed(PERIODIC, PERIODIC_MS);
        }
    }

    /** 主线程调。没人听了就不再定时刷新。 */
    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
        if (LISTENERS.isEmpty()) {
            MAIN.removeCallbacks(PERIODIC);
        }
    }
}
