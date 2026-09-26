package com.kooo.evcam.playback;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.zeekr.FisheyeMesh;

/**
 * 视频回看里环视那一格的外框：取景，以及屏幕上的鱼眼校正。
 *
 * <h3>为什么要套一层</h3>
 *
 * <p>不校正时，取景就是 TextureView 自己的变换矩阵（{@link PlaybackViewport#transformRects}）。
 * 校正是非线性的，一个矩阵做不到，只能由父视图把这个 TextureView 切成小格逐格画
 * （{@link FisheyeMesh}，和主界面预览、超级后视镜同一套）。所以校正时把 TextureView 的矩阵
 * 还原成单位阵，让视频铺满它自己，取景和校正都由这里画。</p>
 *
 * <p>开关和图片回看、主界面是同一个（{@link AppConfig#isFisheyeCorrection}），参数也是同一套。
 * 外框自己听开关，一变就换画法；所在的界面只管告诉它现在看哪一格（{@link #show}）。</p>
 *
 * <p>只校正按 2×2 存、每格是正方形的录像 —— 那才是四路鱼眼。座舱录像是一整幅普通画面，
 * 这里对它只做取景，和以前一样。录像文件本身一个字节都不动。</p>
 */
public class FisheyeVideoFrame extends FrameLayout {

    private static final String TAG = "FisheyeVideoFrame";

    private TextureView video;
    private final FisheyeMesh mesh = new FisheyeMesh();
    private final FisheyeMesh.Painter paintVideo =
            canvas -> drawChild(canvas, video, getDrawingTime());
    private final Matrix matrix = new Matrix();
    private final RectF sourceRect = new RectF();
    private final RectF destinationRect = new RectF();
    /** 拿住它：SharedPreferences 只弱引用监听器。 */
    private SharedPreferences.OnSharedPreferenceChangeListener listener;

    /** 开关开没开。 */
    private boolean switchedOn;
    /** 这一路是不是按 2×2 存的（环视合成流）。 */
    private boolean grid;
    private int cell = PlaybackViewport.NO_CELL;
    private int videoWidth;
    private int videoHeight;
    /** 此刻是不是在校正着画：开关开着，而且这段录像真是四路鱼眼。 */
    private boolean correcting;

    public FisheyeVideoFrame(Context context) {
        this(context, null);
    }

    public FisheyeVideoFrame(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child instanceof TextureView) {
                video = (TextureView) child;
                break;
            }
        }
        if (video == null) {
            AppLog.e(TAG, "外框里没有 TextureView，校正和取景都做不了");
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        readConfig();
        listener = new AppConfig(getContext()).onFisheyeChanged(this::readConfig);
    }

    @Override
    protected void onDetachedFromWindow() {
        new AppConfig(getContext()).removeFisheyeListener(listener);
        listener = null;
        super.onDetachedFromWindow();
    }

    private void readConfig() {
        AppConfig config = new AppConfig(getContext());
        switchedOn = config.isFisheyeCorrection();
        mesh.setCorrection(config.getFisheyeFov(), config.getFisheyeProjection(),
                config.getFisheyeStrength() / 100f);
        apply();
    }

    /**
     * 现在看什么：整幅（{@link PlaybackViewport#NO_CELL}）还是放大其中一格。
     *
     * <p>视频尺寸或视图尺寸还不知道时先记下，等下一次（准备好、布局变了都会再叫）。</p>
     *
     * @param grid 这一路是不是按 2×2 存的
     */
    public void show(int cell, int videoWidth, int videoHeight, boolean grid) {
        this.cell = cell;
        this.videoWidth = videoWidth;
        this.videoHeight = videoHeight;
        this.grid = grid;
        apply();
    }

    private void apply() {
        if (video == null) {
            return;
        }
        float[] r = PlaybackViewport.transformRects(cell, videoWidth, videoHeight,
                video.getWidth(), video.getHeight());
        if (r == null) {
            return;
        }
        boolean now = switchedOn && grid
                && PlaybackViewport.hasSquareCells(videoWidth, videoHeight);
        if (now != correcting) {
            AppLog.i(TAG, now
                    ? "鱼眼校正开：" + mesh.projection() + " " + mesh.fovDegrees() + "° 强度 "
                            + Math.round(mesh.strength() * 100f) + "%"
                    : "鱼眼校正关" + (switchedOn ? "（这段录像不是四路鱼眼："
                            + videoWidth + "x" + videoHeight + " grid=" + grid + "）" : ""));
        }
        correcting = now;
        if (correcting) {
            // 视频铺满子视图，取景和校正都在 dispatchDraw 里做
            video.setTransform(null);
        } else {
            sourceRect.set(r[0], r[1], r[2], r[3]);
            destinationRect.set(r[4], r[5], r[6], r[7]);
            matrix.setRectToRect(sourceRect, destinationRect, Matrix.ScaleToFit.FILL);
            video.setTransform(matrix);
        }
        video.invalidate();
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (!correcting || video == null) {
            super.dispatchDraw(canvas);
            return;
        }
        int viewWidth = video.getWidth();
        int viewHeight = video.getHeight();
        float[] r = PlaybackViewport.transformRects(cell, videoWidth, videoHeight,
                viewWidth, viewHeight);
        if (r == null) {
            super.dispatchDraw(canvas);
            return;
        }
        // 视频铺满了 TextureView，所以视图坐标就是画面坐标；再加上它在外框里的位置
        float offsetX = video.getLeft();
        float offsetY = video.getTop();
        if (cell != PlaybackViewport.NO_CELL) {
            drawLane(canvas, offsetX + r[0], offsetY + r[1], r[2] - r[0], r[3] - r[1],
                    offsetX + r[4], offsetY + r[5], r[6] - r[4], r[7] - r[5]);
            return;
        }
        // 整幅：四格各自校正，每一格有自己的光心
        float sourceWidth = (r[2] - r[0]) / 2f;
        float sourceHeight = (r[3] - r[1]) / 2f;
        float targetWidth = (r[6] - r[4]) / 2f;
        float targetHeight = (r[7] - r[5]) / 2f;
        for (int index = 0; index < PlaybackViewport.CELL_COUNT; index++) {
            int column = index % 2;
            int row = index / 2;
            drawLane(canvas,
                    offsetX + r[0] + column * sourceWidth, offsetY + r[1] + row * sourceHeight,
                    sourceWidth, sourceHeight,
                    offsetX + r[4] + column * targetWidth, offsetY + r[5] + row * targetHeight,
                    targetWidth, targetHeight);
        }
    }

    private void drawLane(Canvas canvas,
                          float laneLeft, float laneTop, float laneWidth, float laneHeight,
                          float targetLeft, float targetTop, float targetWidth, float targetHeight) {
        if (laneWidth <= 0f || laneHeight <= 0f || targetWidth <= 0f || targetHeight <= 0f) {
            return;
        }
        mesh.prepare(FisheyeMesh.divisionsFor(Math.max(targetWidth, targetHeight)),
                laneLeft, laneTop, laneWidth, laneHeight, 0f, 0f, 1f, 1f);
        mesh.draw(canvas, targetLeft, targetTop, targetWidth, targetHeight, paintVideo);
    }
}
