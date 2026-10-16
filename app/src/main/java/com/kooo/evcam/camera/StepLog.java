package com.kooo.evcam.camera;

import android.content.Context;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 相机步骤统计存在哪（{@link StepStats}，2.11.4 R2）：应用目录下 {@code channel/steps.log}，一行一步，最近 {@link StepStats#MAX} 条，
 * 跨进程、跨版本留着（读不懂的行跳过）。
 *
 * <p>调度每结束一步记一行（MultiCameraManager 的 Journal.recorded，在主线程上）：写在自己的一条线程上，主线程不碰盘。
 * 只往后追加；攒到两倍就重写一遍、只留最后那些 —— 一步一次几十字节，不为每一行重写整份。诊断报告在它自己的后台线程上读（{@link #read}）。</p>
 *
 * <p>同一个地方还存每一台相机的来历（{@link CameraHistory}，2.11.5 第 D1、D3 项）：{@code channel/history.txt}，车机重新初始化以来开过几台、
 * 上一次重置、最后一次关完。进程起来时在写的那条线程上读回来（{@link #loadHistory}，ZeekrShortcutApp 调），之后每变一次在那条线程上整份重写
 * （几行字；连着变的并成一次）。记账本身在内存里、哪个线程都能做（相机线程开好 / 关完、主线程上相机服务的话），主线程不碰盘。</p>
 */
public final class StepLog {

    private static final String TAG = "StepLog";
    private static final String DIR = "channel";
    private static final String FILE = "steps.log";
    private static final String HISTORY_FILE = "history.txt";

    private static final Object LOCK = new Object();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "step-log");
        thread.setDaemon(true);
        return thread;
    });
    /** 文件里此刻有几行（{@link #LOCK}）；-1 = 还没数过。 */
    private static int lines = -1;

    /** 每一台相机的来历（进程里一份）。 */
    private static final CameraHistory HISTORY = new CameraHistory();
    /** 来历存在哪；读回来之前是 null（那之前变了的，读回来时一起写）。 */
    private static volatile File historyFile;
    /** 已经排了一次重写、还没写：连着变的并成一次。 */
    private static final AtomicBoolean HISTORY_SAVE_QUEUED = new AtomicBoolean();

    private StepLog() {
    }

    // ================================================================= 每一台相机的来历

    /** 每一台相机的来历（读的时候只在内存里，哪个线程都能读）。 */
    public static CameraHistory history() {
        return HISTORY;
    }

    /**
     * 进程起来时读回存下的来历（ZeekrShortcutApp.onCreate；马上返回，读在写的那条线程上）。车机重启过（黑匣子的开机记录，
     * {@link com.kooo.evcam.blackbox.BlackBox#rebootedSinceLastRun}，它在这之前接上）或者开机时长从头来过了，每一台从开机重新数。
     */
    public static void loadHistory(Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final boolean rebooted = com.kooo.evcam.blackbox.BlackBox.rebootedSinceLastRun();
        try {
            WRITER.execute(() -> {
                File file = new File(new File(app.getFilesDir(), DIR), HISTORY_FILE);
                String text = null;
                if (file.isFile()) {
                    try {
                        text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                    } catch (IOException e) {
                        AppLog.w(TAG, "cannot read the camera history: " + e.getMessage());
                    }
                }
                HISTORY.restore(text, rebooted, System.currentTimeMillis(), SystemClock.elapsedRealtime());
                historyFile = file;
                saveHistoryNow();
            });
        } catch (RuntimeException e) {
            AppLog.w(TAG, "cannot queue the camera history load: " + e);
        }
    }

    /** 相机服务交给我们一台设备（SingleCamera 开好那一下，相机线程）：数一台。 */
    public static void deviceOpened(String cameraId) {
        HISTORY.opened(cameraId, System.currentTimeMillis());
        historyChanged();
    }

    /** 我们关完了这一台的一个设备（任何一种关，SingleCamera 关完那一下）。 */
    public static void deviceClosed(String cameraId, long ms, String why) {
        HISTORY.closed(cameraId, System.currentTimeMillis(), SystemClock.elapsedRealtime(), ms, why);
        historyChanged();
    }

    /** 车机重新初始化了这一台（相机服务说它被别的程序拿了又空出来、或者没了又回来，主线程）：从头数。 */
    public static void cameraReset(String cameraId, CameraHistory.Reset why) {
        HISTORY.reset(cameraId, why, System.currentTimeMillis(), SystemClock.elapsedRealtime());
        historyChanged();
    }

    /** 来历变了：读回来以后才写（之前变的读回来时一起写），连着变的并成一次。 */
    private static void historyChanged() {
        if (historyFile == null || !HISTORY_SAVE_QUEUED.compareAndSet(false, true)) {
            return;
        }
        try {
            WRITER.execute(() -> {
                HISTORY_SAVE_QUEUED.set(false);
                saveHistoryNow();
            });
        } catch (RuntimeException e) {
            HISTORY_SAVE_QUEUED.set(false);
            AppLog.w(TAG, "cannot queue a camera history save: " + e);
        }
    }

    /** 整份重写（写的那条线程上）：先写到旁边、再换过去，写到一半断电也不会只剩半份。 */
    private static void saveHistoryNow() {
        File file = historyFile;
        if (file == null) {
            return;
        }
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            AppLog.w(TAG, "cannot create " + dir);
            return;
        }
        File tmp = new File(dir, HISTORY_FILE + ".tmp");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp, false), StandardCharsets.UTF_8)) {
            writer.write(HISTORY.encode(SystemClock.elapsedRealtime()));
        } catch (IOException e) {
            AppLog.w(TAG, "cannot write the camera history: " + e.getMessage());
            return;
        }
        if (!tmp.renameTo(file)) {
            AppLog.w(TAG, "cannot replace " + file);
        }
    }

    // ================================================================= 相机步骤统计

    /** 记一步（哪个线程都能调，马上返回）。 */
    public static void append(Context context, StepStats.Entry entry) {
        if (context == null || entry == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final String line = entry.encode();
        try {
            // 找应用目录（第一次会建目录）也在写的那条线程上
            WRITER.execute(() -> appendNow(file(app), line));
        } catch (RuntimeException e) {
            AppLog.w(TAG, "cannot queue a step: " + e);
        }
    }

    /** 最近的几步，按记下的先后（读不懂的行跳过）；读不到是空的。在后台线程上调。 */
    public static List<StepStats.Entry> read(Context context) {
        if (context == null) {
            return Collections.emptyList();
        }
        synchronized (LOCK) {
            return StepStats.parseAll(readLines(file(context)));
        }
    }

    private static File file(Context context) {
        return new File(new File(context.getApplicationContext().getFilesDir(), DIR), FILE);
    }

    private static void appendNow(File file, String line) {
        synchronized (LOCK) {
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                AppLog.w(TAG, "cannot create " + dir);
                return;
            }
            if (lines < 0) {
                lines = readLines(file).size();
            }
            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                writer.write(line);
                writer.write('\n');
                lines++;
            } catch (IOException e) {
                AppLog.w(TAG, "cannot write a step: " + e.getMessage());
                return;
            }
            if (lines > 2 * StepStats.MAX) {
                List<String> all = readLines(file);
                List<String> keep = all.subList(Math.max(0, all.size() - StepStats.MAX), all.size());
                try (Writer writer = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.UTF_8)) {
                    for (String one : keep) {
                        writer.write(one);
                        writer.write('\n');
                    }
                    lines = keep.size();
                } catch (IOException e) {
                    AppLog.w(TAG, "cannot trim the step log: " + e.getMessage());
                    lines = -1;
                }
            }
        }
    }

    /**
     * 文件里的每一行（空行不算）。读成字节再解：坏了的字节（断电写坏、存储出错）换成替代字符，那一行解析不了就跳过（{@link StepStats#parse}）——
     * {@code Files.readAllLines} 碰到一个坏字节就整份抛异常：报告里整份统计读不出来，攒到两倍重写时还把整份清掉。
     */
    private static List<String> readLines(File file) {
        if (!file.isFile()) {
            return new ArrayList<>();
        }
        try {
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            List<String> lines = new ArrayList<>();
            for (String line : text.split("\n")) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (IOException e) {
            AppLog.w(TAG, "cannot read the step log: " + e.getMessage());
            return new ArrayList<>();
        }
    }
}
