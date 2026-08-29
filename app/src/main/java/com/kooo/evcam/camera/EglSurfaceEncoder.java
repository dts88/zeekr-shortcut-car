package com.kooo.evcam.camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.view.Surface;

import com.kooo.evcam.AppLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * EGL/OpenGL 渲染桥接类
 * 用于将 SurfaceTexture（来自 Camera）的内容渲染到 MediaCodec 的输入 Surface
 * 
 * 工作流程：
 * 1. Camera 输出到 SurfaceTexture（用于 TextureView 预览）
 * 2. 本类监听 SurfaceTexture 的 onFrameAvailable 回调
 * 3. 使用 OpenGL 将 SurfaceTexture 的内容渲染到 MediaCodec 的输入 Surface
 * 4. MediaCodec 编码后通过 MediaMuxer 写入文件
 */
public class EglSurfaceEncoder {
    private static final String TAG = "EglSurfaceEncoder";

    // Vertex shader - 简单的顶点变换
    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vTextureCoord = (uTexMatrix * aTextureCoord).xy;\n" +
            "}\n";

    // Fragment shader - 使用外部纹理（OES）采样（无水印版本）
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sTexture, vTextureCoord);\n" +
            "}\n";

    // Fragment shader - 带时间水印版本
    private static final String FRAGMENT_SHADER_WITH_WATERMARK =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform sampler2D sWatermarkTexture;\n" +
            "uniform vec4 uWatermarkRect;\n" +  // x, y, width, height (归一化坐标)
            "void main() {\n" +
            "    vec4 videoColor = texture2D(sTexture, vTextureCoord);\n" +
            "    // 检查是否在水印区域内\n" +
            "    if (vTextureCoord.x >= uWatermarkRect.x && vTextureCoord.x <= uWatermarkRect.x + uWatermarkRect.z &&\n" +
            "        vTextureCoord.y >= uWatermarkRect.y && vTextureCoord.y <= uWatermarkRect.y + uWatermarkRect.w) {\n" +
            "        // 计算水印纹理坐标\n" +
            "        vec2 watermarkCoord = vec2(\n" +
            "            (vTextureCoord.x - uWatermarkRect.x) / uWatermarkRect.z,\n" +
            "            (vTextureCoord.y - uWatermarkRect.y) / uWatermarkRect.w\n" +
            "        );\n" +
            "        vec4 watermarkColor = texture2D(sWatermarkTexture, watermarkCoord);\n" +
            "        // Alpha 混合\n" +
            "        gl_FragColor = mix(videoColor, watermarkColor, watermarkColor.a);\n" +
            "    } else {\n" +
            "        gl_FragColor = videoColor;\n" +
            "    }\n" +
            "}\n";

    // 四宫格模式下的 2D 水印叠加着色器。
    //
    // 为什么不能复用 FRAGMENT_SHADER_WITH_WATERMARK：那个着色器是靠 vTextureCoord
    // （纹理坐标）判断像素是否落在水印矩形内的。四宫格模式下每个画面只采样纹理的
    // 一个子区间，这个判断会在错误的位置命中。所以水印改为独立一遍，直接在 NDC
    // 里画一个小四边形，只采样水印位图并做 alpha 混合。
    private static final String WATERMARK_OVERLAY_VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    private static final String WATERMARK_OVERLAY_FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D sWatermark;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(sWatermark, vTexCoord);\n" +
            "}\n";

    // 顶点坐标（全屏四边形）
    private static final float[] VERTICES = {
            -1.0f, -1.0f,  // 左下
             1.0f, -1.0f,  // 右下
            -1.0f,  1.0f,  // 左上
             1.0f,  1.0f,  // 右上
    };

    // 纹理坐标
    private static final float[] TEXTURE_COORDS = {
            0.0f, 0.0f,  // 左下
            1.0f, 0.0f,  // 右下
            0.0f, 1.0f,  // 左上
            1.0f, 1.0f,  // 右上
    };

    private final String cameraId;
    private final int width;
    private final int height;

    // ---- 四宫格录制 ----
    /** 非 null 时启用四宫格录制：把合成流拆成 4 个画面渲染成 2x2。 */
    private volatile com.kooo.evcam.zeekr.CompositeStreamGeometry.Plan fourLanePlan;
    /** laneOrder[格子位置] = 合成流中的画面序号。 */
    private volatile int[] fourLaneOrder = {0, 1, 2, 3};
    private java.nio.FloatBuffer laneVertexBuffer;
    private java.nio.FloatBuffer laneTexCoordBuffer;
    private final float[] laneVertexScratch = new float[8];
    private final float[] laneTexScratch = new float[8];
    private int watermarkOverlayProgram;
    private int overlayPositionHandle;
    private int overlayTexCoordHandle;
    private int overlayTextureHandle;

    // EGL 相关
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;

    // OpenGL 相关
    private int program;
    private int textureId;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    // Shader 变量位置
    private int positionHandle;
    private int texCoordHandle;
    private int mvpMatrixHandle;
    private int texMatrixHandle;
    private int textureHandle;

    // 变换矩阵
    private final float[] mvpMatrix = new float[16];
    private final float[] texMatrix = new float[16];

    // 输入 SurfaceTexture（来自 Camera）
    private SurfaceTexture inputSurfaceTexture;

    // 状态
    private boolean isInitialized = false;
    private boolean isReleased = false;

    // 时间水印相关
    private boolean watermarkEnabled = false;
    private int watermarkProgram;
    private int watermarkTextureId;
    private int watermarkTextureHandle;
    private int watermarkRectHandle;
    private int watermarkPositionHandle;
    private int watermarkTexCoordHandle;
    private int watermarkMvpMatrixHandle;
    private int watermarkTexMatrixHandle;
    private int watermarkOesTextureHandle;
    private Bitmap watermarkBitmap;
    private String lastWatermarkTime = "";
    /** 角标第二行：录制规格。为空表示只显示时间。 */
    private volatile String watermarkInfoLine = "";
    private static final int WATERMARK_WIDTH = 560;   // 需容纳规格行（比 19 字符的时间戳长）
    private static final int WATERMARK_HEIGHT = 80;   // 两行
    private static final int WATERMARK_LINE_HEIGHT = 34;
    private final SimpleDateFormat watermarkDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
    
    // 性能优化：水印更新控制
    private long lastWatermarkUpdateMs = 0;
    private static final long WATERMARK_UPDATE_INTERVAL_MS = 1000; // 每秒更新一次水印
    
    // 性能优化：跳帧控制（仅用于录制编码，不影响预览质量）
    private int frameSkipCounter = 0;
    private static final int FRAME_SKIP_THRESHOLD = 2; // 每3帧渲染1次（当CPU高负载时）
    private boolean enableFrameSkip = false; // 默认不跳帧
    
    // 性能优化：异步渲染支持
    private volatile boolean hasPendingFrame = false; // 是否有待处理的帧
    private final Object frameLock = new Object(); // 帧处理锁
    private volatile long lastFrameTimeNs = 0; // 上一帧时间戳
    /** 默认上限约 30fps，防止过度渲染；由 {@link #setFrameRate(int)} 按用户选择收紧。 */
    private static final long DEFAULT_MIN_FRAME_INTERVAL_NS = 33_000_000L;
    /**
     * 渲染的最小帧间隔。
     *
     * <p>这里才是真正决定录制帧率的地方。MediaFormat 的 KEY_FRAME_RATE 对
     * Surface 输入的编码器只是码率分配的提示，<b>不会丢帧</b> —— 编码器输出多少帧，
     * 取决于我们隔多久 eglSwapBuffers 一次。之前这个值写死成 33ms，
     * 所以设置里选 10fps 也照样按 30fps 出帧。</p>
     */
    private volatile long minFrameIntervalNs = DEFAULT_MIN_FRAME_INTERVAL_NS;
    
    // 性能优化：复用缓冲区，减少GC
    private final float[] tempMatrix = new float[16];
    private long frameCount = 0;

    // 优化：控制何时需要清除缓冲（避免闪屏）
    private boolean needsClear = true;  // 初始需要清除

    public EglSurfaceEncoder(String cameraId, int width, int height) {
        this.cameraId = cameraId;
        this.width = width;
        this.height = height;

        // 初始化 MVP 矩阵为单位矩阵
        Matrix.setIdentityM(mvpMatrix, 0);
    }

    /**
     * 初始化 EGL 和 OpenGL
     * @param outputSurface MediaCodec 的输入 Surface
     * @return 创建的 OES 纹理 ID（用于创建 SurfaceTexture 供 Camera 输出）
     */
    public int initialize(Surface outputSurface) {
        if (isInitialized) {
            AppLog.w(TAG, "Camera " + cameraId + " EglSurfaceEncoder already initialized");
            return textureId;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Initializing EglSurfaceEncoder " + width + "x" + height);

        try {
            // 初始化 EGL
            initEgl(outputSurface);

            // 初始化 OpenGL
            initGl();

            isInitialized = true;
            AppLog.d(TAG, "Camera " + cameraId + " EglSurfaceEncoder initialized, textureId=" + textureId);

            return textureId;

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to initialize EglSurfaceEncoder", e);
            release();
            throw new RuntimeException("Failed to initialize EglSurfaceEncoder", e);
        }
    }

    /**
     * 设置输入 SurfaceTexture
     */
    public void setInputSurfaceTexture(SurfaceTexture surfaceTexture) {
        this.inputSurfaceTexture = surfaceTexture;
        AppLog.d(TAG, "Camera " + cameraId + " Input SurfaceTexture set");
    }

    /**
     * 设置是否启用时间水印
     * @param enabled true 表示启用水印
     */
    /**
     * 设置角标第二行（录制规格）。传空串则只画时间那一行。
     *
     * <p>规格在一次录制中是不变的，所以这里只是存下来；真正重绘由每秒一次的
     * 时间更新顺带完成 —— 清空 {@code lastWatermarkTime} 是为了不必等到秒数变化。</p>
     */
    public void setWatermarkInfoLine(String line) {
        this.watermarkInfoLine = line == null ? "" : line;
        this.lastWatermarkTime = "";
    }

    public void setWatermarkEnabled(boolean enabled) {
        this.watermarkEnabled = enabled;
        AppLog.d(TAG, "Camera " + cameraId + " Watermark " + (enabled ? "enabled" : "disabled"));
        
        // 如果已初始化且启用水印，需要初始化水印相关资源
        if (isInitialized && enabled && watermarkProgram == 0) {
            initWatermarkGl();
        }
    }

    /**
     * 检查是否启用了时间水印
     */
    public boolean isWatermarkEnabled() {
        return watermarkEnabled;
    }

    /**
     * 设置是否启用跳帧（用于降低CPU占用）
     * @param enabled true 表示启用跳帧
     */
    public void setFrameSkipEnabled(boolean enabled) {
        this.enableFrameSkip = enabled;
        this.frameSkipCounter = 0;
        AppLog.d(TAG, "Camera " + cameraId + " Frame skip " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * 检查是否启用了跳帧
     */
    public boolean isFrameSkipEnabled() {
        return enableFrameSkip;
    }

    /**
     * 渲染一帧到输出 Surface
     * 应该在 SurfaceTexture.onFrameAvailable 回调中调用
     * @param presentationTimeNs 帧的呈现时间（纳秒）
     */
    /**
     * 设置录制帧率。这会改变实际的出帧节奏，而不只是编码器的码率提示。
     *
     * @param fps 目标帧率；<= 0 表示恢复默认上限（约 30fps）
     */
    public void setFrameRate(int fps) {
        if (fps <= 0) {
            minFrameIntervalNs = DEFAULT_MIN_FRAME_INTERVAL_NS;
            AppLog.d(TAG, "Camera " + cameraId + " 渲染帧率恢复默认上限");
            return;
        }
        int clamped = Math.max(1, Math.min(60, fps));
        minFrameIntervalNs = 1_000_000_000L / clamped;
        AppLog.i(TAG, "Camera " + cameraId + " 渲染帧率上限设为 " + clamped
                + " fps（间隔 " + (minFrameIntervalNs / 1_000_000L) + "ms）");
    }

    public void drawFrame(long presentationTimeNs) {
        if (!isInitialized || isReleased) {
            return;
        }

        if (inputSurfaceTexture == null) {
            AppLog.w(TAG, "Camera " + cameraId + " No input SurfaceTexture set");
            return;
        }

        // 性能优化：帧率控制，防止过度渲染占用CPU
        long currentTimeNs = System.nanoTime();
        if (currentTimeNs - lastFrameTimeNs < minFrameIntervalNs) {
            // 帧间隔太短，跳过渲染但消费帧
            try {
                makeCurrent();
                inputSurfaceTexture.updateTexImage();
            } catch (Exception e) {
                // 忽略
            }
            return;
        }

        // 性能优化：跳帧控制（当启用时，每3帧渲染1次）
        if (enableFrameSkip) {
            frameSkipCounter++;
            if (frameSkipCounter < FRAME_SKIP_THRESHOLD) {
                // 跳过渲染，但更新时间戳
                try {
                    makeCurrent();
                    inputSurfaceTexture.updateTexImage();
                } catch (Exception e) {
                    // 忽略
                }
                return;
            }
            frameSkipCounter = 0;
        }

        try {
            // 首先绑定 EGL context（必须在 updateTexImage 之前）
            makeCurrent();

            // 更新纹理（需要在正确的 EGL context 中）
            inputSurfaceTexture.updateTexImage();
            inputSurfaceTexture.getTransformMatrix(texMatrix);
            lastFrameTimeNs = currentTimeNs;

            // 设置视口
            GLES20.glViewport(0, 0, width, height);

            // 优化：只在必要时清除缓冲，避免闪屏
            if (needsClear) {
                GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                needsClear = false;
            }

            // 四宫格模式优先：拆成 2x2 渲染，水印另走一遍叠加
            com.kooo.evcam.zeekr.CompositeStreamGeometry.Plan plan = fourLanePlan;
            if (plan != null) {
                drawFourLanes(plan);
                if (watermarkEnabled) {
                    drawWatermarkOverlay();
                }
            } else if (watermarkEnabled && watermarkProgram != 0) {
                drawFrameWithWatermark();
            } else {
                drawFrameWithoutWatermark();
            }

            // 设置呈现时间戳并交换缓冲区
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, presentationTimeNs);
            EGL14.eglSwapBuffers(eglDisplay, eglSurface);

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Error drawing frame", e);
        }
    }

    /**
     * 启用四宫格录制。
     *
     * <p>传入的 plan 必须是按<b>合成流真实尺寸</b>算出来的（见 CompositeStreamGeometry），
     * 因为它用的是归一化坐标，与编码输出尺寸无关。传 null 关闭，恢复整帧录制。</p>
     *
     * @param plan  四画面拆分方案；null 表示不拆分
     * @param order 长度为 4 的排列，order[格子位置] = 画面序号；null 表示默认顺序
     */
    public void setFourLanePlan(com.kooo.evcam.zeekr.CompositeStreamGeometry.Plan plan, int[] order) {
        this.fourLanePlan = (plan != null && plan.isComposite()) ? plan : null;
        if (order != null && order.length == 4) {
            this.fourLaneOrder = order.clone();
        }
        AppLog.i(TAG, "Camera " + cameraId + " 四宫格录制: "
                + (this.fourLanePlan != null ? "启用 (" + this.fourLanePlan + ")" : "关闭"));
    }

    public boolean isFourLaneEnabled() {
        return fourLanePlan != null;
    }

    /**
     * 四宫格渲染：把同一张 OES 纹理的四个子区域画进 2x2 的四个格子。
     *
     * <p>输出是正方形、每个画面也是正方形，所以不需要额外的比例修正。</p>
     */
    private void drawFourLanes(com.kooo.evcam.zeekr.CompositeStreamGeometry.Plan plan) {
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0);
        GLES20.glUniform1i(textureHandle, 0);

        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glEnableVertexAttribArray(texCoordHandle);

        int[] order = fourLaneOrder;
        for (int cell = 0; cell < 4; cell++) {
            int laneIndex = order[cell];
            if (laneIndex >= plan.laneCount()) {
                continue;
            }
            com.kooo.evcam.zeekr.CompositeStreamGeometry.Lane lane = plan.lane(laneIndex);

            // 目标格子（NDC）：格子 0 左上、1 右上、2 左下、3 右下
            float left = -1.0f + (cell % 2);
            float right = left + 1.0f;
            float top = 1.0f - (cell / 2);
            float bottom = top - 1.0f;

            // 几何坐标以左上为原点，GL 纹理坐标以左下为原点，这里翻一次
            float u0 = lane.u0;
            float u1 = lane.u1;
            float v0 = 1.0f - lane.v1;
            float v1 = 1.0f - lane.v0;

            // triangle strip: 左下 -> 右下 -> 左上 -> 右上
            laneVertexScratch[0] = left;  laneVertexScratch[1] = bottom;
            laneVertexScratch[2] = right; laneVertexScratch[3] = bottom;
            laneVertexScratch[4] = left;  laneVertexScratch[5] = top;
            laneVertexScratch[6] = right; laneVertexScratch[7] = top;

            laneTexScratch[0] = u0; laneTexScratch[1] = v0;
            laneTexScratch[2] = u1; laneTexScratch[3] = v0;
            laneTexScratch[4] = u0; laneTexScratch[5] = v1;
            laneTexScratch[6] = u1; laneTexScratch[7] = v1;

            laneVertexBuffer.clear();
            laneVertexBuffer.put(laneVertexScratch);
            laneVertexBuffer.position(0);
            laneTexCoordBuffer.clear();
            laneTexCoordBuffer.put(laneTexScratch);
            laneTexCoordBuffer.position(0);

            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, laneVertexBuffer);
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, laneTexCoordBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texCoordHandle);
    }

    /**
     * 四宫格模式下的水印：单独一遍，画在输出画面的右上角。
     */
    private void drawWatermarkOverlay() {
        if (watermarkOverlayProgram == 0 || watermarkTextureId == 0) {
            return;
        }
        long currentTimeMs = System.currentTimeMillis();
        if (currentTimeMs - lastWatermarkUpdateMs >= WATERMARK_UPDATE_INTERVAL_MS) {
            updateWatermarkBitmap();
            lastWatermarkUpdateMs = currentTimeMs;
        }

        float w = 2.0f * WATERMARK_WIDTH / width;    // NDC 宽度
        float h = 2.0f * WATERMARK_HEIGHT / height;  // NDC 高度
        float margin = 0.02f;
        float right = 1.0f - margin;
        float left = right - w;
        float top = 1.0f - margin;
        float bottom = top - h;

        laneVertexScratch[0] = left;  laneVertexScratch[1] = bottom;
        laneVertexScratch[2] = right; laneVertexScratch[3] = bottom;
        laneVertexScratch[4] = left;  laneVertexScratch[5] = top;
        laneVertexScratch[6] = right; laneVertexScratch[7] = top;

        // 水印位图左上为原点，这里上下翻转贴图
        laneTexScratch[0] = 0f; laneTexScratch[1] = 1f;
        laneTexScratch[2] = 1f; laneTexScratch[3] = 1f;
        laneTexScratch[4] = 0f; laneTexScratch[5] = 0f;
        laneTexScratch[6] = 1f; laneTexScratch[7] = 0f;

        laneVertexBuffer.clear();
        laneVertexBuffer.put(laneVertexScratch);
        laneVertexBuffer.position(0);
        laneTexCoordBuffer.clear();
        laneTexCoordBuffer.put(laneTexScratch);
        laneTexCoordBuffer.position(0);

        GLES20.glUseProgram(watermarkOverlayProgram);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId);
        GLES20.glUniform1i(overlayTextureHandle, 0);

        GLES20.glEnableVertexAttribArray(overlayPositionHandle);
        GLES20.glVertexAttribPointer(overlayPositionHandle, 2, GLES20.GL_FLOAT, false, 0, laneVertexBuffer);
        GLES20.glEnableVertexAttribArray(overlayTexCoordHandle);
        GLES20.glVertexAttribPointer(overlayTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, laneTexCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(overlayPositionHandle);
        GLES20.glDisableVertexAttribArray(overlayTexCoordHandle);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /**
     * 无水印渲染
     */
    private void drawFrameWithoutWatermark() {
        // 使用着色器程序
        GLES20.glUseProgram(program);
        checkGlError("glUseProgram");

        // 绑定纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

        // 设置 uniform 变量
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0);
        GLES20.glUniform1i(textureHandle, 0);

        // 设置顶点属性
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(texCoordHandle);
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("glDrawArrays");

        // 禁用顶点属性
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texCoordHandle);
    }

    // 性能优化：缓存水印位置（只需计算一次）
    private float watermarkX, watermarkY, watermarkW, watermarkH;
    private boolean watermarkPositionCached = false;
    
    /**
     * 带水印渲染（优化版）
     */
    private void drawFrameWithWatermark() {
        // 性能优化：控制水印更新频率（每秒最多更新一次）
        long currentTimeMs = System.currentTimeMillis();
        if (currentTimeMs - lastWatermarkUpdateMs >= WATERMARK_UPDATE_INTERVAL_MS) {
            updateWatermarkBitmap();
            lastWatermarkUpdateMs = currentTimeMs;
        }

        // 使用水印着色器程序
        GLES20.glUseProgram(watermarkProgram);
        checkGlError("glUseProgram watermark");

        // 绑定视频纹理到纹理单元0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glUniform1i(watermarkOesTextureHandle, 0);

        // 绑定水印纹理到纹理单元1
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId);
        GLES20.glUniform1i(watermarkTextureHandle, 1);

        // 设置 uniform 变量
        GLES20.glUniformMatrix4fv(watermarkMvpMatrixHandle, 1, false, mvpMatrix, 0);
        GLES20.glUniformMatrix4fv(watermarkTexMatrixHandle, 1, false, texMatrix, 0);

        // 性能优化：缓存水印位置计算结果
        if (!watermarkPositionCached) {
            watermarkW = (float) WATERMARK_WIDTH / width;   // 水印宽度占比
            watermarkH = (float) WATERMARK_HEIGHT / height; // 水印高度占比
            watermarkX = 1.0f - watermarkW - 0.01f;  // 右边距 1%
            watermarkY = 0.01f;  // 上边距 1%
            watermarkPositionCached = true;
        }
        GLES20.glUniform4f(watermarkRectHandle, watermarkX, watermarkY, watermarkW, watermarkH);

        // 设置顶点属性
        GLES20.glEnableVertexAttribArray(watermarkPositionHandle);
        GLES20.glVertexAttribPointer(watermarkPositionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(watermarkTexCoordHandle);
        GLES20.glVertexAttribPointer(watermarkTexCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        checkGlError("glDrawArrays watermark");

        // 禁用顶点属性
        GLES20.glDisableVertexAttribArray(watermarkPositionHandle);
        GLES20.glDisableVertexAttribArray(watermarkTexCoordHandle);
    }

    /**
     * 更新输出 Surface（用于分段切换时）
     * 销毁旧的 EGL Surface，创建新的绑定到新的 MediaCodec 输入 Surface
     * @param newOutputSurface 新的 MediaCodec 输入 Surface
     */
    public void updateOutputSurface(Surface newOutputSurface) {
        if (!isInitialized || isReleased) {
            AppLog.w(TAG, "Camera " + cameraId + " Cannot update output surface: not initialized or released");
            return;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Updating output surface");

        try {
            // 销毁旧的 EGL Surface
            // 注意：当 surface 为 EGL_NO_SURFACE 时，context 必须也是 EGL_NO_CONTEXT，否则会报 EGL_BAD_MATCH
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }

            // 创建新的 EGL Surface
            int[] surfaceAttribList = {
                    EGL14.EGL_NONE
            };
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, newOutputSurface, surfaceAttribList, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                throw new RuntimeException("Unable to create new EGL window surface");
            }

            // 设置为当前上下文
            makeCurrent();

            // 标记需要清除缓冲，因为新 Surface 没有之前的内容
            needsClear = true;

            AppLog.d(TAG, "Camera " + cameraId + " Output surface updated successfully");

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to update output surface", e);
            throw new RuntimeException("Failed to update output surface", e);
        }
    }

    /**
     * 仅消费帧而不渲染（用于非录制状态时保持 SurfaceTexture 正常工作）
     * 关键：必须调用 updateTexImage() 来消费帧，否则 SurfaceTexture 会保持 pending 状态，
     * 不再触发后续的 onFrameAvailable 回调
     */
    public void consumeFrame() {
        if (!isInitialized || isReleased) {
            return;
        }

        if (inputSurfaceTexture == null) {
            return;
        }

        try {
            // 绑定 EGL context（必须在 updateTexImage 之前）
            makeCurrent();
            // 只消费帧，不渲染
            inputSurfaceTexture.updateTexImage();
        } catch (Exception e) {
            // 非录制状态下的错误不需要记录
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        if (isReleased) {
            return;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Releasing EglSurfaceEncoder");

        isReleased = true;
        isInitialized = false;

        try {
            // 释放 OpenGL 资源
            if (program != 0) {
                try {
                    GLES20.glDeleteProgram(program);
                } catch (Exception e) {
                    AppLog.w(TAG, "Camera " + cameraId + " Error deleting program: " + e.getMessage());
                }
                program = 0;
            }

            if (textureId != 0) {
                try {
                    int[] textures = {textureId};
                    GLES20.glDeleteTextures(1, textures, 0);
                } catch (Exception e) {
                    AppLog.w(TAG, "Camera " + cameraId + " Error deleting texture: " + e.getMessage());
                }
                textureId = 0;
            }

            // 释放水印相关资源
            if (watermarkProgram != 0) {
                try {
                    GLES20.glDeleteProgram(watermarkProgram);
                } catch (Exception e) {
                    AppLog.w(TAG, "Camera " + cameraId + " Error deleting watermark program: " + e.getMessage());
                }
                watermarkProgram = 0;
            }

            if (watermarkTextureId != 0) {
                try {
                    int[] textures = {watermarkTextureId};
                    GLES20.glDeleteTextures(1, textures, 0);
                } catch (Exception e) {
                    AppLog.w(TAG, "Camera " + cameraId + " Error deleting watermark texture: " + e.getMessage());
                }
                watermarkTextureId = 0;
            }

            if (watermarkBitmap != null) {
                watermarkBitmap.recycle();
                watermarkBitmap = null;
            }

            // 释放 EGL 资源
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                try {
                    EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                } catch (Exception e) {
                    AppLog.w(TAG, "Camera " + cameraId + " Error making EGL no current: " + e.getMessage());
                }

                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    try {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface);
                    } catch (Exception e) {
                        AppLog.w(TAG, "Camera " + cameraId + " Error destroying EGL surface: " + e.getMessage());
                    }
                    eglSurface = EGL14.EGL_NO_SURFACE;
                }

                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    try {
                        EGL14.eglDestroyContext(eglDisplay, eglContext);
                    } catch (Exception e) {
                        AppLog.w(TAG, "Camera " + cameraId + " Error destroying EGL context: " + e.getMessage());
                    }
                    eglContext = EGL14.EGL_NO_CONTEXT;
                }

                try {
                    EGL14.eglTerminate(eglDisplay);
                } catch (Exception e) {
                    AppLog.w(TAG, "Camera " + cameraId + " Error terminating EGL: " + e.getMessage());
                }
                eglDisplay = EGL14.EGL_NO_DISPLAY;
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Error during release", e);
        }

        inputSurfaceTexture = null;

        AppLog.d(TAG, "Camera " + cameraId + " EglSurfaceEncoder released");
    }

    /**
     * 获取纹理 ID
     */
    public int getTextureId() {
        return textureId;
    }

    /**
     * 检查是否已初始化
     */
    public boolean isInitialized() {
        return isInitialized && !isReleased;
    }

    // ===== 私有方法 =====

    /**
     * 初始化 EGL
     */
    private void initEgl(Surface outputSurface) {
        // 获取 EGL Display
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("Unable to get EGL14 display");
        }

        // 初始化 EGL
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("Unable to initialize EGL14");
        }
        AppLog.d(TAG, "Camera " + cameraId + " EGL initialized: " + version[0] + "." + version[1]);

        // 选择 EGL 配置
        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT | EGL14.EGL_WINDOW_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,  // 重要：支持录制
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
            throw new RuntimeException("Unable to find suitable EGL config");
        }
        eglConfig = configs[0];

        // 创建 EGL Context
        int[] contextAttribList = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribList, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("Unable to create EGL context");
        }

        // 创建 EGL Surface（绑定到 MediaCodec 的输入 Surface）
        int[] surfaceAttribList = {
                EGL14.EGL_NONE
        };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribList, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("Unable to create EGL window surface");
        }

        // 设置为当前上下文
        makeCurrent();

        AppLog.d(TAG, "Camera " + cameraId + " EGL setup complete");
    }

    /**
     * 初始化 OpenGL
     */
    private void initGl() {
        // 创建着色器程序
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (program == 0) {
            throw new RuntimeException("Unable to create shader program");
        }

        // 获取属性位置
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix");
        textureHandle = GLES20.glGetUniformLocation(program, "sTexture");

        // 创建 OES 纹理
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        textureId = textures[0];

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // 创建顶点缓冲
        vertexBuffer = createFloatBuffer(VERTICES);
        texCoordBuffer = createFloatBuffer(TEXTURE_COORDS);

        // 四宫格模式每帧要改写顶点/纹理坐标，单独准备可写缓冲
        laneVertexBuffer = createFloatBuffer(new float[8]);
        laneTexCoordBuffer = createFloatBuffer(new float[8]);

        // 四宫格下的水印叠加程序（失败不致命，只是没有水印）
        watermarkOverlayProgram = createProgram(
                WATERMARK_OVERLAY_VERTEX_SHADER, WATERMARK_OVERLAY_FRAGMENT_SHADER);
        if (watermarkOverlayProgram != 0) {
            overlayPositionHandle = GLES20.glGetAttribLocation(watermarkOverlayProgram, "aPosition");
            overlayTexCoordHandle = GLES20.glGetAttribLocation(watermarkOverlayProgram, "aTexCoord");
            overlayTextureHandle = GLES20.glGetUniformLocation(watermarkOverlayProgram, "sWatermark");
        } else {
            AppLog.w(TAG, "Camera " + cameraId + " 水印叠加着色器创建失败，四宫格模式将没有水印");
        }

        AppLog.d(TAG, "Camera " + cameraId + " OpenGL setup complete, textureId=" + textureId);
    }

    /**
     * 初始化水印相关的 OpenGL 资源
     */
    private void initWatermarkGl() {
        if (watermarkProgram != 0) {
            return;  // 已经初始化过了
        }

        AppLog.d(TAG, "Camera " + cameraId + " Initializing watermark OpenGL resources");

        // 创建带水印的着色器程序
        watermarkProgram = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_WITH_WATERMARK);
        if (watermarkProgram == 0) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to create watermark shader program");
            return;
        }

        // 获取属性位置
        watermarkPositionHandle = GLES20.glGetAttribLocation(watermarkProgram, "aPosition");
        watermarkTexCoordHandle = GLES20.glGetAttribLocation(watermarkProgram, "aTextureCoord");
        watermarkMvpMatrixHandle = GLES20.glGetUniformLocation(watermarkProgram, "uMVPMatrix");
        watermarkTexMatrixHandle = GLES20.glGetUniformLocation(watermarkProgram, "uTexMatrix");
        watermarkOesTextureHandle = GLES20.glGetUniformLocation(watermarkProgram, "sTexture");
        watermarkTextureHandle = GLES20.glGetUniformLocation(watermarkProgram, "sWatermarkTexture");
        watermarkRectHandle = GLES20.glGetUniformLocation(watermarkProgram, "uWatermarkRect");

        // 创建水印纹理
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        watermarkTextureId = textures[0];

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // 创建初始水印位图
        watermarkBitmap = Bitmap.createBitmap(WATERMARK_WIDTH, WATERMARK_HEIGHT, Bitmap.Config.ARGB_8888);
        updateWatermarkBitmap();

        AppLog.d(TAG, "Camera " + cameraId + " Watermark OpenGL resources initialized, textureId=" + watermarkTextureId);
    }

    /**
     * 更新水印位图（每秒调用一次）
     */
    private void updateWatermarkBitmap() {
        if (watermarkBitmap == null) {
            return;
        }

        String currentTime = watermarkDateFormat.format(new Date());
        
        // 只有时间变化时才更新
        if (currentTime.equals(lastWatermarkTime)) {
            return;
        }
        lastWatermarkTime = currentTime;

        // 清除位图
        watermarkBitmap.eraseColor(Color.TRANSPARENT);

        Canvas canvas = new Canvas(watermarkBitmap);

        // 设置画笔 - 阴影
        Paint shadowPaint = new Paint();
        shadowPaint.setColor(Color.BLACK);
        shadowPaint.setTextSize(28);
        shadowPaint.setAntiAlias(true);
        shadowPaint.setTypeface(Typeface.MONOSPACE);

        // 设置画笔 - 主文字
        Paint textPaint = new Paint();
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(28);
        textPaint.setAntiAlias(true);
        textPaint.setTypeface(Typeface.MONOSPACE);

        // 第一行：时间
        canvas.drawText(currentTime, 8, 32, shadowPaint);
        canvas.drawText(currentTime, 6, 30, textPaint);

        // 第二行：录制规格。字号小一点 —— 它是参考信息，不该抢时间那一行的位置
        String info = watermarkInfoLine;
        if (info != null && !info.isEmpty()) {
            shadowPaint.setTextSize(22);
            textPaint.setTextSize(22);
            float baseline = 30 + WATERMARK_LINE_HEIGHT;
            canvas.drawText(info, 8, baseline + 2, shadowPaint);
            canvas.drawText(info, 6, baseline, textPaint);
        }

        // 上传纹理到 GPU
        if (watermarkTextureId != 0) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, watermarkTextureId);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, watermarkBitmap, 0);
        }
    }

    /**
     * 设置为当前 EGL 上下文
     */
    private void makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    /**
     * 创建着色器程序
     */
    private int createProgram(String vertexSource, String fragmentSource) {
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vertexShader == 0) {
            return 0;
        }

        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (fragmentShader == 0) {
            GLES20.glDeleteShader(vertexShader);
            return 0;
        }

        int program = GLES20.glCreateProgram();
        if (program == 0) {
            AppLog.e(TAG, "Could not create program");
            return 0;
        }

        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            AppLog.e(TAG, "Could not link program: " + GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteProgram(program);
            return 0;
        }

        // 删除着色器（已链接到程序）
        GLES20.glDeleteShader(vertexShader);
        GLES20.glDeleteShader(fragmentShader);

        return program;
    }

    /**
     * 加载着色器
     */
    private int loadShader(int shaderType, String source) {
        int shader = GLES20.glCreateShader(shaderType);
        if (shader == 0) {
            AppLog.e(TAG, "Could not create shader type " + shaderType);
            return 0;
        }

        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            AppLog.e(TAG, "Could not compile shader " + shaderType + ": " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }

        return shader;
    }

    /**
     * 创建 FloatBuffer
     */
    private FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    /**
     * 检查 OpenGL 错误
     */
    private void checkGlError(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            AppLog.e(TAG, "Camera " + cameraId + " " + op + ": glError " + error);
        }
    }
}
