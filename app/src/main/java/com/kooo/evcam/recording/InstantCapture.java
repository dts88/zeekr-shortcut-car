package com.kooo.evcam.recording;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.FileTransferManager;
import com.kooo.evcam.R;
import com.kooo.evcam.StorageHelper;
import com.kooo.evcam.camera.CameraSlots;
import com.kooo.evcam.camera.StoragePlan;
import com.kooo.evcam.profile.RecordSpecs;
import com.kooo.evcam.repair.Mp4Repair;
import com.kooo.evcam.share.DriveExport;
import com.kooo.evcam.share.SurroundDefish;
import com.kooo.evcam.share.UsbExport;
import com.kooo.evcam.storage.LockWindow;
import com.kooo.evcam.zeekr.RecordingTimeline;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Flash-to-pass writes the surround view, 10 seconds before the flash and 10
 * seconds after, into {@code <usb>/instant captures}. Other cameras and the
 * rest of the trip stay in the video folder. Drive upload follows the
 * save-moment Drive switch.
 */
public final class InstantCapture {

    private static final String TAG = "InstantCapture";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Object GATE = new Object();

    private InstantCapture() {
    }

    public static void perform(Context context) {
        if (context == null) {
            return;
        }
        Context app = context.getApplicationContext();
        long momentMs = System.currentTimeMillis();
        toast(app, app.getString(R.string.instant_capture_start));
        new Thread(() -> {
            synchronized (GATE) {
                try {
                    run(app, momentMs);
                } catch (Exception e) {
                    AppLog.e(TAG, "即时片段失败", e);
                    toast(app, app.getString(R.string.instant_capture_failed));
                }
            }
        }, "instant-capture").start();
    }

    private static void run(Context app, long momentMs) {
        LockWindow window = LockWindow.around(momentMs);
        long longest = longestSegment(app);
        long deadline = window.endMs + longest * 2L + 15_000L;
        List<File> sources = new ArrayList<>();
        while (true) {
            long now = System.currentTimeMillis();
            if (now >= window.endMs + LockWindow.SLACK_MS) {
                List<File> ready = closedOverlap(app, window);
                if (ready != null) {
                    sources = ready;
                    break;
                }
            }
            if (now >= deadline) {
                sources = playableOverlap(app, window);
                break;
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        sources = oneClip(sources, momentMs);
        if (sources.isEmpty()) {
            toast(app, app.getString(R.string.instant_capture_failed));
            return;
        }
        File cache = new File(app.getCacheDir(), "instant");
        if (!cache.exists() && !cache.mkdirs()) {
            toast(app, app.getString(R.string.instant_capture_failed));
            return;
        }
        List<File> clips = new ArrayList<>();
        try {
            clips.addAll(cut(app, cache, sources, window, momentMs));
            if (clips.isEmpty()) {
                toast(app, app.getString(R.string.instant_capture_failed));
                return;
            }
            int copied = copyToUsb(app, clips);
            if (copied > 0) {
                final int count = copied;
                toast(app, app.getString(R.string.instant_capture_saved, count));
            } else if (StorageHelper.getExternalSdCardRoot(app) == null) {
                toast(app, app.getString(R.string.save_moment_no_usb));
            } else {
                toast(app, app.getString(R.string.instant_capture_failed));
            }
            AppConfig config = new AppConfig(app);
            boolean drive = config.isSaveMomentDrive()
                    && config.hasDriveClient() && config.hasDriveRefreshToken();
            if (drive) {
                int sent = DriveExport.uploadQuiet(app, clips, false);
                if (sent > 0) {
                    final int count = sent;
                    toast(app, app.getString(R.string.instant_capture_uploaded, count));
                } else {
                    toast(app, app.getString(R.string.instant_capture_upload_failed));
                }
            }
        } finally {
            for (File clip : clips) {
                if (clip != null && clip.exists() && !clip.delete()) {
                    AppLog.w(TAG, "删不掉临时片段: " + clip.getAbsolutePath());
                }
            }
        }
    }

    /** Null while a file that overlaps the window is still open. */
    private static List<File> closedOverlap(Context app, LockWindow window) {
        List<File> hits = candidates(app, window);
        List<File> ready = new ArrayList<>();
        for (File file : hits) {
            long start = RecordingTimeline.parseStartEpochMs(file.getName());
            if (start < 0 || start >= window.endMs) {
                continue;
            }
            if (InstantSpan.inFile(start, window.startMs, window.endMs) == null) {
                continue;
            }
            if (!UsbExport.playable(file)) {
                return null;
            }
            ready.add(file);
        }
        return ready;
    }

    private static List<File> playableOverlap(Context app, LockWindow window) {
        List<File> ready = new ArrayList<>();
        for (File file : candidates(app, window)) {
            long start = RecordingTimeline.parseStartEpochMs(file.getName());
            if (start < 0 || InstantSpan.inFile(start, window.startMs, window.endMs) == null) {
                continue;
            }
            if (UsbExport.playable(file)) {
                ready.add(file);
            }
        }
        return ready;
    }

    private static List<File> candidates(Context app, LockWindow window) {
        List<File> dirs = new ArrayList<>();
        File video = StorageHelper.getVideoDir(app);
        if (video != null) {
            dirs.add(video);
        }
        dirs.add(new File(app.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR));
        Set<String> names = new LinkedHashSet<>();
        Map<String, File> byName = new HashMap<>();
        for (File dir : dirs) {
            File[] listed = dir.listFiles();
            if (listed == null) {
                continue;
            }
            for (File file : listed) {
                if (!file.isFile() || !StoragePlan.isOwnClip(file.getName()) || !surround(file)) {
                    continue;
                }
                names.add(file.getName());
                File previous = byName.get(file.getName());
                if (previous == null || video != null && video.equals(file.getParentFile())) {
                    byName.put(file.getName(), file);
                }
            }
        }
        Map<String, Long> lengths = segmentLengths(app);
        Set<String> hit = window.overlapping(names, lengths, longest(lengths));
        List<File> out = new ArrayList<>();
        for (String name : hit) {
            File file = byName.get(name);
            if (file != null) {
                out.add(file);
            }
        }
        return out;
    }

    /** One flash, one file: the segment that contains the flash, not its neighbor. */
    private static List<File> oneClip(List<File> sources, long momentMs) {
        long[] starts = new long[sources.size()];
        for (int i = 0; i < sources.size(); i++) {
            starts[i] = RecordingTimeline.parseStartEpochMs(sources.get(i).getName());
        }
        int index = InstantSpan.containing(starts, momentMs);
        if (index < 0 || index >= sources.size()) {
            return new ArrayList<>();
        }
        return java.util.Collections.singletonList(sources.get(index));
    }

    private static List<File> cut(Context app, File cache, List<File> sources,
                                  LockWindow window, long momentMs) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(momentMs));
        Set<String> used = new LinkedHashSet<>();
        List<File> clips = new ArrayList<>();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        for (File source : sources) {
            long start = RecordingTimeline.parseStartEpochMs(source.getName());
            InstantSpan span = InstantSpan.inFile(start, window.startMs, window.endMs);
            if (span == null) {
                continue;
            }
            String slot = RecordingTimeline.parseCameraSlot(source.getName());
            if (slot == null || slot.isEmpty()) {
                slot = "cam";
            }
            String name = unique(used, stamp + "_" + slot + ".mp4");
            File raw = new File(cache, name + ".part");
            File clip = new File(cache, name);
            try {
                InstantClip.write(source, raw, span.startUs, span.endUs);
                if (!UsbExport.playable(raw)) {
                    throw new java.io.IOException("unfinished " + name);
                }
                File payload = raw;
                if (SurroundDefish.wanted(app, source)) {
                    SurroundDefish.write(app, raw, clip, cancelled, null);
                    if (!UsbExport.playable(clip)) {
                        throw new java.io.IOException("unfinished " + name);
                    }
                    if (!raw.delete()) {
                        AppLog.w(TAG, "删不掉未拉直的片段: " + raw.getAbsolutePath());
                    }
                    payload = clip;
                } else if (!raw.renameTo(clip)) {
                    throw new java.io.IOException("rename " + raw.getAbsolutePath());
                }
                clips.add(payload);
            } catch (Exception e) {
                AppLog.e(TAG, "切即时片段失败: " + source.getAbsolutePath(), e);
                if (raw.exists() && !raw.delete()) {
                    AppLog.w(TAG, "删不掉半成品: " + raw.getAbsolutePath());
                }
                if (clip.exists() && !clips.contains(clip) && !clip.delete()) {
                    AppLog.w(TAG, "删不掉半成品: " + clip.getAbsolutePath());
                }
            }
        }
        return clips;
    }

    private static int copyToUsb(Context app, List<File> clips) {
        File root = StorageHelper.getExternalSdCardRoot(app);
        if (root == null) {
            AppLog.w(TAG, "要存即时片段，但没有 U 盘");
            return 0;
        }
        File dest = UsbExport.instantCaptures(root);
        int copied = 0;
        for (File clip : clips) {
            try {
                UsbExport.copy(clip, dest);
                copied++;
            } catch (Exception e) {
                AppLog.e(TAG, "拷到 instant captures 失败: " + clip.getAbsolutePath(), e);
            }
        }
        AppLog.i(TAG, "即时片段拷到 U 盘 " + copied + "/" + clips.size());
        return copied;
    }

    private static String unique(Set<String> used, String name) {
        if (used.add(name)) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        int n = 2;
        String next;
        do {
            next = stem + "_" + n + ext;
            n++;
        } while (!used.add(next));
        return next;
    }

    static boolean snapshotOpenFile(Mp4Repair.Status status) {
        return false;
    }

    static File instantFolder(File usbRoot, File videoDir) {
        return new File(usbRoot, UsbExport.INSTANT_DIR);
    }

    /** 闪远光要的是车外那一路。座舱录像留在录像目录里。 */
    private static boolean surround(File file) {
        String slot = RecordingTimeline.parseCameraSlot(file.getName());
        if (slot == null) {
            return false;
        }
        return CameraSlots.SURROUND.equals(
                CameraSlots.canonical(slot.toLowerCase(Locale.US)));
    }

    private static Map<String, Long> segmentLengths(Context context) {
        Map<String, Long> out = new HashMap<>();
        for (String key : RecordSpecs.enabledCameraKeys(context)) {
            out.put(CameraSlots.suffixFor(key),
                    RecordSpecs.segmentMs(RecordSpecs.forCameraKey(context, key).segmentMinutes));
        }
        return out;
    }

    private static long longest(Map<String, Long> lengths) {
        long longest = RecordSpecs.segmentMs(RecordSpecs.DEFAULT_SEGMENT_MINUTES);
        for (Long ms : lengths.values()) {
            if (ms != null && ms > longest) {
                longest = ms;
            }
        }
        return longest;
    }

    private static long longestSegment(Context context) {
        return longest(segmentLengths(context));
    }

    private static void toast(Context app, String text) {
        MAIN.post(() -> Toast.makeText(app, text, Toast.LENGTH_SHORT).show());
    }
}
