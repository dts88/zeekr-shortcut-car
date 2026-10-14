package com.kooo.evcam.camera;

import android.content.Context;

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

/**
 * 相机步骤统计存在哪（{@link StepStats}，2.11.4 R2）：应用目录下 {@code channel/steps.log}，一行一步，最近 {@link StepStats#MAX} 条，
 * 跨进程、跨版本留着（读不懂的行跳过）。
 *
 * <p>调度每结束一步记一行（MultiCameraManager 的 Journal.recorded，在主线程上）：写在自己的一条线程上，主线程不碰盘。
 * 只往后追加；攒到两倍就重写一遍、只留最后那些 —— 一步一次几十字节，不为每一行重写整份。诊断报告在它自己的后台线程上读（{@link #read}）。</p>
 */
public final class StepLog {

    private static final String TAG = "StepLog";
    private static final String DIR = "channel";
    private static final String FILE = "steps.log";

    private static final Object LOCK = new Object();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "step-log");
        thread.setDaemon(true);
        return thread;
    });
    /** 文件里此刻有几行（{@link #LOCK}）；-1 = 还没数过。 */
    private static int lines = -1;

    private StepLog() {
    }

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
