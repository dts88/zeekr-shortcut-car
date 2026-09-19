package com.kooo.evcam.zeekr;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 熄火 / 关机时，应用到底有没有收到通知。
 *
 * <h3>要回答的问题</h3>
 *
 * <p>断电时正在录的那一段之所以打不开，是因为 MP4 的索引要等 {@code stop()} 那一刻才写。
 * 成熟的行车记录仪靠机身里的超级电容撑那几秒，把文件正常关掉 —— 我们没有电容，
 * 但如果<b>车机在断电前会广播一声</b>，那干干净净地停一次录制只要一两百毫秒，
 * 一帧都不会丢，比改封装、改索引都省事得多。</p>
 *
 * <p>所以先探：熄一次火，看这里记下了什么。</p>
 *
 * <h3>怎么探</h3>
 *
 * <ul>
 *   <li>关机族的广播（{@code ACTION_SHUTDOWN} / {@code ACTION_REBOOT} /
 *       厂商的 {@code QUICKBOOT_POWEROFF}）—— 收到就<b>同步落盘</b>，
 *       因为这时候系统随时会把进程带走，异步写很可能来不及；</li>
 *   <li>电源、熄屏这些作为上下文一起记 —— 判断「熄火」在这台车机上究竟长什么样；</li>
 *   <li>录制期间每 30 秒留一个「还活着」的时刻。下次启动一对照就知道：
 *       断电前最后一次活着是几点、那之后有没有来过任何信号。</li>
 * </ul>
 *
 * <h3>只记，不动作</h3>
 *
 * <p>这一版<b>收到关机信号也不会去停录制</b>。有些车机 ROM 会在别的时候也广播一次关机，
 * 真按它停录，就成了「录着录着自己停了」。先拿到实车数据，确认这个信号只在真关机时来，
 * 再接动作 —— 到那时也就是一行调用的事。</p>
 *
 * <p>记录写在应用私有的 SharedPreferences 里，重启后还在，诊断报告会带上。</p>
 */
public final class ShutdownProbe {

    private static final String TAG = "ShutdownProbe";
    private static final String PREFS = "shutdown_probe";
    private static final String KEY_EVENTS = "events";
    private static final String KEY_ALIVE_AT = "alive_at";
    private static final String KEY_ALIVE_RECORDING = "alive_recording";

    /** 留最近这么多条。再多也看不过来，关键是最后几条。 */
    private static final int MAX_EVENTS = 16;

    /** 「还活着」最多这么密地写一次，免得被频繁调用时反复写盘。 */
    private static final long ALIVE_MIN_GAP_MS = 20_000L;

    private static long lastAliveWriteMs;

    private ShutdownProbe() {
    }

    /**
     * 记一条信号。
     *
     * @param urgent 关机族的广播传 true —— 同步写盘，不赌系统还给不给我们时间
     */
    public static void note(Context context, String action, boolean urgent) {
        if (context == null || action == null) {
            return;
        }
        try {
            SharedPreferences prefs = prefs(context);
            List<String> events = read(prefs);
            events.add(stamp() + " | " + action + " | " + recordingState()
                    + " | " + SystemClock.elapsedRealtime());
            while (events.size() > MAX_EVENTS) {
                events.remove(0);
            }
            SharedPreferences.Editor editor = prefs.edit()
                    .putString(KEY_EVENTS, join(events));
            if (urgent) {
                editor.commit();
                AppLog.w(TAG, "收到关机族广播 " + action + "（录制中=" + recordingState()
                        + "），已同步记录");
            } else {
                editor.apply();
            }
        } catch (Exception e) {
            AppLog.w(TAG, "记录失败: " + e);
        }
    }

    /** 录制期间定期留一个「还活着」的时刻。 */
    public static void heartbeat(Context context, boolean recording) {
        if (context == null) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (lastAliveWriteMs != 0 && now - lastAliveWriteMs < ALIVE_MIN_GAP_MS) {
            return;
        }
        lastAliveWriteMs = now;
        try {
            prefs(context).edit()
                    .putString(KEY_ALIVE_AT, stamp())
                    .putBoolean(KEY_ALIVE_RECORDING, recording)
                    .apply();
        } catch (Exception e) {
            AppLog.w(TAG, "心跳写入失败: " + e);
        }
    }

    /** 诊断报告里的那一节。 */
    public static String describe(Context context) {
        StringBuilder sb = new StringBuilder();
        try {
            SharedPreferences prefs = prefs(context);
            String aliveAt = prefs.getString(KEY_ALIVE_AT, null);
            sb.append("最后一次「还活着」: ")
                    .append(aliveAt == null ? "还没记过（这一趟没录制过）" : aliveAt)
                    .append(aliveAt == null ? ""
                            : (prefs.getBoolean(KEY_ALIVE_RECORDING, false)
                            ? "（录制中）" : "（未录制）"))
                    .append('\n');

            List<String> events = read(prefs);
            if (events.isEmpty()) {
                sb.append("还没收到过任何电源 / 关机相关广播。\n");
                sb.append("如果熄过火再开机也依然是这一行，说明这台车机不给应用关机通知，\n");
                sb.append("「断电前干净地停一次录制」这条路走不通。\n");
                return sb.toString();
            }
            sb.append("收到过的信号（新的在下面）:\n");
            for (String event : events) {
                String[] parts = event.split(" \\| ", -1);
                if (parts.length < 3) {
                    continue;
                }
                sb.append("  ").append(parts[0]).append("  ").append(pad(parts[1]))
                        .append("  录制中=").append(parts[2]).append('\n');
            }
        } catch (Exception e) {
            sb.append("!! 读取失败: ").append(e).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static List<String> read(SharedPreferences prefs) {
        List<String> events = new ArrayList<>();
        String raw = prefs.getString(KEY_EVENTS, "");
        if (raw != null && !raw.isEmpty()) {
            for (String line : raw.split("\n")) {
                if (!line.isEmpty()) {
                    events.add(line);
                }
            }
        }
        return events;
    }

    private static String join(List<String> events) {
        StringBuilder sb = new StringBuilder();
        for (String event : events) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(event);
        }
        return sb.toString();
    }

    private static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    /** 那一刻在不在录制。拿不到就写 unknown，不为了一行日志去碰相机。 */
    private static String recordingState() {
        try {
            com.kooo.evcam.camera.MultiCameraManager manager =
                    com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
            return manager == null ? "unknown" : String.valueOf(manager.isRecording());
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String pad(String action) {
        String shortened = action.startsWith("android.intent.action.")
                ? action.substring("android.intent.action.".length()) : action;
        StringBuilder sb = new StringBuilder(shortened);
        while (sb.length() < 22) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
