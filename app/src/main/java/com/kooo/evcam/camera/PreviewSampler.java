package com.kooo.evcam.camera;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import com.kooo.evcam.AppLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 每秒记一次「这一秒各路预览出了多少帧、相机开着几路、人在哪个界面」。
 *
 * <h3>为什么要它</h3>
 *
 * <p>要回答的问题是：切到别的界面、退到车机桌面之后，预览这一路的资源
 * 是不是还占着。人拿秒表在两个界面之间来回切、再去比两次累计帧数，
 * 误差比要测的东西还大。</p>
 *
 * <p>所以让它自己在后台采：开始采样之后随便怎么切界面，采完看那张表 ——
 * 每一秒的帧率旁边写着当时在哪个界面，界限一目了然。</p>
 *
 * <h3>帧率是算出来的，不是读出来的</h3>
 *
 * <p>用的是 {@link PreviewFrameRates} 的<b>累计帧数差</b>，不是它那个滑动窗口的
 * 读数 —— 窗口读数在没有新帧时会停在最后一个值上，正好在「停了没有」这个
 * 问题上给出错误答案。累计帧数不会：不动就是不动。</p>
 */
public final class PreviewSampler {

    private static final String TAG = "PreviewSampler";

    /** 采样间隔。一秒足够看清界限，也不会把表撑得太长。 */
    private static final long INTERVAL_MS = 1000L;

    /** 最多采这么久，忘了停也不会一直跑下去。 */
    private static final long MAX_DURATION_MS = 10 * 60 * 1000L;

    /** 一条采样。 */
    public static final class Sample {
        public final long elapsedMs;
        public final String screen;
        public final int camerasOpen;
        public final boolean recording;
        /** 这一秒各路各出了多少帧。 */
        public final Map<String, Long> framesPerLane;

        Sample(long elapsedMs, String screen, int camerasOpen, boolean recording,
               Map<String, Long> framesPerLane) {
            this.elapsedMs = elapsedMs;
            this.screen = screen;
            this.camerasOpen = camerasOpen;
            this.recording = recording;
            this.framesPerLane = framesPerLane;
        }
    }

    /** 采样期间要问的那几个运行时状态。由调用方接上，这个类不认识相机管理器。 */
    public interface StateProbe {
        int camerasOpen();

        boolean recording();
    }

    private static final List<Sample> SAMPLES = new ArrayList<>();
    private static HandlerThread thread;
    private static Handler handler;
    private static volatile boolean running;
    private static long startedAt;
    private static StateProbe probe;
    private static Map<String, Long> lastTotals = new LinkedHashMap<>();

    private PreviewSampler() {
    }

    public static synchronized boolean isRunning() {
        return running;
    }

    public static synchronized void start(StateProbe stateProbe) {
        if (running) {
            return;
        }
        SAMPLES.clear();
        lastTotals = snapshotTotals();
        probe = stateProbe;
        startedAt = SystemClock.elapsedRealtime();
        running = true;
        thread = new HandlerThread("preview-sampler");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.postDelayed(PreviewSampler::tick, INTERVAL_MS);
        AppLog.i(TAG, "开始采样预览资源占用");
    }

    public static synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        if (thread != null) {
            thread.quitSafely();
            thread = null;
            handler = null;
        }
        AppLog.i(TAG, "停止采样，共 " + SAMPLES.size() + " 条");
    }

    private static void tick() {
        synchronized (PreviewSampler.class) {
            if (!running) {
                return;
            }
            long elapsed = SystemClock.elapsedRealtime() - startedAt;

            Map<String, Long> totals = snapshotTotals();
            Map<String, Long> delta = new LinkedHashMap<>();
            for (Map.Entry<String, Long> entry : totals.entrySet()) {
                Long before = lastTotals.get(entry.getKey());
                delta.put(entry.getKey(), entry.getValue() - (before == null ? 0L : before));
            }
            lastTotals = totals;

            SAMPLES.add(new Sample(elapsed, AppScreenState.current(),
                    probe == null ? -1 : probe.camerasOpen(),
                    probe != null && probe.recording(), delta));

            if (elapsed >= MAX_DURATION_MS) {
                AppLog.i(TAG, "到达采样上限，自动停止");
                running = false;
                return;
            }
            if (handler != null) {
                handler.postDelayed(PreviewSampler::tick, INTERVAL_MS);
            }
        }
    }

    private static Map<String, Long> snapshotTotals() {
        Map<String, Long> totals = new LinkedHashMap<>();
        for (String key : new String[]{"front", "back", "left", "right"}) {
            long frames = PreviewFrameRates.totalFrames(key);
            if (frames > 0) {
                totals.put(key, frames);
            }
        }
        return totals;
    }

    public static synchronized List<Sample> samples() {
        return new ArrayList<>(SAMPLES);
    }

    /**
     * 把采样结果压成「每个界面停留了多久、期间平均多少帧」。
     *
     * <p>这才是要看的东西 —— 逐秒那张表是给不信这个汇总的时候查的。</p>
     */
    public static synchronized String summarise() {
        List<Sample> samples = samples();
        if (samples.isEmpty()) {
            return "还没有采到任何样本。";
        }
        StringBuilder sb = new StringBuilder();
        String currentScreen = null;
        int seconds = 0;
        Map<String, Long> frames = new LinkedHashMap<>();
        int camerasOpen = -1;
        boolean recording = false;

        for (int i = 0; i <= samples.size(); i++) {
            Sample sample = i < samples.size() ? samples.get(i) : null;
            boolean boundary = sample == null || !sample.screen.equals(currentScreen);
            if (boundary && currentScreen != null) {
                sb.append(describeSegment(currentScreen, seconds, frames, camerasOpen, recording));
            }
            if (sample == null) {
                break;
            }
            if (boundary) {
                currentScreen = sample.screen;
                seconds = 0;
                frames = new LinkedHashMap<>();
            }
            seconds++;
            camerasOpen = sample.camerasOpen;
            recording = sample.recording;
            for (Map.Entry<String, Long> entry : sample.framesPerLane.entrySet()) {
                Long sum = frames.get(entry.getKey());
                frames.put(entry.getKey(), (sum == null ? 0L : sum) + entry.getValue());
            }
        }
        return sb.toString();
    }

    private static String describeSegment(String screen, int seconds, Map<String, Long> frames,
                                          int camerasOpen, boolean recording) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%-10s 停留 %d 秒   相机开着 %s%s%n",
                screen, seconds,
                camerasOpen < 0 ? "未知" : camerasOpen + " 路",
                recording ? "   录制中" : ""));
        if (frames.isEmpty()) {
            sb.append("           这段时间一帧都没出\n");
            return sb.toString();
        }
        for (Map.Entry<String, Long> entry : frames.entrySet()) {
            float fps = seconds > 0 ? entry.getValue() / (float) seconds : 0f;
            sb.append(String.format(Locale.US, "           %-6s %6d 帧   平均 %.1f fps%n",
                    entry.getKey(), entry.getValue(), fps));
        }
        return sb.toString();
    }
}
