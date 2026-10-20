package com.kooo.evcam.zeekr;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.TextureView;
import android.view.View;

import com.kooo.evcam.AppLog;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 主界面预览的画面停了：和超级镜子一样压暗、写一句（{@link StalledPicture}，项目所有者 2026-10-20）。
 *
 * <p>每一格的容器上挂一层（{@code ViewOverlay}：画在画面和容器里别的东西上面，不改布局）。环视四格是同一条流，
 * 有就都有、没有就都没有，所以整块四宫格一起压暗、只写一句；座舱两格各是一台摄像头，各压各的、各写各的。</p>
 *
 * <p>新画面只认画布上那一帧的时间戳变了。TextureView 改尺寸、重新可见时会把手里那一帧重贴一次、照样报一次更新，
 * 那不是新画面；时间戳是贴完才变的（贴在渲染线程上做），所以等这一次画完再读。</p>
 *
 * <p>只盖「显示过画面、之后停了」的：画布上从没贴过画面（时间戳是 0）时是黑的，没有过时的画面可以误导人
 * （相机还在开，按次序开座舱要几秒）。接回上一个界面留下的画布时，画布上还留着最后那一帧，就算显示过了。</p>
 *
 * <p>看不见的时候（主界面在后台、那一格藏着）不算它停 —— 系统不往看不见的画布上贴画面；重新看得见时从那一刻起再等
 * {@link StalledPicture#AFTER_MS}。已经盖上的，等到新画面才揭开。主线程。</p>
 */
public final class PreviewStall {

    private static final String TAG = "PreviewStall";

    /** 多久看一次（画面停了就没有帧来驱动重画了）：同超级镜子。 */
    private static final long TICK_MS = 1000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    /** 每一路一层，按 key（{@code com.kooo.evcam.camera.CameraSlots}）。 */
    private final Map<String, Cover> covers = new LinkedHashMap<>();
    private boolean running;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            for (Cover cover : covers.values()) {
                if (!cover.textureView.isShown()) {
                    cover.lastFrameAt = now;
                    continue;
                }
                if (cover.lastTimestamp != 0 && now - cover.lastFrameAt > StalledPicture.AFTER_MS
                        && cover.setStalled(true)) {
                    AppLog.i(TAG, "主界面 " + cover.key + " 画面停了，压暗");
                }
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    /**
     * 给这一路挂上一层，挂在画布所在的那一格上。同一路再挂一次换成新的（主界面重建了布局）。
     *
     * @param textureView 这一路的画布；它的父容器就是那一格（环视是四宫格那个容器，座舱是那一格）
     * @param hint        压暗时写的那一句
     * @param maxTextPx   字最大多大
     */
    public void attach(String key, TextureView textureView, String hint, float maxTextPx) {
        if (key == null || textureView == null || !(textureView.getParent() instanceof View)) {
            return;
        }
        detach(key);
        Cover cover = new Cover(key, textureView, hint, maxTextPx);
        covers.put(key, cover);
        cover.bind();
    }

    /** 把挂上的都摘掉（主界面销毁）。 */
    public void detachAll() {
        stop();
        for (String key : covers.keySet().toArray(new String[0])) {
            detach(key);
        }
    }

    private void detach(String key) {
        Cover cover = covers.remove(key);
        if (cover != null) {
            handler.removeCallbacks(cover.check);
            cover.unbind();
        }
    }

    /** 这一路的画布报了一次更新：这一次画完再看是不是新画面（见类说明）。 */
    public void noteFrame(String key) {
        Cover cover = covers.get(key);
        if (cover == null || cover.checkPending) {
            return;
        }
        cover.checkPending = true;
        handler.post(cover.check);
    }

    /** 这一路换了一块新画布（系统新建的，上面什么都没有）：还没显示过画面。 */
    public void canvasReplaced(String key) {
        Cover cover = covers.get(key);
        if (cover == null) {
            return;
        }
        cover.lastTimestamp = 0;
        cover.setStalled(false);
    }

    /** 主界面回到前台：从此刻起再等 {@link StalledPicture#AFTER_MS}，开始看。 */
    public void start() {
        long now = SystemClock.uptimeMillis();
        for (Cover cover : covers.values()) {
            cover.lastFrameAt = now;
        }
        if (!running) {
            running = true;
            handler.postDelayed(tick, TICK_MS);
        }
    }

    /** 主界面退到后台：不看了。 */
    public void stop() {
        running = false;
        handler.removeCallbacks(tick);
    }

    /**
     * 一格上的那一层：画面停了才画（{@link StalledPicture#draw}），大小跟着那一格。
     * 只盖那一格看得见的部分：底框（照它背景的圆角）加上画面那一块，不盖到底框外面去。
     */
    private static final class Cover extends Drawable {
        final String key;
        final TextureView textureView;
        private final View pane;
        private final String hint;
        private final float maxTextPx;
        private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path shape = new Path();
        private final Outline outline = new Outline();
        private final Rect frame = new Rect();
        private final View.OnLayoutChangeListener sizer =
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        setBounds(0, 0, right - left, bottom - top);
        final Runnable check = this::checkFrame;
        boolean checkPending;
        /** 上一次认作新画面的那一帧的时间戳；0 = 这块画布还没显示过画面。 */
        long lastTimestamp;
        long lastFrameAt;
        private boolean stalled;

        Cover(String key, TextureView textureView, String hint, float maxTextPx) {
            this.key = key;
            this.textureView = textureView;
            this.pane = (View) textureView.getParent();
            this.hint = hint;
            this.maxTextPx = maxTextPx;
        }

        void bind() {
            setBounds(0, 0, pane.getWidth(), pane.getHeight());
            pane.addOnLayoutChangeListener(sizer);
            pane.getOverlay().add(this);
        }

        void unbind() {
            pane.removeOnLayoutChangeListener(sizer);
            pane.getOverlay().remove(this);
        }

        private void checkFrame() {
            checkPending = false;
            SurfaceTexture surface = textureView.getSurfaceTexture();
            if (surface == null || surface.isReleased()) {
                return;
            }
            long timestamp = surface.getTimestamp();
            if (timestamp == 0 || timestamp == lastTimestamp) {
                return;
            }
            lastTimestamp = timestamp;
            lastFrameAt = SystemClock.uptimeMillis();
            if (setStalled(false)) {
                AppLog.i(TAG, "主界面 " + key + " 画面回来了");
            }
        }

        /** @return true：变了 */
        boolean setStalled(boolean value) {
            if (stalled == value) {
                return false;
            }
            stalled = value;
            invalidateSelf();
            return true;
        }

        @Override
        public void draw(Canvas canvas) {
            if (!stalled) {
                return;
            }
            Rect b = getBounds();
            float radius = 0f;
            Drawable background = pane.getBackground();
            if (background != null) {
                outline.setEmpty();
                background.getOutline(outline);
                if (outline.getRect(frame)) {
                    radius = outline.getRadius();
                }
            }
            shape.rewind();
            shape.addRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, Path.Direction.CW);
            shape.addRect(textureView.getLeft(), textureView.getTop(),
                    textureView.getRight(), textureView.getBottom(), Path.Direction.CW);
            canvas.save();
            canvas.clipPath(shape);
            StalledPicture.draw(canvas, scrim, text, b.left, b.top, b.width(), b.height(), hint, maxTextPx);
            canvas.restore();
        }

        @Override
        public void setAlpha(int alpha) {
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
