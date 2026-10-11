package com.kooo.evcam.camera;


import com.kooo.evcam.AppLog;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * MediaRecorder 模式一路的录制器（开发者选项里保留的非默认模式：MediaRecorder 直接吃相机输出，没有 GL 这一步）。
 *
 * <h3>2.11 起和编码录制器走同一套按路进出（channel-logic §三，大纲阶段 5d）</h3>
 *
 * <p>这里从不碰相机：只备好 MediaRecorder、交出它的 Surface，由 MultiCameraManager 声明成这一路的录像输出；
 * 什么时候带进会话、什么时候摘掉，都由通道调度一路一路地做。相机带着它出了画面，这一路加入（{@link #start}）；
 * 相机走了，这一路自己收尾退出（{@link #stop}），回来时另备一个再加入（{@link RecordingLanes}）。
 * 以前开录、分段、看门狗重建都是录像这边自己去设录像 Surface、重建会话，是调度之外的又一个动手方。</p>
 *
 * <p>和编码录制器（{@link CodecVideoRecorder}）不一样的地方，都出在 MediaRecorder 本身：</p>
 * <ul>
 *   <li><b>不能停放</b>：停下的 MediaRecorder 不再取帧，它的 Surface 还在会话里的话，相机服务会把那一路整条重复请求停掉
 *       （Camera3Device checkAndStopRepeatingRequest）。所以停了就放掉；叫停时先把交出去的 Surface 放掉 —— 一放，
 *       相机层就认得出这个录像输出死了，调度马上摘（{@code ChannelRules} 的「摘死输出」）；</li>
 *   <li><b>文件名在备好时就定了</b>（setOutputFile 在 prepare 之前）：每一趟（这一路加入一次）的第一段按备好那一刻起名。
 *       所以宿主只在这一路的相机在出画面、或者正要开时才备（{@link RecordingLanes#keepsNamedRecorder}），
 *       备好到开录一般不到一两秒；</li>
 *   <li><b>分段要换一个新的 MediaRecorder</b>：分段时间到了先备好下一段（新的 Surface），告诉宿主（{@link SegmentOutputs}）——
 *       宿主把新的声明进来、旧的不再声明，调度那一步「改输出」先停止往旧的送帧再重配（SingleCamera.applyOutputs）；
 *       旧的离开会话了（{@link #segmentLeft}）才停它、收文件，新的带进会话出了画面（{@link #segmentCarried}）才开。
 *       旧的一直录到调度换会话那一下，中间不空着。以前是先让相机层停止往旧的送帧、停旧的、再重建会话，
 *       等会话配好的回调里开新的。</li>
 * </ul>
 *
 * <h3>写不进</h3>
 *
 * <p>每一段开录后看文件长没长：开录 {@link #FIRST_WRITE_TIMEOUT_MS} 还没写进第一笔，或者写进过之后
 * {@link #NO_WRITE_MS} 没再长，报 {@link RecordCallback#onWriteStalled}；新的一段开不起来也这样报。
 * 停不停、停一路还是整次由宿主判（{@link RecordingLanes#stallIsCamera}：相机的事只这一路退出；
 * 盘或编码器的事整次停，存储一类，照旧自动恢复）。以前看门狗请求「重建录制」，管线自己停了、隔 500 ms 重开，
 * 第二次起还会改用编码录制 —— 又是录像这边直接动相机；而且文件超过 1 KB（MP4 的头就有 3 KB 多）它就当作在写，
 * 写不进根本判不出来。</p>
 *
 * <h3>线程</h3>
 *
 * <p>MediaRecorder 只在这个录制器自己的线程上动：备、开、换段、停、看文件都排在上面，一次一件。宿主从后台线程调
 * {@link #prepare}、{@link #start}（等它做完，最多 {@link #CALL_WAIT_MS}）；{@link #stop} 不等，结果在
 * {@link Stopping#await} 上等；换段的两声（{@link #segmentLeft}、{@link #segmentCarried}）和 {@link #switchNow}
 * 哪个线程都能调。回调都在这个线程上发。一个录制器一次只有一趟：上一趟停完才能再备（宿主保证，这里也拒）。</p>
 */
public final class VideoRecorder {
    private static final String TAG = "VideoRecorder";

    /**
     * 分段时间戳提供者接口
     * 用于多路摄像头分段切换时使用统一的时间戳
     */
    public interface SegmentTimestampProvider {
        /**
         * 获取当前分段的统一时间戳
         * @return 时间戳字符串，格式为 yyyyMMdd_HHmmss
         */
        String getSegmentTimestamp();
    }

    /** 换段时告诉宿主：下一段备好了（录制器线程上调）。 */
    public interface SegmentOutputs {
        /**
         * 下一段的 MediaRecorder 备好了：它的 Surface 要声明成这一路的录像输出，顶替现在这一段的。
         * 宿主声明好、等现在这一段的 Surface 离开会话了调 {@link #segmentLeft}，新的带进会话出了画面调 {@link #segmentCarried}。
         *
         * @param next    下一段的 Surface（还没开录）
         * @param leaving 现在这一段的 Surface（还在录，离开会话才停）
         */
        void onNextSegment(Surface next, Surface leaving);
    }

    /** 宿主等这个录制器做完一步（备、开）最多等多久；到点没做完的那一步，做完时自己收回（见 {@link #call}）。 */
    public static final long CALL_WAIT_MS = 5_000L;
    /** 开录后第一次看文件：快一点看出写进了第一笔。 */
    private static final long FIRST_CHECK_DELAY_MS = 500L;
    /** 写进过之后多久看一次文件。 */
    private static final long CHECK_INTERVAL_MS = 3_000L;
    /** 写进过之后这么久文件没再长：写不进（和以前「连续 3 次、每次 3 秒」是同一个数）。 */
    private static final long NO_WRITE_MS = 9_000L;
    /** 开录这么久还没写进第一笔：写不进。 */
    private static final long FIRST_WRITE_TIMEOUT_MS = 10_000L;
    /**
     * 文件长到这么大才算写进了第一笔：MediaRecorder 一开录就写 MP4 的头（3 KB 多，给 moov 预留的空位），
     * 画面要攒满一截才落盘，一截至少几十 KB。
     */
    private static final long FIRST_DATA_BYTES = 16 * 1024L;
    /** 收好的文件不到这么大就是没有画面的空壳，删掉（和以前一样）。 */
    private static final long MIN_VALID_FILE_SIZE = 1024L;

    // 编码器分辨率限制（H.264 编码器通常有分辨率上限）
    // 大多数 Android 设备的 H.264 编码器最大支持 4096x4096 或类似
    // 但某些摄像头可能输出超过 5000 宽度的分辨率，导致编码失败
    private static final int MAX_ENCODE_WIDTH = 4096;
    private static final int MAX_ENCODE_HEIGHT = 4096;

    private final String cameraId;
    /** 文件名后缀：surround / cabin_rear / cabin_front（{@link CameraSlots#suffixFor}）。 */
    private final String suffix;
    private final HandlerThread thread;
    private final Handler handler;
    private volatile RecordCallback callback;
    private volatile SegmentOutputs outputs;
    private volatile SegmentTimestampProvider timestampProvider;
    /** 放掉了：不再备。 */
    private volatile boolean released;
    /** 正在写的文件（开录了才有）；没有是 null。黑匣子用。 */
    private volatile String currentPath;
    /**
     * 交出去的 Surface（这一段的、换段时要走的那一段的）：叫停时在调用的线程上当场放掉（{@link #stop}）。
     * 哪个线程都能读；段放掉时拿掉。
     */
    private final java.util.Set<Surface> handedOut = java.util.concurrent.ConcurrentHashMap.newKeySet();

    // ---- 这一趟（只在录制器线程上动）

    private File dir;
    private int encodeWidth;
    private int encodeHeight;
    private int frameRate;
    private int bitrate;
    private long segmentMs;
    /** 这一段：备好了的、在录的。 */
    private Segment cur;
    /** 换段时要走的那一段：还在录，等它离开会话（{@link #segmentLeft}）再停。 */
    private Segment leaving;
    /** 这一趟叫停了（或者还没备过）：之后排到的开录、换段都不做。 */
    private boolean stopped = true;
    /** 这一趟写进过数据。 */
    private boolean everWrote;
    /** 这一趟最后一次文件长了的时刻（uptime）。 */
    private long lastGrewAt;
    /** 这一趟报过写不进：不再看文件，等宿主停。 */
    private boolean stallReported;
    /** 这一趟换过几段。 */
    private int segmentIndex;

    private final Runnable check = this::checkFile;
    private final Runnable segmentTick = () -> beginSwitch("segment");

    /** 一段：一个 MediaRecorder、它的 Surface 和文件。只在录制器线程上动。 */
    private static final class Segment {
        final MediaRecorder recorder;
        final Surface surface;
        final String path;
        /** 名字是共用的分段名字；false = 这一趟的第一段，按备好那一刻起的（和别的路错开着）。 */
        final boolean shared;
        boolean started;
        long startedAt;
        /** 上一次看到的文件大小。 */
        long size;
        /** 写进了第一笔（文件长过 {@link #FIRST_DATA_BYTES}）。 */
        boolean wrote;
        /** 最后一次长了的时刻（uptime）。 */
        long grewAt;

        Segment(MediaRecorder recorder, Surface surface, String path, boolean shared) {
            this.recorder = recorder;
            this.surface = surface;
            this.path = path;
            this.shared = shared;
        }
    }

    /** {@link #prepare} 的结果。 */
    public static final class Prepared {
        /** 相机写进去的 Surface（录像输出的身份就是它）；null = 备不起来。 */
        public final Surface surface;
        /** 第一段的文件（按备好那一刻起名）。 */
        public final String file;
        /** 实际编码的尺寸（超过编码器上限时等比缩过）。 */
        public final int width;
        public final int height;
        /** 备不起来的原因（英文短句，进黑匣子）；备好了是 null。 */
        public final String failure;
        /** 到点没备好：录制器线程卡住了（多半是盘），宿主换一个录制器。 */
        public final boolean stuck;

        Prepared(Surface surface, String file, int width, int height, String failure, boolean stuck) {
            this.surface = surface;
            this.file = file;
            this.width = width;
            this.height = height;
            this.failure = failure;
            this.stuck = stuck;
        }

        static Prepared failed(String why) {
            return new Prepared(null, null, 0, 0, why, false);
        }
    }

    /** 一趟停完的结果。 */
    public static final class Stopped {
        static final Stopped NOTHING = new Stopped(true, Collections.emptyList(), null);

        /** 停完了；false = 到点还没停完（录制器线程卡住了，多半是盘），这个录制器宿主不再用。 */
        public final boolean finished;
        /** 收好的文件（有画面的；空壳已经删了，报过 {@link RecordCallback#onCorruptedFilesDeleted}）。 */
        public final List<String> files;
        /** 没停完时还开着的那个文件（中转写入扫缓存时跳过它）；停完是 null。 */
        public final String held;

        Stopped(boolean finished, List<String> files, String held) {
            this.finished = finished;
            this.files = Collections.unmodifiableList(new ArrayList<>(files));
            this.held = held;
        }
    }

    /** 叫停的那一张（{@link #stop}）：在收拾线程上等结果。 */
    public static final class Stopping {
        private final CountDownLatch done = new CountDownLatch(1);
        private final AtomicReference<Stopped> result = new AtomicReference<>();
        /** 叫停那一刻在写的文件：到点没停完时交出去。 */
        private final String writing;

        Stopping(String writing) {
            this.writing = writing;
        }

        void deliver(Stopped stopped) {
            if (result.compareAndSet(null, stopped)) {
                done.countDown();
            }
        }

        /** 等这一趟停完，最多等到 {@code deadlineUptime}（{@code SystemClock.uptimeMillis()}）。后台线程上调。 */
        public Stopped await(long deadlineUptime) {
            long left = deadlineUptime - SystemClock.uptimeMillis();
            try {
                if (left > 0) {
                    done.await(left, TimeUnit.MILLISECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Stopped got = result.get();
            return got != null ? got : new Stopped(false, Collections.emptyList(), writing);
        }
    }

    /**
     * @param cameraKey 这一路的 key（front / left / back），文件名后缀照它（{@link CameraSlots#suffixFor}）
     */
    public VideoRecorder(String cameraId, String cameraKey) {
        this.cameraId = cameraId;
        this.suffix = CameraSlots.suffixFor(cameraKey);
        thread = new HandlerThread("VideoRecorder-" + cameraId);
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** 这一趟的回调（宿主每备一趟换一份）。 */
    public void setCallback(RecordCallback callback) {
        this.callback = callback;
    }

    /** 换段时告诉谁（宿主每备一趟换一份）。 */
    public void setSegmentOutputs(SegmentOutputs outputs) {
        this.outputs = outputs;
    }

    /**
     * 设置分段时间戳提供者
     * 用于多路摄像头分段切换时使用统一的时间戳，避免时间戳差1秒导致分组错误
     * @param provider 时间戳提供者
     */
    public void setTimestampProvider(SegmentTimestampProvider provider) {
        this.timestampProvider = provider;
    }

    /** 正在写的文件（开录了才有）；没有是 null。黑匣子用。 */
    public String currentFile() {
        return currentPath;
    }

    /** 这个录制器是给哪一台相机的。 */
    public String cameraId() {
        return cameraId;
    }

    // ===================================================================== 宿主调的

    /**
     * 备好这一路的一趟：MediaRecorder 照参数配好、prepare，第一段的文件按此刻起名（文件这时就建出来了）；
     * 交出它的 Surface，宿主声明成录像输出。后台线程上调（开文件、prepare 不能在主线程上），最多等 {@link #CALL_WAIT_MS}；
     * 到点没备好的，备好时自己放掉。上一趟没停完、录制器放掉了，拒。
     *
     * @param size      这一路的画面尺寸（超过编码器上限的等比缩）
     * @param segmentMs 分段时长：从每一段写进第一笔起算
     */
    public Prepared prepare(File dir, Size size, int frameRate, int bitrate, long segmentMs) {
        return call("prepare", () -> {
            if (released) {
                return Prepared.failed("recorder released");
            }
            if (cur != null || leaving != null) {
                return Prepared.failed("previous run not stopped");
            }
            stopped = true;
            String path;
            try {
                if (dir == null || !dir.isDirectory() && !dir.mkdirs()) {
                    return Prepared.failed("no directory " + dir);
                }
                this.dir = dir;
                int[] encode = encodeSize(size.getWidth(), size.getHeight());
                this.encodeWidth = encode[0];
                this.encodeHeight = encode[1];
                this.frameRate = frameRate;
                this.bitrate = bitrate;
                this.segmentMs = segmentMs;
                path = pathNamedAt(System.currentTimeMillis());
                cur = openSegment(path, false);
            } catch (IOException | RuntimeException e) {
                AppLog.e(TAG, "Camera " + cameraId + " MediaRecorder prepare failed", e);
                return Prepared.failed(String.valueOf(e));
            }
            stopped = false;
            everWrote = false;
            stallReported = false;
            segmentIndex = 0;
            lastGrewAt = 0;
            AppLog.d(TAG, "Camera " + cameraId + " prepared " + encodeWidth + "x" + encodeHeight + " @ " + frameRate
                    + "fps, " + (bitrate / 1000) + " Kbps, segment " + (segmentMs / 1000) + " s: " + path);
            return new Prepared(cur.surface, path, encodeWidth, encodeHeight, null, false);
        }, prepared -> {
            // 等的那一方到点不要了：备好的这一个放掉（它的文件还是空的，删掉）
            if (prepared.surface != null && cur != null && cur.surface == prepared.surface) {
                discard(cur);
                cur = null;
                stopped = true;
            }
        }, new Prepared(null, null, 0, 0, "prepare did not finish in " + CALL_WAIT_MS + " ms", true));
    }

    /**
     * 开录：备好的那一段开始录（这一路加入）。后台线程上调，最多等 {@link #CALL_WAIT_MS}。
     * 开不起来的那一段当场放掉；宿主接着叫停这一趟（{@link #stop}）—— 到点才开起来的那一下也由那一次停收。
     *
     * @return false：没开起来（没备好、叫停了、MediaRecorder 不肯开、到点没做完）
     */
    public boolean start() {
        Boolean started = call("start", () -> {
            Segment seg = cur;
            if (stopped || seg == null || seg.started || leaving != null) {
                return false;
            }
            return startSegment(seg);
        }, null, Boolean.FALSE);
        return started != null && started;
    }

    /**
     * 叫停这一趟（不等）。先在调用的线程上把交出去的 Surface 当场放掉 —— 会话里还带着它的，相机层马上认得出这个录像输出
     * 死了，调度马上摘（摘的时候 MediaRecorder 多半还在取帧，相机不会往停了的 MediaRecorder 里送帧，那一路整条重复请求
     * 不会因此停下；正在为它建的会话也不会再带上它）；再在录制器线程上把在录的几段停下、收好文件，备好还没开的放掉
     * （空文件删掉）。之后能再备一趟。哪个线程都能调；调完叫醒调度。
     *
     * @return 等结果的那一张（{@link Stopping#await}）
     */
    public Stopping stop() {
        for (Surface surface : handedOut) {
            releaseQuietly(surface);
        }
        Stopping stopping = new Stopping(currentPath);
        if (!post(() -> stopping.deliver(stopRun()))) {
            stopping.deliver(Stopped.NOTHING);   // 线程没了：手上什么都没有
        }
        return stopping;
    }

    /**
     * 换段：要走的那一段的 Surface 离开了会话（调度那一步「改输出」摘掉了它、或者随相机关了）—— 现在停它、收文件，
     * 报 {@link RecordCallback#onSegmentSwitch}。不是它、这一趟停了的什么都不做。哪个线程都能调。
     */
    public void segmentLeft(Surface surface) {
        post(() -> {
            Segment seg = leaving;
            if (seg == null || seg.surface != surface) {
                return;
            }
            leaving = null;
            List<String> deleted = new ArrayList<>();
            String completed = finish(seg, deleted);
            reportDeleted(deleted);
            AppLog.d(TAG, "Camera " + cameraId + " segment " + (segmentIndex - 1) + " left the session, sealed: "
                    + completed);
            RecordCallback cb = callback;
            if (cb != null) {
                cb.onSegmentSwitch(cameraId, segmentIndex, completed);
            }
        });
    }

    /**
     * 换段：新的一段带进了会话、出了画面 —— 现在开它。开不起来的，这一路录不下去了，照写不进报
     * （{@link RecordCallback#onWriteStalled}，宿主判）。不是它、已经开了、这一趟停了的什么都不做。哪个线程都能调。
     */
    public void segmentCarried(Surface surface) {
        post(() -> {
            Segment seg = cur;
            if (stopped || seg == null || seg.surface != surface || seg.started) {
                return;
            }
            if (!startSegment(seg)) {
                reportStall(SystemClock.uptimeMillis());
            }
        });
    }

    /**
     * 共用的分段名字刚换了一个新的（别的路在切段）：这一路的名字要是还和别的路错开着（这一趟的第一段，
     * 名字是备好那一刻起的），马上换段，拿同一个名字（{@link RecordingLanes#alignsNow}）。名字本来就对得上、
     * 正在换段、还没写进第一笔的什么都不做。哪个线程都能调。
     */
    public void switchNow() {
        post(() -> {
            Segment seg = cur;
            // 在录：开了、写进过第一笔、不在换段 —— 刚开录还没落盘的第一段马上换掉，留下的是没有画面的空壳
            boolean recording = seg != null && seg.started && seg.wrote && leaving == null;
            if (RecordingLanes.alignsNow(seg != null && !seg.shared, recording, stopped)) {
                AppLog.d(TAG, "Camera " + cameraId + " joined off-cycle, switching to the shared segment name");
                beginSwitch("align");
            }
        });
    }

    /**
     * 不再用（管线放了、停不下来被换掉了）：手上这一趟停掉（不等），线程把排着的做完就退。后台线程上调（等线程退出最多 1 秒）。
     */
    public void release() {
        released = true;
        post(this::stopRun);
        thread.quitSafely();
        try {
            thread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AppLog.w(TAG, "Camera " + cameraId + " recorder thread join interrupted");
        }
    }

    // ===================================================================== 录制器线程上

    /** 停这一趟：在录的几段停下、收文件；备好没开的放掉。 */
    private Stopped stopRun() {
        stopped = true;
        handler.removeCallbacks(check);
        handler.removeCallbacks(segmentTick);
        List<String> files = new ArrayList<>();
        List<String> deleted = new ArrayList<>();
        for (Segment seg : new Segment[]{leaving, cur}) {
            if (seg == null) {
                continue;
            }
            if (seg.started) {
                String done = finish(seg, deleted);
                if (done != null) {
                    files.add(done);
                }
            } else {
                discard(seg);
            }
        }
        boolean hadRun = leaving != null || cur != null;
        leaving = null;
        cur = null;
        currentPath = null;
        reportDeleted(deleted);
        if (hadRun) {
            AppLog.d(TAG, "Camera " + cameraId + " run stopped after " + segmentIndex + " segment switch(es), sealed "
                    + files);
            RecordCallback cb = callback;
            if (cb != null) {
                cb.onRecordStop(cameraId);
            }
        }
        return new Stopped(true, files, null);
    }

    /** 开一段：开录，从这一刻起看文件。开不起来的这一段当场放掉。 */
    private boolean startSegment(Segment seg) {
        try {
            seg.recorder.start();
        } catch (RuntimeException e) {
            AppLog.e(TAG, "Camera " + cameraId + " MediaRecorder start failed: " + seg.path, e);
            if (cur == seg) {
                cur = null;
            }
            discard(seg);
            RecordCallback cb = callback;
            if (cb != null) {
                cb.onRecordError(cameraId, "MediaRecorder start failed: " + e.getMessage());
            }
            return false;
        }
        long now = SystemClock.uptimeMillis();
        seg.started = true;
        seg.startedAt = now;
        seg.grewAt = now;
        seg.size = sizeOf(seg.path);
        if (lastGrewAt == 0) {
            lastGrewAt = now;
        }
        currentPath = seg.path;
        AppLog.d(TAG, "Camera " + cameraId + " started segment " + segmentIndex + ": " + seg.path);
        if (segmentIndex == 0) {
            RecordCallback cb = callback;
            if (cb != null) {
                cb.onRecordStart(cameraId);
            }
        }
        scheduleCheck(FIRST_CHECK_DELAY_MS);
        return true;
    }

    /**
     * 换段（分段时间到了、或者跟着别的路对齐名字）：备好下一段，告诉宿主。下一段备不起来的，这一段接着录，
     * 下一个分段时长再试。叫停了、还没开录、上一次换段还没走完的什么都不做。
     */
    private void beginSwitch(String why) {
        Segment from = cur;
        if (stopped || from == null || !from.started || leaving != null) {
            return;
        }
        String path = sharedPath();
        Segment next;
        try {
            next = openSegment(path, true);
        } catch (IOException | RuntimeException e) {
            AppLog.e(TAG, "Camera " + cameraId + " next segment could not be prepared (" + why
                    + "), keeps recording " + from.path, e);
            RecordCallback cb = callback;
            if (cb != null) {
                cb.onRecordError(cameraId, "next segment could not be prepared: " + e.getMessage());
            }
            scheduleSegment();
            return;
        }
        leaving = from;
        cur = next;
        segmentIndex++;
        AppLog.d(TAG, "Camera " + cameraId + " segment " + segmentIndex + " prepared (" + why + "): " + path
                + ", waiting for the channel to swap the record output");
        SegmentOutputs host = outputs;
        if (host != null) {
            host.onNextSegment(next.surface, from.surface);
        }
    }

    /** 看这一段的文件长没长：写进第一笔、写不进都从这里报。 */
    private void checkFile() {
        if (stopped || stallReported) {
            return;
        }
        Segment seg = watched();
        if (seg == null) {
            return;   // 换段中：旧的已经停了、新的还没开 —— 新的开了再看
        }
        long now = SystemClock.uptimeMillis();
        long size = sizeOf(seg.path);
        if (size > seg.size) {
            seg.size = size;
            seg.grewAt = now;
            lastGrewAt = now;
        }
        if (!seg.wrote && size >= FIRST_DATA_BYTES) {
            seg.wrote = true;
            if (seg == cur) {
                // 分段计时从这一段写进第一笔起算：相机起来得慢时，分段时长是有效的录像时长
                scheduleSegment();
            }
            if (!everWrote) {
                everWrote = true;
                AppLog.d(TAG, "Camera " + cameraId + " first data written: " + size + " bytes");
                RecordCallback cb = callback;
                if (cb != null) {
                    cb.onFirstDataWritten(cameraId);
                }
            }
        }
        boolean stalled = seg.wrote ? now - seg.grewAt >= NO_WRITE_MS : now - seg.startedAt >= FIRST_WRITE_TIMEOUT_MS;
        if (stalled) {
            AppLog.e(TAG, "Camera " + cameraId + " file not growing: " + seg.path + " (" + size + " bytes, wrote="
                    + seg.wrote + ")");
            reportStall(now);
            return;
        }
        handler.postDelayed(check, seg.wrote ? CHECK_INTERVAL_MS : FIRST_CHECK_DELAY_MS);
    }

    /** 看哪一段的文件：这一段开了看它；换段中新的还没开，看还在录的旧的。 */
    private Segment watched() {
        if (cur != null && cur.started) {
            return cur;
        }
        return leaving != null && leaving.started ? leaving : null;
    }

    /** 报写不进（一趟只报一次）：不再看文件，等宿主判、停。 */
    private void reportStall(long now) {
        if (stallReported || stopped) {
            return;
        }
        stallReported = true;
        handler.removeCallbacks(check);
        long stalledMs = lastGrewAt == 0 ? 0 : now - lastGrewAt;
        RecordCallback cb = callback;
        if (cb != null) {
            cb.onWriteStalled(cameraId, stalledMs, everWrote);
        }
    }

    private void scheduleCheck(long delayMs) {
        handler.removeCallbacks(check);
        handler.postDelayed(check, delayMs);
    }

    private void scheduleSegment() {
        handler.removeCallbacks(segmentTick);
        handler.postDelayed(segmentTick, segmentMs);
    }

    /** 配好、prepare 一个 MediaRecorder，写到 {@code path}。prepare 时文件就建出来了；配不起来的放掉、删掉文件。 */
    private Segment openSegment(String path, boolean shared) throws IOException {
        MediaRecorder recorder = new MediaRecorder();
        try {
            recorder.setOnInfoListener((mr, what, extra) ->
                    AppLog.d(TAG, "Camera " + cameraId + " MediaRecorder info what=" + what + ", extra=" + extra));
            recorder.setOnErrorListener((mr, what, extra) ->
                    AppLog.e(TAG, "Camera " + cameraId + " MediaRecorder error what=" + what + ", extra=" + extra));
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setOutputFile(path);
            recorder.setVideoEncodingBitRate(bitrate);
            recorder.setVideoFrameRate(frameRate);
            recorder.setVideoSize(encodeWidth, encodeHeight);
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            recorder.prepare();
            Surface surface = recorder.getSurface();
            if (surface == null || !surface.isValid()) {
                throw new IOException("MediaRecorder has no input surface after prepare");
            }
            handedOut.add(surface);
            return new Segment(recorder, surface, path, shared);
        } catch (IOException | RuntimeException e) {
            releaseQuietly(recorder);
            deleteQuietly(path);
            throw e;
        }
    }

    /**
     * 收一段：停下（没收到过画面的 MediaRecorder 停不下来，那个文件是空壳），放掉 MediaRecorder 和 Surface，
     * 没有画面的文件删掉。
     *
     * @return 收好的文件；删了、没有是 null
     */
    private String finish(Segment seg, List<String> deleted) {
        boolean sealed = true;
        try {
            seg.recorder.stop();
        } catch (RuntimeException e) {
            sealed = false;
            AppLog.w(TAG, "Camera " + cameraId + " MediaRecorder stop failed (no frames?): " + e.getMessage());
        }
        releaseSegment(seg);
        File file = new File(seg.path);
        if (!file.exists()) {
            return null;
        }
        long size = file.length();
        if (!sealed || size < MIN_VALID_FILE_SIZE) {
            if (file.delete()) {
                deleted.add(file.getName());
            }
            AppLog.w(TAG, "Camera " + cameraId + " deleted unusable file " + seg.path + " (" + size + " bytes, sealed="
                    + sealed + ")");
            return null;
        }
        return seg.path;
    }

    /** 放掉一段还没开录的：MediaRecorder、Surface 放掉，文件（prepare 时建的空壳）删掉。 */
    private void discard(Segment seg) {
        releaseSegment(seg);
        deleteQuietly(seg.path);
    }

    /**
     * 放掉一段的 MediaRecorder 和我们手上的 Surface（叫停时已经放过的再放一次无害）：Surface 一放就失效了，还在会话里的话
     * 相机层认得出它是死输出，调度马上摘 —— 停下的 MediaRecorder 不再取帧，留在会话里会让那一路整条重复请求停下。
     */
    private void releaseSegment(Segment seg) {
        releaseQuietly(seg.recorder);
        releaseQuietly(seg.surface);
        handedOut.remove(seg.surface);
    }

    private static void releaseQuietly(Surface surface) {
        try {
            surface.release();
        } catch (RuntimeException ignored) {
            // 已经放过了
        }
    }

    private void releaseQuietly(MediaRecorder recorder) {
        try {
            recorder.reset();
        } catch (RuntimeException e) {
            AppLog.w(TAG, "Camera " + cameraId + " MediaRecorder.reset() failed: " + e.getMessage());
        }
        try {
            recorder.release();
        } catch (RuntimeException e) {
            AppLog.w(TAG, "Camera " + cameraId + " MediaRecorder.release() failed: " + e.getMessage());
        }
    }

    private void reportDeleted(List<String> deleted) {
        RecordCallback cb = callback;
        if (!deleted.isEmpty() && cb != null) {
            cb.onCorruptedFilesDeleted(cameraId, deleted);
        }
    }

    /** 下一段的文件：名字按共用的分段名字（几路同一个）；那里已经有同名的了就按此刻另起一个，不能覆盖已有的录像。 */
    private String sharedPath() {
        SegmentTimestampProvider provider = timestampProvider;
        if (provider == null) {
            return pathNamedAt(System.currentTimeMillis());
        }
        File file = new File(dir, provider.getSegmentTimestamp() + "_" + suffix + ".mp4");
        return file.exists() ? pathNamedAt(System.currentTimeMillis()) : file.getAbsolutePath();
    }

    /** 以这一刻命名的文件路径；撞名就往后挪一秒 —— 不能覆盖已有的文件。 */
    private String pathNamedAt(long wallMs) {
        SimpleDateFormat format = new SimpleDateFormat(SharedSegmentName.PATTERN, Locale.getDefault());
        String tail = "_" + suffix + ".mp4";
        for (int i = 0; ; i++) {
            File file = new File(dir, format.format(new Date(wallMs + i * 1000L)) + tail);
            if (!file.exists()) {
                return file.getAbsolutePath();
            }
        }
    }

    /** 编码尺寸：超过编码器上限的等比缩，宽高取偶数。 */
    private int[] encodeSize(int width, int height) {
        int outWidth = width;
        int outHeight = height;
        if (width > MAX_ENCODE_WIDTH || height > MAX_ENCODE_HEIGHT) {
            float scale = Math.min((float) MAX_ENCODE_WIDTH / width, (float) MAX_ENCODE_HEIGHT / height);
            outWidth = (int) (width * scale);
            outHeight = (int) (height * scale);
            AppLog.w(TAG, "Camera " + cameraId + " resolution exceeds encoder limit: " + width + "x" + height
                    + " -> " + outWidth + "x" + outHeight);
        }
        outWidth = Math.max(2, outWidth / 2 * 2);
        outHeight = Math.max(2, outHeight / 2 * 2);
        return new int[]{outWidth, outHeight};
    }

    private static long sizeOf(String path) {
        File file = new File(path);
        return file.exists() ? file.length() : 0;
    }

    private static void deleteQuietly(String path) {
        File file = new File(path);
        if (file.exists() && !file.delete()) {
            AppLog.w(TAG, "Could not delete " + path);
        }
    }

    // ===================================================================== 排到录制器线程上

    private boolean post(Runnable task) {
        return handler.post(task);
    }

    private interface Task<T> {
        T run();
    }

    private interface Undo<T> {
        void undo(T value);
    }

    /**
     * 在录制器线程上做一步，调用的线程等它做完，最多 {@link #CALL_WAIT_MS}。到点没做完：返回 {@code fallback}，
     * 那一步做完时看到等的一方已经不要了，自己收回（{@code undo}）—— 谁先「认领」结果谁算数，只会有一方。
     */
    private <T> T call(String what, Task<T> task, Undo<T> undo, T fallback) {
        final AtomicBoolean claimed = new AtomicBoolean();
        final AtomicReference<T> value = new AtomicReference<>();
        final CountDownLatch done = new CountDownLatch(1);
        boolean posted = post(() -> {
            T result;
            try {
                result = task.run();
            } catch (RuntimeException e) {
                AppLog.e(TAG, "Camera " + cameraId + " " + what + " failed", e);
                result = fallback;
            }
            value.set(result);
            if (claimed.compareAndSet(false, true)) {
                done.countDown();
            } else if (undo != null && result != null) {
                AppLog.w(TAG, "Camera " + cameraId + " " + what + " finished after the caller gave up, undone");
                undo.undo(result);
            }
        });
        if (!posted) {
            return fallback;
        }
        try {
            if (done.await(CALL_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return value.get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (claimed.compareAndSet(false, true)) {
            AppLog.w(TAG, "Camera " + cameraId + " " + what + " did not finish in " + CALL_WAIT_MS + " ms");
            return fallback;
        }
        // 它刚好在这一下做完、认领了结果：结果在认领之前就放好了
        return value.get();
    }
}
