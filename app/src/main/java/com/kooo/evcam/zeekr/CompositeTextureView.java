package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Size;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.AutoFitTextureView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 把一路四联合成流实时拆成 2x2 画面显示的 TextureView。
 *
 * <p><b>为什么是 TextureView 的子类：</b>EVCam 现有的相机管线（SingleCamera /
 * MultiCameraManager / 悬浮窗 / 录制）都是围绕 TextureView 写的，取流方式统一是
 * {@code textureView.getSurfaceTexture()}。本类保持这一契约，只是把返回的
 * SurfaceTexture 换成自己在 GL 线程上创建的<b>外部纹理（OES）</b>：</p>
 *
 * <pre>
 *   Camera2 / MediaPlayer
 *        |  写入
 *        v
 *   生产者 SurfaceTexture (OES 纹理)   &lt;-- getSurfaceTexture() 返回这个
 *        |  GL 线程按 CompositeStreamGeometry 拆成 4 个采样窗口
 *        v
 *   TextureView 自带的显示 Surface（EGL Window Surface）
 * </pre>
 *
 * <p>这样一来，上游 SingleCamera 一行都不用改，就能把 1280x5140 的长条流
 * 显示成正常比例的四宫格。同一个类也能接 MediaPlayer，用于回放录下来的合成流。</p>
 *
 * <p>四宫格的画面比例由 {@link ScaleMode} 决定，默认 {@link ScaleMode#FIT}：
 * 保持每个画面原本的方形比例，不拉伸。</p>
 */
public class CompositeTextureView extends AutoFitTextureView {

    private static final String TAG = "CompositeTextureView";

    /** 等待 GL 线程创建生产者纹理的超时。超时只影响首帧，不会崩。 */
    private static final long GL_READY_TIMEOUT_MS = 3000;

    private static final int MSG_INIT = 1;
    private static final int MSG_FRAME = 2;
    private static final int MSG_RESIZE = 3;
    private static final int MSG_RELEASE = 4;

    /** 每个画面在自己格子里的缩放方式。 */
    public enum ScaleMode {
        /** 保持原始比例，格子内留黑边。合成流的画面是正方形，这是默认且推荐的方式。 */
        FIT,
        /** 填满格子，超出部分居中裁切。 */
        FILL
    }

    /** 显示模式。 */
    public enum DisplayMode {
        /** 2x2 四宫格。 */
        GRID,
        /** 只显示某一个画面，铺满整个视图。 */
        SINGLE
    }

    private final Object stateLock = new Object();

    private HandlerThread glThread;
    private Handler glHandler;
    private volatile SurfaceTexture producerTexture;
    private volatile boolean released;

    /** 外部（MainActivity 等）设置的监听器，本类负责转发。 */
    private SurfaceTextureListener externalListener;

    /** 源帧尺寸，由 {@link #setSourceSize} 告知；未知时按整帧单画面显示。 */
    private volatile int sourceWidth;
    private volatile int sourceHeight;
    private volatile int cropInsetPx;
    private volatile ScaleMode scaleMode = ScaleMode.FIT;
    private volatile DisplayMode displayMode = DisplayMode.GRID;
    private volatile int focusedLane;
    /** 画面序号 -> 宫格位置的映射，允许用户调整前后左右的排列。 */
    private volatile int[] laneOrder = {0, 1, 2, 3};

    private volatile CompositeStreamGeometry.Plan cachedPlan;

    public CompositeTextureView(Context context) {
        this(context, null);
    }

    public CompositeTextureView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CompositeTextureView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        super.setSurfaceTextureListener(internalListener);
    }

    // ------------------------------------------------------------------
    // 对外配置
    // ------------------------------------------------------------------

    /**
     * 告知合成流的真实尺寸。相机选定分辨率后必须调用，否则只会显示整帧。
     *
     * @param size Camera2 / 播放器实际输出的尺寸，例如 1280x5140
     */
    public void setSourceSize(Size size) {
        if (size == null) {
            return;
        }
        setSourceSize(size.getWidth(), size.getHeight());
    }

    /** @see #setSourceSize(Size) */
    public void setSourceSize(int width, int height) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (width == sourceWidth && height == sourceHeight) {
            return;
        }
        sourceWidth = width;
        sourceHeight = height;
        cachedPlan = null;
        AppLog.i(TAG, "合成流源尺寸: " + width + "x" + height + " -> " + describePlan());
        requestRender();
    }

    /** 每个画面四边内缩的像素数，用于裁掉分隔带残留。 */
    public void setCropInsetPx(int px) {
        int clamped = Math.max(0, Math.min(64, px));
        if (clamped != cropInsetPx) {
            cropInsetPx = clamped;
            cachedPlan = null;
            requestRender();
        }
    }

    public void setScaleMode(ScaleMode mode) {
        if (mode != null && mode != scaleMode) {
            scaleMode = mode;
            requestRender();
        }
    }

    public ScaleMode getScaleMode() {
        return scaleMode;
    }

    /** 切到只看某一个画面；index 超出范围时忽略。 */
    public void focusLane(int index) {
        if (index < 0 || index >= CompositeStreamGeometry.LANE_COUNT) {
            return;
        }
        focusedLane = index;
        displayMode = DisplayMode.SINGLE;
        requestRender();
    }

    /** 回到 2x2 四宫格。 */
    public void showGrid() {
        displayMode = DisplayMode.GRID;
        requestRender();
    }

    public DisplayMode getDisplayMode() {
        return displayMode;
    }

    public int getFocusedLane() {
        return focusedLane;
    }

    /**
     * 调整画面在四宫格中的排列。
     *
     * @param order 长度为 4 的数组，order[格子位置] = 合成流中的画面序号
     */
    public void setLaneOrder(int[] order) {
        if (order == null || order.length != CompositeStreamGeometry.LANE_COUNT) {
            return;
        }
        boolean[] seen = new boolean[CompositeStreamGeometry.LANE_COUNT];
        for (int value : order) {
            if (value < 0 || value >= CompositeStreamGeometry.LANE_COUNT || seen[value]) {
                AppLog.w(TAG, "忽略非法的画面排列");
                return;
            }
            seen[value] = true;
        }
        laneOrder = order.clone();
        requestRender();
    }

    public int[] getLaneOrder() {
        return laneOrder.clone();
    }

    /** 当前拆分结果的可读描述，用于设置页显示诊断信息。 */
    public String describePlan() {
        CompositeStreamGeometry.Plan plan = currentPlan();
        return plan == null ? "尚未获得源尺寸" : plan.toString();
    }

    /** 当前是否真的识别为四联合成流。 */
    public boolean isCompositeActive() {
        CompositeStreamGeometry.Plan plan = currentPlan();
        return plan != null && plan.isComposite();
    }

    private CompositeStreamGeometry.Plan currentPlan() {
        CompositeStreamGeometry.Plan plan = cachedPlan;
        if (plan != null) {
            return plan;
        }
        int w = sourceWidth;
        int h = sourceHeight;
        if (w <= 0 || h <= 0) {
            return null;
        }
        plan = CompositeStreamGeometry.analyse(w, h, cropInsetPx);
        cachedPlan = plan;
        return plan;
    }

    // ------------------------------------------------------------------
    // TextureView 契约的改写
    // ------------------------------------------------------------------

    /**
     * 返回<b>生产者</b>纹理，而不是 TextureView 自己的显示纹理。
     * 相机、播放器都往这里写；显示由 GL 线程负责。
     */
    @Override
    public SurfaceTexture getSurfaceTexture() {
        SurfaceTexture producer = producerTexture;
        return producer != null ? producer : super.getSurfaceTexture();
    }

    /** 生产者纹理就绪才算可用，避免上游拿到 null。 */
    @Override
    public boolean isAvailable() {
        return producerTexture != null && super.isAvailable();
    }

    /**
     * 外部监听器会被链式转发：本类先完成 GL 初始化，再通知外部，
     * 保证外部收到回调时 {@link #getSurfaceTexture()} 已经能返回生产者纹理。
     */
    @Override
    public void setSurfaceTextureListener(SurfaceTextureListener listener) {
        synchronized (stateLock) {
            externalListener = listener;
        }
        // 内部监听器必须一直挂着，不能被外部覆盖
        super.setSurfaceTextureListener(internalListener);
    }

    /**
     * 四宫格由本类自己排版，外部的预览矩阵（为单画面居中/缩放而算）会破坏排版，
     * 因此在合成模式下忽略。非合成模式下保持原行为。
     */
    @Override
    public void setTransform(Matrix transform) {
        if (isCompositeActive()) {
            return;
        }
        super.setTransform(transform);
    }

    /**
     * 同理：合成模式下视图应铺满容器，不能按 1280:5140 这种长条比例去测量。
     */
    @Override
    public void setAspectRatio(int width, int height) {
        if (CompositeStreamGeometry.looksLikeComposite(width, height)) {
            AppLog.d(TAG, "合成模式忽略外部宽高比 " + width + ":" + height);
            return;
        }
        super.setAspectRatio(width, height);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    private final SurfaceTextureListener internalListener = new SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            startGl(surface, width, height);
            SurfaceTextureListener listener = externalListenerSnapshot();
            if (listener != null) {
                // 转发生产者纹理，外部拿到的和 getSurfaceTexture() 一致
                SurfaceTexture producer = producerTexture;
                listener.onSurfaceTextureAvailable(producer != null ? producer : surface, width, height);
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            Handler handler = glHandler;
            if (handler != null) {
                handler.obtainMessage(MSG_RESIZE, width, height).sendToTarget();
            }
            SurfaceTextureListener listener = externalListenerSnapshot();
            if (listener != null) {
                SurfaceTexture producer = producerTexture;
                listener.onSurfaceTextureSizeChanged(producer != null ? producer : surface, width, height);
            }
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            SurfaceTextureListener listener = externalListenerSnapshot();
            if (listener != null) {
                SurfaceTexture producer = producerTexture;
                listener.onSurfaceTextureDestroyed(producer != null ? producer : surface);
            }
            stopGl();
            // 返回 true 表示由我们负责释放显示纹理
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            SurfaceTextureListener listener = externalListenerSnapshot();
            if (listener != null) {
                listener.onSurfaceTextureUpdated(surface);
            }
        }
    };

    private SurfaceTextureListener externalListenerSnapshot() {
        synchronized (stateLock) {
            return externalListener;
        }
    }

    private void startGl(SurfaceTexture displaySurface, int width, int height) {
        synchronized (stateLock) {
            if (glThread != null) {
                return;
            }
            released = false;
            glThread = new HandlerThread("ZeekrCompositeGL");
            glThread.start();
            glHandler = new GlHandler(glThread.getLooper());
        }
        CountDownLatch ready = new CountDownLatch(1);
        glHandler.obtainMessage(MSG_INIT, width, height,
                new InitRequest(displaySurface, ready)).sendToTarget();
        try {
            if (!ready.await(GL_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                AppLog.w(TAG, "等待 GL 初始化超时，首帧可能延迟");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void stopGl() {
        HandlerThread thread;
        Handler handler;
        synchronized (stateLock) {
            thread = glThread;
            handler = glHandler;
            glThread = null;
            glHandler = null;
            released = true;
        }
        if (handler == null || thread == null) {
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        handler.obtainMessage(MSG_RELEASE, done).sendToTarget();
        try {
            done.await(GL_READY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        thread.quitSafely();
        producerTexture = null;
    }

    private void requestRender() {
        Handler handler = glHandler;
        if (handler != null && !released) {
            handler.removeMessages(MSG_FRAME);
            handler.sendEmptyMessage(MSG_FRAME);
        }
    }

    private static final class InitRequest {
        final SurfaceTexture displaySurface;
        final CountDownLatch ready;

        InitRequest(SurfaceTexture displaySurface, CountDownLatch ready) {
            this.displaySurface = displaySurface;
            this.ready = ready;
        }
    }

    // ------------------------------------------------------------------
    // GL 线程
    // ------------------------------------------------------------------

    private final class GlHandler extends Handler {

        private final CompositeRenderer renderer = new CompositeRenderer();

        GlHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_INIT: {
                    InitRequest request = (InitRequest) msg.obj;
                    try {
                        renderer.setUp(request.displaySurface, msg.arg1, msg.arg2);
                        SurfaceTexture producer = renderer.createProducerTexture(this);
                        producerTexture = producer;
                    } catch (RuntimeException e) {
                        AppLog.e(TAG, "GL 初始化失败，退回普通 TextureView 显示", e);
                        renderer.tearDown();
                    } finally {
                        request.ready.countDown();
                    }
                    break;
                }
                case MSG_FRAME:
                    renderer.drawFrame(currentPlan(), displayMode, focusedLane, laneOrder, scaleMode);
                    break;
                case MSG_RESIZE:
                    renderer.resize(msg.arg1, msg.arg2);
                    renderer.drawFrame(currentPlan(), displayMode, focusedLane, laneOrder, scaleMode);
                    break;
                case MSG_RELEASE: {
                    renderer.tearDown();
                    ((CountDownLatch) msg.obj).countDown();
                    break;
                }
                default:
                    break;
            }
        }
    }

    /**
     * EGL + GLES2 渲染器。只在 GL 线程上使用。
     */
    private static final class CompositeRenderer {

        private static final String VERTEX_SHADER =
                "attribute vec4 aPosition;\n"
                        + "attribute vec4 aTexCoord;\n"
                        + "uniform mat4 uTexMatrix;\n"
                        + "varying vec2 vTexCoord;\n"
                        + "void main() {\n"
                        + "    gl_Position = aPosition;\n"
                        + "    vTexCoord = (uTexMatrix * aTexCoord).xy;\n"
                        + "}\n";

        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n"
                        + "precision mediump float;\n"
                        + "varying vec2 vTexCoord;\n"
                        + "uniform samplerExternalOES uTexture;\n"
                        + "void main() {\n"
                        + "    gl_FragColor = texture2D(uTexture, vTexCoord);\n"
                        + "}\n";

        private static final int FLOATS_PER_VERTEX = 4; // x, y, u, v
        private static final int VERTEX_COUNT = 4;      // triangle strip

        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

        private int program;
        private int aPositionLoc;
        private int aTexCoordLoc;
        private int uTexMatrixLoc;
        private int uTextureLoc;
        private int oesTextureId;

        private SurfaceTexture producer;
        private final float[] texMatrix = new float[16];
        private final FloatBuffer vertexBuffer = ByteBuffer
                .allocateDirect(VERTEX_COUNT * FLOATS_PER_VERTEX * Float.SIZE / Byte.SIZE)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        private final float[] vertexScratch = new float[VERTEX_COUNT * FLOATS_PER_VERTEX];

        private int viewportWidth;
        private int viewportHeight;
        private boolean ready;

        void setUp(SurfaceTexture displaySurface, int width, int height) {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
                throw new RuntimeException("eglGetDisplay 失败");
            }
            int[] version = new int[2];
            if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
                throw new RuntimeException("eglInitialize 失败");
            }
            int[] attributes = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_NONE
            };
            EGLConfig[] configs = new EGLConfig[1];
            int[] configCount = new int[1];
            if (!EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, configCount, 0)
                    || configCount[0] <= 0) {
                throw new RuntimeException("eglChooseConfig 失败");
            }
            int[] contextAttributes = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                    contextAttributes, 0);
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                throw new RuntimeException("eglCreateContext 失败");
            }
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], displaySurface,
                    new int[]{EGL14.EGL_NONE}, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                throw new RuntimeException("eglCreateWindowSurface 失败");
            }
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                throw new RuntimeException("eglMakeCurrent 失败");
            }

            program = buildProgram();
            aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition");
            aTexCoordLoc = GLES20.glGetAttribLocation(program, "aTexCoord");
            uTexMatrixLoc = GLES20.glGetUniformLocation(program, "uTexMatrix");
            uTextureLoc = GLES20.glGetUniformLocation(program, "uTexture");

            int[] textures = new int[1];
            GLES20.glGenTextures(1, textures, 0);
            oesTextureId = textures[0];
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                    GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            viewportWidth = width;
            viewportHeight = height;
            ready = true;
            AppLog.i(TAG, "GL 初始化完成 " + width + "x" + height);
        }

        SurfaceTexture createProducerTexture(Handler glHandler) {
            producer = new SurfaceTexture(oesTextureId);
            producer.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                @Override
                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                    glHandler.removeMessages(MSG_FRAME);
                    glHandler.sendEmptyMessage(MSG_FRAME);
                }
            }, glHandler);
            return producer;
        }

        void resize(int width, int height) {
            viewportWidth = width;
            viewportHeight = height;
        }

        void drawFrame(CompositeStreamGeometry.Plan plan, DisplayMode mode, int focusedLane,
                       int[] order, ScaleMode scaleMode) {
            if (!ready || producer == null) {
                return;
            }
            try {
                producer.updateTexImage();
                producer.getTransformMatrix(texMatrix);
            } catch (RuntimeException e) {
                AppLog.w(TAG, "updateTexImage 失败: " + e.getMessage());
                return;
            }

            GLES20.glViewport(0, 0, viewportWidth, viewportHeight);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
            GLES20.glUniform1i(uTextureLoc, 0);
            GLES20.glUniformMatrix4fv(uTexMatrixLoc, 1, false, texMatrix, 0);

            if (plan == null || !plan.isComposite()) {
                // 尺寸未知或不是合成流：整帧铺满，行为等同普通预览
                drawQuad(0f, 0f, 1f, 1f, -1f, -1f, 1f, 1f);
            } else if (mode == DisplayMode.SINGLE) {
                CompositeStreamGeometry.Lane lane =
                        plan.lane(Math.min(focusedLane, plan.laneCount() - 1));
                drawLaneInCell(lane, -1f, -1f, 1f, 1f, scaleMode);
            } else {
                drawGrid(plan, order, scaleMode);
            }

            EGL14.eglSwapBuffers(eglDisplay, eglSurface);
        }

        /** 2x2 排布：格子位置 0=左上 1=右上 2=左下 3=右下。 */
        private void drawGrid(CompositeStreamGeometry.Plan plan, int[] order, ScaleMode scaleMode) {
            for (int cell = 0; cell < CompositeStreamGeometry.LANE_COUNT; cell++) {
                int laneIndex = order[cell];
                if (laneIndex >= plan.laneCount()) {
                    continue;
                }
                int column = cell % 2;
                int row = cell / 2;
                float left = -1f + column;
                float right = left + 1f;
                float top = 1f - row;
                float bottom = top - 1f;
                drawLaneInCell(plan.lane(laneIndex), left, bottom, right, top, scaleMode);
            }
        }

        /**
         * 把一个画面画进一个 NDC 矩形，按 scaleMode 处理比例。
         *
         * <p>FIT：缩小目标矩形，格子里留黑边，画面比例不变。
         * FILL：目标矩形不变，改为居中裁切纹理窗口。</p>
         */
        private void drawLaneInCell(CompositeStreamGeometry.Lane lane,
                                    float cellLeft, float cellBottom,
                                    float cellRight, float cellTop,
                                    ScaleMode scaleMode) {
            float u0 = lane.u0;
            float u1 = lane.u1;
            // 几何坐标以左上为原点，GL 纹理坐标以左下为原点，这里做一次翻转
            float glV0 = 1f - lane.v1;
            float glV1 = 1f - lane.v0;

            float left = cellLeft;
            float right = cellRight;
            float bottom = cellBottom;
            float top = cellTop;

            // NDC 上一个格子的物理宽高比 = (格子宽 / 2 * 视图宽) / (格子高 / 2 * 视图高)
            float cellAspect = ((cellRight - cellLeft) * viewportWidth)
                    / ((cellTop - cellBottom) * viewportHeight);
            float laneAspect = lane.aspect();

            if (cellAspect > 0f && laneAspect > 0f) {
                if (scaleMode == ScaleMode.FIT) {
                    if (laneAspect < cellAspect) {
                        // 画面比格子窄：左右留黑边
                        float scale = laneAspect / cellAspect;
                        float centre = (cellLeft + cellRight) / 2f;
                        float half = (cellRight - cellLeft) / 2f * scale;
                        left = centre - half;
                        right = centre + half;
                    } else if (laneAspect > cellAspect) {
                        // 画面比格子宽：上下留黑边
                        float scale = cellAspect / laneAspect;
                        float centre = (cellBottom + cellTop) / 2f;
                        float half = (cellTop - cellBottom) / 2f * scale;
                        bottom = centre - half;
                        top = centre + half;
                    }
                } else {
                    if (laneAspect < cellAspect) {
                        // 画面比格子窄：上下裁切纹理
                        float keep = laneAspect / cellAspect;
                        float centre = (glV0 + glV1) / 2f;
                        float half = (glV1 - glV0) / 2f * keep;
                        glV0 = centre - half;
                        glV1 = centre + half;
                    } else if (laneAspect > cellAspect) {
                        // 画面比格子宽：左右裁切纹理
                        float keep = cellAspect / laneAspect;
                        float centre = (u0 + u1) / 2f;
                        float half = (u1 - u0) / 2f * keep;
                        u0 = centre - half;
                        u1 = centre + half;
                    }
                }
            }

            drawQuad(u0, glV0, u1, glV1, left, bottom, right, top);
        }

        private void drawQuad(float u0, float v0, float u1, float v1,
                              float left, float bottom, float right, float top) {
            // triangle strip: 左下 -> 右下 -> 左上 -> 右上
            vertexScratch[0] = left;
            vertexScratch[1] = bottom;
            vertexScratch[2] = u0;
            vertexScratch[3] = v0;

            vertexScratch[4] = right;
            vertexScratch[5] = bottom;
            vertexScratch[6] = u1;
            vertexScratch[7] = v0;

            vertexScratch[8] = left;
            vertexScratch[9] = top;
            vertexScratch[10] = u0;
            vertexScratch[11] = v1;

            vertexScratch[12] = right;
            vertexScratch[13] = top;
            vertexScratch[14] = u1;
            vertexScratch[15] = v1;

            // 分开写，避免依赖 Buffer.position 的协变返回类型
            vertexBuffer.clear();
            vertexBuffer.put(vertexScratch);
            vertexBuffer.position(0);

            int stride = FLOATS_PER_VERTEX * Float.SIZE / Byte.SIZE;

            vertexBuffer.position(0);
            GLES20.glEnableVertexAttribArray(aPositionLoc);
            GLES20.glVertexAttribPointer(aPositionLoc, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer);

            vertexBuffer.position(2);
            GLES20.glEnableVertexAttribArray(aTexCoordLoc);
            GLES20.glVertexAttribPointer(aTexCoordLoc, 2, GLES20.GL_FLOAT, false, stride, vertexBuffer);

            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, VERTEX_COUNT);

            GLES20.glDisableVertexAttribArray(aPositionLoc);
            GLES20.glDisableVertexAttribArray(aTexCoordLoc);
        }

        void tearDown() {
            ready = false;
            if (producer != null) {
                producer.setOnFrameAvailableListener(null);
                producer.release();
                producer = null;
            }
            if (program != 0) {
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            if (oesTextureId != 0) {
                GLES20.glDeleteTextures(1, new int[]{oesTextureId}, 0);
                oesTextureId = 0;
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    eglSurface = EGL14.EGL_NO_SURFACE;
                }
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                    eglContext = EGL14.EGL_NO_CONTEXT;
                }
                EGL14.eglTerminate(eglDisplay);
                eglDisplay = EGL14.EGL_NO_DISPLAY;
            }
            AppLog.i(TAG, "GL 资源已释放");
        }

        private int buildProgram() {
            int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
            int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
            int handle = GLES20.glCreateProgram();
            GLES20.glAttachShader(handle, vertexShader);
            GLES20.glAttachShader(handle, fragmentShader);
            GLES20.glLinkProgram(handle);
            int[] linked = new int[1];
            GLES20.glGetProgramiv(handle, GLES20.GL_LINK_STATUS, linked, 0);
            if (linked[0] != GLES20.GL_TRUE) {
                String log = GLES20.glGetProgramInfoLog(handle);
                GLES20.glDeleteProgram(handle);
                throw new RuntimeException("着色器链接失败: " + log);
            }
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            return handle;
        }

        private int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] compiled = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
            if (compiled[0] != GLES20.GL_TRUE) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new RuntimeException("着色器编译失败: " + log);
            }
            return shader;
        }
    }
}
