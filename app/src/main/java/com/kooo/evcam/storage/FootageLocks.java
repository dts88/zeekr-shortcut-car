package com.kooo.evcam.storage;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.camera.StoragePlan;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 锁定影像：锁定的录像、照片不会被自动清理删掉，回放里也不能直接删（先解锁）。规则见 docs/storage-spec.md。
 *
 * <h3>清单放在 U 盘上</h3>
 *
 * <p>和影像同一个目录：{@code DCIM/EVCam_Video/locked.txt}、{@code DCIM/EVCam_Photo/locked.txt}，
 * 一行一个文件名。换盘、重装、清除数据都还在；插到电脑上也能打开看锁了哪些。
 * 锁的是文件：录像是这一刻各路正在放的那几个文件，照片是同一次拍摄的一整组。</p>
 *
 * <h3>谁读、谁写</h3>
 *
 * <ul>
 *   <li>改清单（{@link #set}）在自己的线程上写：先写临时文件再改名，拔盘也不会写坏一半。</li>
 *   <li>自动清理「读清单 → 决定 → 删」整段拿着 {@link #guard()}，中间没人改得了清单 ——
 *       否则刚锁上的文件可能正好在这一轮的删除名单里。</li>
 *   <li>开关关着时清理不看清单（{@link #protectedNames} 给空集合），清单本身留着，重新打开恢复保护；
 *       被删掉的文件从清单里拿掉（{@link #forget}）。</li>
 * </ul>
 */
public final class FootageLocks {

    private static final String TAG = "FootageLocks";

    /** 清单的文件名。 */
    public static final String FILE_NAME = "locked.txt";
    private static final String HEADER =
            "# Zeekr Shortcut: locked footage, one file name per line. Never deleted automatically.";

    private static final Object GUARD = new Object();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "FootageLocks");
        thread.setDaemon(true);
        return thread;
    });
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    private FootageLocks() {
    }

    // ================================================================= 纯函数

    /** 清单文本 → 文件名。空行、# 开头的行、不是本应用文件名的行都不算。 */
    static Set<String> parse(String text) {
        Set<String> names = new TreeSet<>();
        if (text == null) {
            return names;
        }
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (StoragePlan.isOwnClip(line) || StoragePlan.isOwnPhoto(line)) {
                names.add(line);
            }
        }
        return names;
    }

    /** 文件名 → 清单文本（按名字排序，就是按时间排序）。 */
    static String format(Collection<String> names) {
        StringBuilder sb = new StringBuilder(HEADER).append('\n');
        for (String name : new TreeSet<>(names)) {
            sb.append(name).append('\n');
        }
        return sb.toString();
    }

    // ================================================================= 读

    /**
     * 清单里的文件名（不看开关）。没有清单是空集合；有清单但读不出来返回 null。
     */
    public static Set<String> read(File dir) {
        File file = dir == null ? null : new File(dir, FILE_NAME);
        if (file == null || !file.isFile()) {
            return new TreeSet<>();
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return parse(sb.toString());
        } catch (IOException e) {
            AppLog.w(TAG, "读不出锁定清单 " + file + ": " + e);
            return null;
        }
    }

    /** 「锁定影像」开着没有。 */
    public static boolean enabled(Context context) {
        return new AppConfig(context).isFootageLockEnabled();
    }

    /**
     * 自动清理不能删的文件名。开关关着是空集合；清单读不出来返回 null —— 调用方这一轮不删。
     * 在 {@link #guard()} 里调。
     */
    public static Set<String> protectedNames(Context context, File dir) {
        return enabled(context) ? read(dir) : Collections.<String>emptySet();
    }

    /** 回放里用：开关开着时清单里的名字，关着或读不出时空集合。 */
    public static Set<String> shown(Context context, File dir) {
        if (!enabled(context)) {
            return new TreeSet<>();
        }
        Set<String> names = read(dir);
        return names != null ? names : new TreeSet<String>();
    }

    /** 自动清理「读清单 → 决定 → 删」整段拿着它。 */
    public static Object guard() {
        return GUARD;
    }

    // ================================================================= 写

    /** 锁 / 解锁的结果，在主线程上回调。 */
    public interface Result {
        void done(boolean ok);
    }

    /**
     * 锁上或解开这几个文件（{@code dir} 里的文件名）。后台写，结果回主线程；成功后通知 {@link #addListener} 的人。
     */
    public static void set(File dir, Collection<String> names, boolean locked, Result result) {
        final List<String> copy = new ArrayList<>(names);
        IO.execute(() -> {
            boolean ok;
            synchronized (GUARD) {
                ok = update(dir, copy, locked);
            }
            com.kooo.evcam.blackbox.BlackBox.noteImportant((locked ? "锁定影像：锁上 " : "锁定影像：解开 ")
                    + copy.size() + " 个文件" + (ok ? "" : "（没写进去）") + " " + copy);
            new Handler(Looper.getMainLooper()).post(() -> {
                if (result != null) {
                    result.done(ok);
                }
                if (ok) {
                    for (Runnable listener : LISTENERS) {
                        listener.run();
                    }
                }
            });
        });
    }

    /**
     * 删掉了的文件从清单里拿掉（开关关着时自动清理删了锁定的，或者回放里删了别的）。
     * 在清理线程上调，已经拿着 {@link #guard()}。
     */
    public static void forget(File dir, Collection<String> deletedNames) {
        if (deletedNames.isEmpty()) {
            return;
        }
        Set<String> current = read(dir);
        if (current == null) {
            return;
        }
        List<String> listed = new ArrayList<>();
        for (String name : deletedNames) {
            if (current.contains(name)) {
                listed.add(name);
            }
        }
        if (!listed.isEmpty()) {
            update(dir, listed, false);
            AppLog.i(TAG, "清单里拿掉已删掉的 " + listed.size() + " 个文件");
        }
    }

    private static boolean update(File dir, Collection<String> names, boolean locked) {
        if (dir == null || !dir.isDirectory()) {
            return false;
        }
        Set<String> current = read(dir);
        if (current == null) {
            // 读不出来就别写：写了等于把原来锁的全丢掉
            return false;
        }
        boolean changed = locked ? current.addAll(names) : current.removeAll(names);
        if (!changed) {
            return true;
        }
        File file = new File(dir, FILE_NAME);
        if (current.isEmpty()) {
            return !file.exists() || file.delete();
        }
        File tmp = new File(dir, FILE_NAME + ".tmp");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            writer.write(format(current));
        } catch (IOException e) {
            AppLog.w(TAG, "锁定清单写不进 " + tmp + ": " + e);
            tmp.delete();
            return false;
        }
        if (tmp.renameTo(file)) {
            return true;
        }
        // 有的文件系统不让改名盖掉已有的文件：先删再改名
        return file.delete() && tmp.renameTo(file);
    }

    // ================================================================= 统计

    /** 清单里还在盘上的那些文件：几个、多大。 */
    public static final class Usage {
        public final int files;
        public final long bytes;

        Usage(int files, long bytes) {
            this.files = files;
            this.bytes = bytes;
        }
    }

    /** 不看开关。读不出清单当作没有。会碰盘，别在主线程上调。 */
    public static Usage usage(File dir) {
        Set<String> names = read(dir);
        int files = 0;
        long bytes = 0;
        if (names != null && dir != null) {
            for (String name : names) {
                File file = new File(dir, name);
                if (file.isFile()) {
                    files++;
                    bytes += file.length();
                }
            }
        }
        return new Usage(files, bytes);
    }

    // ================================================================= 通知

    /** 锁定、解锁成功后在主线程上叫一次。用完 {@link #removeListener}。 */
    public static void addListener(Runnable listener) {
        LISTENERS.add(listener);
    }

    public static void removeListener(Runnable listener) {
        LISTENERS.remove(listener);
    }
}
