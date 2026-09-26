package com.kooo.evcam.zeekr;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;

import java.lang.ref.WeakReference;

/**
 * 主界面环视预览的 GPU 逐像素鱼眼校正（开发者选项）：相机给预览的那个 Surface 从哪来。
 *
 * <p>平时相机直接写进屏幕上的 TextureView。开发者选项开着时，相机改写进一条
 * {@link FisheyeGlPipe}，由它逐像素校正后再画到那个 TextureView 上。
 * {@code SingleCamera} 建预览 Surface 的地方只改了一行：交给 {@link #surfaceFor}。</p>
 *
 * <p>只接管合成流那一路（环视）；座舱、以及开关没开、几何认不出来、管线起不来的时候，
 * 都照旧 {@code new Surface(surfaceTexture)} —— 和这个类存在之前一模一样。</p>
 *
 * <p>校正本身开不开、用什么投影，跟着屏幕上那个鱼眼开关和设置走（和分格近似同一套）；
 * 这里只决定「用哪种算法」。管线接上之后，{@link FourLaneContainer} 就不再分格。</p>
 *
 * <p>开发者选项改了要重启应用才生效：已经建好的相机会话不会换 Surface。</p>
 */
public final class PreviewDewarp {

    private static final String TAG = "PreviewDewarp";

    /** 只用来让「建管线」一次一个。读状态不拿它：主界面每画一帧都要问 {@link #isActive}。 */
    private static final Object LOCK = new Object();
    private static volatile FisheyeGlPipe pipe;
    private static volatile WeakReference<TextureView> pipeView = new WeakReference<>(null);
    private static volatile AppConfig config;
    /** 拿住它：SharedPreferences 只弱引用监听器。 */
    private static SharedPreferences.OnSharedPreferenceChangeListener listener;

    private PreviewDewarp() {
    }

    /**
     * 相机给预览用的 Surface。
     *
     * @param texture    屏幕上那个 TextureView 的 SurfaceTexture，缓冲区尺寸已经设好
     * @param bufferSize 预览出流的尺寸；不知道时传 null（照旧直连）
     */
    public static Surface surfaceFor(String cameraId, TextureView view, SurfaceTexture texture,
                                     Size bufferSize) {
        Surface routed = routed(cameraId, view, texture, bufferSize);
        return routed != null ? routed : new Surface(texture);
    }

    /** 这个 TextureView 上的画面此刻是不是已经由 GPU 校正过（或至少经过了管线）。 */
    public static boolean isActive(TextureView view) {
        FisheyeGlPipe current = pipe;
        return view != null && current != null && current.isAlive() && pipeView.get() == view;
    }

    private static Surface routed(String cameraId, TextureView view, SurfaceTexture texture,
                                  Size bufferSize) {
        if (view == null || texture == null || cameraId == null
                || !cameraId.equals(StreamLayoutTable.compositeCameraId())) {
            return null;
        }
        Context context = view.getContext().getApplicationContext();
        AppConfig appConfig = new AppConfig(context);
        if (!appConfig.isGpuFisheyePreview()) {
            return null;
        }
        if (bufferSize == null) {
            AppLog.w(TAG, "预览尺寸还不知道，这一次照旧直连");
            return null;
        }
        CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(
                cameraId, bufferSize.getWidth(), bufferSize.getHeight());
        if (plan == null || !plan.isComposite()) {
            AppLog.w(TAG, "预览缓冲 " + bufferSize + " 认不出是合成流，照旧直连");
            return null;
        }

        synchronized (LOCK) {
            // 同一块输出上已经有一条活着的管线：相机那边只是重建了 Surface，给它一个新的
            // 入口就行（直连时也是在同一个 SurfaceTexture 上再 new 一个 Surface）
            if (pipe != null && pipe.isAlive() && pipe.output() == texture) {
                Surface again = pipe.newInputSurface();
                if (again != null) {
                    AppLog.i(TAG, "相机 " + cameraId + " 重建预览 Surface，接回同一条管线");
                    return again;
                }
            }
            // 换了输出（TextureView 重建过）或者旧的坏了：先收掉旧的，同一块输出不能接两个生产者
            releaseLocked();

            float[] lanes = new float[plan.laneCount() * 4];
            for (int i = 0; i < plan.laneCount(); i++) {
                CompositeStreamGeometry.Lane lane = plan.lane(i);
                lanes[i * 4] = lane.u0;
                lanes[i * 4 + 1] = lane.v0;
                lanes[i * 4 + 2] = lane.u1 - lane.u0;
                lanes[i * 4 + 3] = lane.v1 - lane.v0;
            }
            FisheyeGlPipe started = FisheyeGlPipe.start("preview-" + cameraId, texture,
                    bufferSize.getWidth(), bufferSize.getHeight(), lanes);
            if (started == null) {
                return null;
            }
            Surface surface = started.newInputSurface();
            if (surface == null) {
                started.release();
                return null;
            }
            pipe = started;
            pipeView = new WeakReference<>(view);
            config = appConfig;
            push();
            if (listener == null) {
                listener = appConfig.onFisheyeChanged(PreviewDewarp::push);
            }
            WeakReference<TextureView> viewRef = new WeakReference<>(view);
            // 这个回调在管线自己的线程上：不拿锁，挪到主线程去做
            started.setOnReleased(() -> {
                TextureView gone = viewRef.get();
                if (gone == null) {
                    return;
                }
                gone.post(() -> {
                    if (pipe == started) {
                        pipe = null;
                    }
                    redraw(gone);
                });
            });
            AppLog.i(TAG, "相机 " + cameraId + " 的预览改走 GPU 逐像素校正，" + plan.laneCount()
                    + " 路，缓冲 " + bufferSize);
            redraw(view);
            return surface;
        }
    }

    /** 把屏幕上那个鱼眼开关和设置推给管线。 */
    private static void push() {
        FisheyeGlPipe current = pipe;
        AppConfig appConfig = config;
        if (current == null || appConfig == null) {
            return;
        }
        current.setCorrection(appConfig.isFisheyeCorrection(), appConfig.getFisheyeFov(),
                appConfig.getFisheyeProjection(), appConfig.getFisheyeStrength() / 100f);
    }

    private static void releaseLocked() {
        if (pipe != null) {
            pipe.release();
            pipe = null;
        }
    }

    /** 管线接上或断开，四宫格那边的画法跟着变（分格 / 不分格），要重画一次。 */
    private static void redraw(TextureView view) {
        view.post(() -> {
            View parent = view.getParent() instanceof View ? (View) view.getParent() : null;
            if (parent != null) {
                parent.invalidate();
            }
        });
    }
}
