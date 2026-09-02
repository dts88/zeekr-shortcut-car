package com.kooo.evcam.camera;

import java.util.Locale;

/**
 * 数帧率。
 *
 * <h3>为什么要能脱离录制单独测</h3>
 *
 * <p>「录出来只有 15fps」有两种原因：相机就只给了 15 帧，或者相机给了 25 帧
 * 而我们只渲染得出 15 帧。之前只在编码器里统计，那就<b>必须先开始录制</b>才有数，
 * 而录制本身又是开销最大的一件事 —— 用一个带开销的过程去测另一个的上限，
 * 测出来的永远是两者的合力。</p>
 *
 * <p>预览的 {@code onSurfaceTextureUpdated} 每来一帧就回调一次，
 * 那就是<b>不录制时相机的出帧率</b>，也就是这条视频流本身的上限。</p>
 *
 * <p>纯计数，不碰 Android，可以单独测。</p>
 */
public final class FrameRateMeter {

    /** 统计窗口。太短会被抖动淹没，太长又要等很久才出第一个数。 */
    public static final long DEFAULT_WINDOW_MS = 2000L;

    private final long windowMs;
    private long windowStartMs;
    private int framesInWindow;
    private float lastFps;
    private long totalFrames;

    public FrameRateMeter() {
        this(DEFAULT_WINDOW_MS);
    }

    public FrameRateMeter(long windowMs) {
        this.windowMs = windowMs > 0 ? windowMs : DEFAULT_WINDOW_MS;
    }

    /**
     * 记一帧。
     *
     * @param nowMs 当前时刻（毫秒），由调用方给 —— 这样测试里能喂固定的时间
     * @return 本次是否结算出了一个新的帧率
     */
    public synchronized boolean onFrame(long nowMs) {
        totalFrames++;
        framesInWindow++;
        if (windowStartMs == 0) {
            windowStartMs = nowMs;
            return false;
        }
        long elapsed = nowMs - windowStartMs;
        if (elapsed < windowMs) {
            return false;
        }
        lastFps = framesInWindow * 1000f / elapsed;
        framesInWindow = 0;
        windowStartMs = nowMs;
        return true;
    }

    /** 最近一个窗口算出的帧率；还没结算过时返回 0。 */
    public synchronized float fps() {
        return lastFps;
    }

    /** 从开始到现在一共来了多少帧。 */
    public synchronized long totalFrames() {
        return totalFrames;
    }

    /** 有没有测出过数。没有的话，说明这一路根本没在出帧。 */
    public synchronized boolean hasReading() {
        return lastFps > 0f;
    }

    public synchronized void reset() {
        windowStartMs = 0;
        framesInWindow = 0;
        lastFps = 0f;
        totalFrames = 0;
    }

    @Override
    public synchronized String toString() {
        return hasReading()
                ? String.format(Locale.US, "%.1f fps", lastFps)
                : "尚未测出";
    }
}
