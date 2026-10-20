package com.kooo.evcam.zeekr;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
 * <p>只盖「显示过画面、之后停了」的：一块画布从没出过帧时是黑的，没有过时的画面可以误导人（相机还在开，按次序开座舱要几秒）。
 * 接回上一个界面留下的画布时，画布上还留着最后那一帧 —— TextureView 接回画布后第一次画会报一次更新，就算显示过了。
 * 回到前台时从那一刻起再等 {@link StalledPicture#AFTER_MS}；主界面在后台时不看。主线程。</p>
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
            for (Map.Entry<String, Cover> entry : covers.entrySet()) {
                Cover cover = entry.getValue();
                boolean stalled = cover.shown && now - cover.lastFrameAt > StalledPicture.AFTER_MS;
                if (cover.setStalled(stalled)) {
                    AppLog.i(TAG, stalled ? "主界面 " + entry.getKey() + " 画面停了，压暗" : "主界面 " + entry.getKey() + " 画面回来了");
                }
            }
            handler.postDelayed(this, TICK_MS);
        }
    };

    /**
     * 给这一路的那一格挂上一层。同一路再挂一次换成新的那一格（主界面重建了布局）。
     *
     * @param pane      这一路画面所在的容器（环视是四宫格那个容器，座舱是那一格）
     * @param hint      压暗时写的那一句
     * @param maxTextPx 字最大多大
     */
    public void attach(String key, View pane, String hint, float maxTextPx) {
        if (key == null || pane == null) {
            return;
        }
        detach(key);
        Cover cover = new Cover(pane, hint, maxTextPx);
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
            cover.unbind();
        }
    }

    /** 这一路来了一帧（画布每显示一次新画面）。 */
    public void noteFrame(String key) {
        Cover cover = covers.get(key);
        if (cover == null) {
            return;
        }
        cover.shown = true;
        cover.lastFrameAt = SystemClock.uptimeMillis();
        cover.setStalled(false);
    }

    /** 这一路换了一块新画布（系统新建的，上面什么都没有）：还没显示过画面。 */
    public void canvasReplaced(String key) {
        Cover cover = covers.get(key);
        if (cover == null) {
            return;
        }
        cover.shown = false;
        cover.setStalled(false);
    }

    /** 主界面回到前台：从此刻起再等 {@link StalledPicture#AFTER_MS}，开始看。 */
    public void start() {
        long now = SystemClock.uptimeMillis();
        for (Cover cover : covers.values()) {
            cover.lastFrameAt = now;
            cover.setStalled(false);
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

    /** 一格上的那一层：画面停了才画（{@link StalledPicture#draw}），大小跟着那一格。 */
    private static final class Cover extends Drawable {
        private final View pane;
        private final String hint;
        private final float maxTextPx;
        private final Paint scrim = new Paint();
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final View.OnLayoutChangeListener sizer =
                (v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                        setBounds(0, 0, right - left, bottom - top);
        /** 这块画布显示过画面（见类说明）。 */
        boolean shown;
        long lastFrameAt;
        private boolean stalled;

        Cover(View pane, String hint, float maxTextPx) {
            this.pane = pane;
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
            StalledPicture.draw(canvas, scrim, text, b.left, b.top, b.width(), b.height(), hint, maxTextPx);
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
