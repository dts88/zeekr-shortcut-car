package com.kooo.evcam;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;


import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AppLog {
    private static final int MAX_BUFFER_LINES = 5000;
    private static final Object LOCK = new Object();
    private static final List<String> BUFFER = new ArrayList<>();
    
    // 会话日志文件名
    private static final String CURRENT_SESSION_LOG = "current_session.log";
    private static final String PREVIOUS_SESSION_LOG = "previous_session.log";
    
    // Application Context 引用（用于崩溃时保存日志）
    private static Context sAppContext = null;
    
    // 原始的 UncaughtExceptionHandler
    private static Thread.UncaughtExceptionHandler sDefaultHandler = null;


    private AppLog() {
    }

    public static void init(Context context) {
        if (context == null) {
            return;
        }
        
        // 保存 Application Context（用于崩溃时保存日志）
        sAppContext = context.getApplicationContext();
        
        // 启动时轮换日志文件
        rotateSessionLogs(context);
        
        // 设置崩溃处理器，确保闪退时能保存日志
        setupCrashHandler();
    }
    
    /**
     * 设置崩溃处理器
     * 在应用崩溃时自动保存日志，便于排查闪退问题
     */
    private static void setupCrashHandler() {
        // 保存原始的 handler
        sDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                // 记录崩溃信息到日志
                String crashInfo = "!!! APPLICATION CRASH !!!\n" +
                        "Thread: " + thread.getName() + "\n" +
                        "Exception: " + throwable.getClass().getName() + "\n" +
                        "Message: " + throwable.getMessage() + "\n" +
                        "Stack trace:\n" + Log.getStackTraceString(throwable);
                
                // 添加崩溃信息到缓冲区
                addToBuffer(Log.ERROR, "CRASH", crashInfo);
                
                // 保存日志到文件
                if (sAppContext != null) {
                    saveToPersistentLog(sAppContext);
                    Log.e("AppLog", "Crash log saved successfully");
                }
            } catch (Exception e) {
                Log.e("AppLog", "Failed to save crash log", e);
            }
            
            // 调用原始 handler（让系统继续处理崩溃）
            if (sDefaultHandler != null) {
                sDefaultHandler.uncaughtException(thread, throwable);
            }
        });
        
        Log.i("AppLog", "Crash handler installed for automatic log saving");
    }
    
    /**
     * 轮换会话日志文件
     * 将当前日志备份为上次日志，清空当前日志
     */
    private static void rotateSessionLogs(Context context) {
        if (context == null) return;
        
        File logDir = getLogDirectory(context);
        File currentLog = new File(logDir, CURRENT_SESSION_LOG);
        File previousLog = new File(logDir, PREVIOUS_SESSION_LOG);
        
        // 如果当前日志存在，将其备份为上次日志
        if (currentLog.exists() && currentLog.length() > 0) {
            // 删除旧的上次日志
            if (previousLog.exists()) {
                if (!previousLog.delete()) {
                    Log.w("AppLog", "Failed to delete old previous session log");
                }
            }
            // 重命名当前日志为上次日志
            boolean renamed = currentLog.renameTo(previousLog);
            if (renamed) {
                Log.i("AppLog", "Previous session log saved: " + previousLog.getAbsolutePath());
            } else {
                Log.w("AppLog", "Failed to rename current session log to previous");
            }
        }
    }
    
    /**
     * 获取日志存储目录
     */
    private static File getLogDirectory(Context context) {
        // 使用应用私有目录存储会话日志，避免权限问题
        File logDir = new File(context.getFilesDir(), "logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        return logDir;
    }
    
    /**
     * 保存当前日志到持久化文件
     * 建议在 Activity.onStop() 或 onDestroy() 中调用
     */
    public static void saveToPersistentLog(Context context) {
        if (context == null) return;
        
        List<String> snapshot;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(BUFFER);
        }
        
        if (snapshot.isEmpty()) return;
        
        File logDir = getLogDirectory(context);
        File currentLog = new File(logDir, CURRENT_SESSION_LOG);
        
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(currentLog), StandardCharsets.UTF_8)) {
            for (String line : snapshot) {
                writer.write(line);
                writer.write('\n');
            }
            Log.d("AppLog", "Current session log saved: " + snapshot.size() + " lines");
        } catch (IOException e) {
            Log.w("AppLog", "Failed to save current session log: " + e.getMessage());
        }
    }


    /**
     * 获取上次运行的日志内容
     */
    public static List<String> getPreviousSessionLogs(Context context) {
        List<String> logs = new ArrayList<>();
        if (context == null) return logs;
        
        File logDir = getLogDirectory(context);
        File previousLog = new File(logDir, PREVIOUS_SESSION_LOG);
        
        if (!previousLog.exists()) return logs;
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(previousLog), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logs.add(line);
            }
        } catch (IOException e) {
            Log.w("AppLog", "Failed to read previous session log: " + e.getMessage());
        }
        
        return logs;
    }

    /** 缓冲区里最后 n 行。卡顿报告拿它当卡住前后的上下文。 */
    public static List<String> tail(int n) {
        synchronized (LOCK) {
            int from = Math.max(0, BUFFER.size() - Math.max(0, n));
            return new ArrayList<>(BUFFER.subList(from, BUFFER.size()));
        }
    }

    public static void d(String tag, String message) {
        logInternal(Log.DEBUG, tag, message, null);
    }

    public static void d(String tag, String message, Throwable tr) {
        logInternal(Log.DEBUG, tag, message, tr);
    }

    public static void i(String tag, String message) {
        logInternal(Log.INFO, tag, message, null);
    }

    public static void i(String tag, String message, Throwable tr) {
        logInternal(Log.INFO, tag, message, tr);
    }

    public static void w(String tag, String message) {
        logInternal(Log.WARN, tag, message, null);
    }

    public static void w(String tag, String message, Throwable tr) {
        logInternal(Log.WARN, tag, message, tr);
    }

    public static void e(String tag, String message) {
        logInternal(Log.ERROR, tag, message, null);
    }

    public static void e(String tag, String message, Throwable tr) {
        logInternal(Log.ERROR, tag, message, tr);
    }

    private static void logInternal(int level, String tag, String message, Throwable tr) {
        String safeTag = tag == null ? "AppLog" : tag;
        String safeMessage = message == null ? "" : message;
        if (tr != null) {
            safeMessage = safeMessage + "\n" + Log.getStackTraceString(tr);
        }
        Log.println(level, safeTag, safeMessage);
        addToBuffer(level, safeTag, safeMessage);
    }

    private static void addToBuffer(int level, String tag, String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String levelLabel = levelToLabel(level);
        String line = timestamp + " " + levelLabel + "/" + tag + ": " + message;
        synchronized (LOCK) {
            BUFFER.add(line);
            if (BUFFER.size() > MAX_BUFFER_LINES) {
                int removeCount = BUFFER.size() - MAX_BUFFER_LINES;
                BUFFER.subList(0, removeCount).clear();
            }
        }
        if (level >= Log.WARN) {
            persistWarning(line, tag, message);
        }
    }

    private static String levelToLabel(int level) {
        switch (level) {
            case Log.ERROR:
                return "E";
            case Log.WARN:
                return "W";
            case Log.INFO:
                return "I";
            case Log.DEBUG:
                return "D";
            default:
                return String.valueOf(level);
        }
    }

    // ------------------------------------------------------------------ 警告和错误落盘

    /**
     * 警告和错误另存一份到 {@code logs/warnings.log}：重启、升级都还在，诊断报告会带上。
     *
     * <p>内存里那 5000 行只活到进程结束；录像一出错又往往每帧报一次，几分钟就把出事那一刻冲掉。
     * 2026-09-26 哨兵模式那一次，编码器为什么坏、重建为什么失败，原因就只在内存里，
     * 导出报告时已经没了。</p>
     *
     * <p>只存警告和错误，量很小；同一句话 60 秒内只存一次（下次存时补一句中间省掉了几次），
     * 堆栈只留前几行 —— 不为这个去磨车机的闪存。文件超过上限就只留后一半。</p>
     */
    private static final String WARNINGS_LOG = "warnings.log";
    private static final long WARNINGS_LOG_LIMIT_BYTES = 512L * 1024L;
    private static final long SAME_WARNING_GAP_MS = 60_000L;
    private static final int WARNING_LINES = 12;
    private static final Map<String, long[]> LAST_WARNING = new HashMap<>();
    private static Handler warningWriter;

    private static void persistWarning(String line, String tag, String message) {
        final Context context = sAppContext;
        if (context == null) {
            return;
        }
        int newline = message.indexOf('\n');
        String first = newline >= 0 ? message.substring(0, newline) : message;
        String key = tag + "|" + (first.length() > 120 ? first.substring(0, 120) : first);
        long now = android.os.SystemClock.uptimeMillis();
        long skipped;
        synchronized (LAST_WARNING) {
            long[] seen = LAST_WARNING.get(key);
            if (seen != null && now - seen[0] < SAME_WARNING_GAP_MS) {
                seen[1]++;
                return;
            }
            skipped = seen == null ? 0 : seen[1];
            if (LAST_WARNING.size() > 500) {
                LAST_WARNING.clear();
            }
            LAST_WARNING.put(key, new long[]{now, 0});
        }
        String[] lines = line.split("\n", WARNING_LINES + 1);
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, WARNING_LINES); i++) {
            text.append(lines[i]).append('\n');
        }
        if (skipped > 0) {
            text.append("    (").append(skipped)
                    .append(" identical messages in the minute before this one were not saved)\n");
        }
        final String entry = text.toString();
        warningWriter().post(() -> appendWarning(context, entry));
    }

    private static synchronized Handler warningWriter() {
        if (warningWriter == null) {
            HandlerThread thread = new HandlerThread("AppLog-warnings");
            thread.start();
            warningWriter = new Handler(thread.getLooper());
        }
        return warningWriter;
    }

    private static void appendWarning(Context context, String entry) {
        try {
            File file = new File(getLogDirectory(context), WARNINGS_LOG);
            if (file.length() > WARNINGS_LOG_LIMIT_BYTES) {
                byte[] all = java.nio.file.Files.readAllBytes(file.toPath());
                int from = all.length / 2;
                while (from < all.length && all[from] != '\n') {
                    from++;
                }
                java.nio.file.Files.write(file.toPath(),
                        java.util.Arrays.copyOfRange(all, Math.min(all.length, from + 1), all.length));
            }
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(file, true), StandardCharsets.UTF_8)) {
                writer.write(entry);
            }
        } catch (IOException | RuntimeException e) {
            Log.w("AppLog", "warnings.log write failed: " + e);
        }
    }

    /**
     * warnings.log 末尾的若干条，老的在前。一条可能跨几行：堆栈接在后面。诊断报告用。
     */
    public static List<String> readWarnings(Context context, int maxEntries) {
        List<String> entries = new ArrayList<>();
        if (context == null) {
            return entries;
        }
        File file = new File(getLogDirectory(context), WARNINGS_LOG);
        if (!file.isFile()) {
            return entries;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder current = null;
            String line;
            while ((line = reader.readLine()) != null) {
                // 一条以时间戳开头：2026-09-26 19:05:50.123 E/Tag: ...
                boolean starts = line.length() > 10 && Character.isDigit(line.charAt(0))
                        && line.charAt(4) == '-';
                if (starts || current == null) {
                    if (current != null) {
                        entries.add(current.toString());
                    }
                    current = new StringBuilder(line);
                } else {
                    current.append('\n').append(line);
                }
            }
            if (current != null) {
                entries.add(current.toString());
            }
        } catch (IOException e) {
            Log.w("AppLog", "warnings.log read failed: " + e);
        }
        int from = Math.max(0, entries.size() - Math.max(0, maxEntries));
        return new ArrayList<>(entries.subList(from, entries.size()));
    }
}
