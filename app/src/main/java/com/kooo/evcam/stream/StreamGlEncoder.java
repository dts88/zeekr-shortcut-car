package com.kooo.evcam.stream;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLES30;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Surface;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.AppConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 独立 GL 编码管线：把摄像头 OES 纹理经"鱼眼矫正 + cover 等比缩放 + pan 位置偏移"渲染到
 * 一个目标分辨率的 FBO，再用 PBO 双缓冲异步读回 RGBA，交给外部回调做 JPEG 编码。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>自带 EGL context + SurfaceTexture + OES 纹理，把返回的 intermediate Surface
 *       注册到 Camera2 作为一路预览输出，不侵入 SingleCamera 的 FisheyeCorrector。</li>
 *   <li>鱼眼 shader 复用 FisheyeCorrector 的实现，新增 cover/pan uniforms。</li>
 *   <li>PBO 双缓冲：本帧 glReadPixels 立即返回，下一帧取上一帧数据，GL 管线零阻塞。</li>
 *   <li>参数热更新：pan/cover/quality/width/height 可实时调整，分辨率变化时重建 FBO。</li>
 * </ul>
 */
public class StreamGlEncoder {
    private static final String TAG = "StreamGlEncoder";

    // ===== Shader =====
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

    /**
     * 鱼眼矫正 + cover/pan 片段着色器。
     * <p>
     * 工作流程：
     * 1. 先按 uRotation 旋转坐标；
     * 2. 在归一化空间做鱼眼径向畸变矫正（k1/k2/center/zoom）；
     * 3. 应用 cover 缩放：把矫正后的纹理坐标按目标宽高比放大到能覆盖目标，
     *    多出部分超出 [0,1] 被裁切；
     * 4. 应用 pan 偏移：在 cover 范围内平移裁剪窗中心。
     * <p>
     * uCoverScale 额外放大裁剪窗（1.0=刚好 cover，2.0=放大到 2 倍视野）。
     * uPanX/uPanY 范围 [0,1]，0.5 为居中。
     */
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision highp float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "uniform float uK1;\n" +
            "uniform float uK2;\n" +
            "uniform float uZoom;\n" +
            "uniform vec2  uCenter;\n" +
            "uniform float uRotation;\n" +
            "uniform vec2  uSrcRatio;    // 源纹理宽高比 (w/h)\n" +
            "uniform vec2  uDstRatio;    // 目标宽高比 (w/h)\n" +
            "uniform float uCoverScale;  // 覆盖缩放额外倍数\n" +
            "uniform vec2  uPan;         // pan 偏移 [0,1], (0.5,0.5) 居中\n" +
            "const float PI = 3.14159265;\n" +
            "void main() {\n" +
            "    // 1. 旋转\n" +
            "    float angle = uRotation * PI / 180.0;\n" +
            "    vec2 center = vec2(0.5, 0.5);\n" +
            "    vec2 rc = vec2(\n" +
            "        cos(angle) * (vTextureCoord.x - center.x) - sin(angle) * (vTextureCoord.y - center.y) + center.x,\n" +
            "        sin(angle) * (vTextureCoord.x - center.x) + cos(angle) * (vTextureCoord.y - center.y) + center.y\n" +
            "    );\n" +
            "    // 2. 鱼眼矫正\n" +
            "    vec2 coord = (rc - uCenter) / uZoom;\n" +
            "    float r = length(coord);\n" +
            "    float r2 = r * r;\n" +
            "    float r4 = r2 * r2;\n" +
            "    float distortion = 1.0 + uK1 * r2 + uK2 * r4;\n" +
            "    vec2 corrected = coord * distortion + uCenter;\n" +
            "    if (corrected.x < 0.0 || corrected.x > 1.0 ||\n" +
            "        corrected.y < 0.0 || corrected.y > 1.0) {\n" +
            "        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
            "        return;\n" +
            "    }\n" +
            "    // 3. cover 等比缩放：源画面等比放大覆盖目标，多出部分裁掉\n" +
            "    //    srcRatio = srcW/srcH，dstRatio = dstW/dstH\n" +
            "    //    s≥1：s 越大采样范围越小（只取中心一块），画面显得越大\n" +
            "    //    采样坐标 = (uv - 0.5) / s + 0.5\n" +
            "    float srcRatio = uSrcRatio.x / uSrcRatio.y;\n" +
            "    float dstRatio = uDstRatio.x / uDstRatio.y;\n" +
            "    float s;\n" +
            "    if (srcRatio >= dstRatio) {\n" +
            "        s = srcRatio / dstRatio;\n" +
            "    } else {\n" +
            "        s = dstRatio / srcRatio;\n" +
            "    }\n" +
            "    s *= uCoverScale;\n" +
            "    vec2 scaled = (corrected - 0.5) / s + 0.5;\n" +
            "    // 4. pan 偏移：在 cover 裁剪窗的可用范围内移动采样中心\n" +
            "    //    可用范围 = (s - 1) / 2，pan [0,1] 映射到 [-range, +range]\n" +
            "    float panRange = max(0.0, (s - 1.0) * 0.5);\n" +
            "    vec2 pan = (uPan - 0.5) * 2.0 * vec2(panRange, panRange);\n" +
            "    scaled += pan;\n" +
            "    if (scaled.x < 0.0 || scaled.x > 1.0 ||\n" +
            "        scaled.y < 0.0 || scaled.y > 1.0) {\n" +
            "        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
            "        return;\n" +
            "    }\n" +
            "    vec2 texCoord = (uTexMatrix * vec4(scaled, 0.0, 1.0)).xy;\n" +
            "    gl_FragColor = texture2D(sTexture, texCoord);\n" +
            "}\n";

    private static final float[] VERTICES = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f,
    };
    private static final float[] TEX_COORDS = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
    };

    // ===== 状态 =====
    private final String cameraId;
    private int outWidth;
    private int outHeight;
    private final FrameCallback frameCallback;

    // EGL
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLConfig eglConfig;
    // 不需要 window surface，FBO 是唯一的渲染目标

    // GL
    private int program;
    private int oesTextureId;
    private int fboId;
    private int renderTextureId;  // FBO 附着的 2D 纹理
    private int[] pboIds = new int[2];
    private int pboIndex = 0;
    private boolean pboReady;  // 是否已经有第一帧 DMA 完成
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    // Shader handles
    private int positionHandle, texCoordHandle;
    private int mvpMatrixHandle, texMatrixHandle, textureHandle;
    private int k1Handle, k2Handle, zoomHandle, centerHandle, rotationHandle;
    private int srcRatioHandle, dstRatioHandle, coverScaleHandle, panHandle;

    private final float[] mvpMatrix = new float[16];
    private final float[] texMatrix = new float[16];

    // 中间 SurfaceTexture（Camera2 输出到此）
    private SurfaceTexture intermediateSurfaceTexture;
    private Surface intermediateSurface;
    private int srcWidth = 0;  // 源纹理实际宽高，从 SurfaceTexture 回调获取
    private int srcHeight = 0;

    // 渲染线程
    private HandlerThread renderThread;
    private Handler renderHandler;

    // 参数（可热更新）
    private volatile float k1, k2, zoom = 1.0f, centerX = 0.5f, centerY = 0.5f;
    private volatile int rotation = 0;
    private volatile float panX = 0.5f, panY = 0.5f;
    private volatile float coverScale = 1.0f;

    private volatile boolean isReleased = false;
    private volatile boolean isInitialized = false;

    /** 帧回调：在 renderHandler 线程被调用，外部应尽快把 RGBA 转 JPEG，避免阻塞 GL。 */
    public interface FrameCallback {
        void onFrame(ByteBuffer rgba, int width, int height);
    }

    public StreamGlEncoder(String cameraId, int outWidth, int outHeight, FrameCallback cb) {
        this.cameraId = cameraId;
        this.outWidth = outWidth;
        this.outHeight = outHeight;
        this.frameCallback = cb;
        Matrix.setIdentityM(mvpMatrix, 0);
    }

    /** 返回应注册到 Camera2 的 Surface。在 init 前调用。 */
    public Surface getIntermediateSurface() {
        return intermediateSurface;
    }

    public boolean isInitialized() {
        return isInitialized && !isReleased;
    }

    /**
     * 初始化 EGL/GL/FBO/PBO，必须在 renderHandler 线程调用。
     * @param srcW 源纹理宽（Camera 预览宽），srcH 源纹理高
     */
    public void init(int srcW, int srcH) {
        if (isInitialized) return;
        AppLog.d(TAG, "Camera " + cameraId + " init " + outWidth + "x" + outHeight + " src=" + srcW + "x" + srcH);
        this.srcWidth = srcW;
        this.srcHeight = srcH;
        try {
            initEgl();
            initGl();
            initFbo();
            initPbo();

            intermediateSurfaceTexture = new SurfaceTexture(oesTextureId);
            intermediateSurfaceTexture.setDefaultBufferSize(srcW, srcH);
            intermediateSurface = new Surface(intermediateSurfaceTexture);
            intermediateSurfaceTexture.setOnFrameAvailableListener(st -> drawFrame(), renderHandler);

            isInitialized = true;
            AppLog.d(TAG, "Camera " + cameraId + " StreamGlEncoder initialized, texture=" + oesTextureId);
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " init failed", e);
            release();
            throw new RuntimeException("StreamGlEncoder init failed", e);
        }
    }

    // ===== 参数热更新 =====

    public void setFisheyeParams(float k1, float k2, float zoom, float cx, float cy, int rotation) {
        this.k1 = k1; this.k2 = k2; this.zoom = zoom;
        this.centerX = cx; this.centerY = cy; this.rotation = rotation;
    }

    public void setPan(float panX, float panY) {
        this.panX = clamp(panX, 0f, 1f);
        this.panY = clamp(panY, 0f, 1f);
    }

    public void setCoverScale(float scale) {
        this.coverScale = Math.max(1.0f, scale);
    }

    /**
     * 改变输出分辨率。会重建 FBO + PBO。必须在 renderHandler 线程调用。
     */
    public void resize(int newW, int newH) {
        if (newW == outWidth && newH == outHeight) return;
        outWidth = newW;
        outHeight = newH;
        if (isInitialized) {
            releaseFbo();
            releasePbo();
            initFbo();
            initPbo();
            pboReady = false;
        }
        AppLog.d(TAG, "Camera " + cameraId + " resized to " + outWidth + "x" + outHeight);
    }

    public int getOutWidth() { return outWidth; }
    public int getOutHeight() { return outHeight; }

    /** 更新源纹理尺寸（切换摄像头时预览分辨率可能不同）。必须在 renderHandler 线程调用。 */
    public void updateSrcSize(int srcW, int srcH) {
        if (srcW != this.srcWidth || srcH != this.srcHeight) {
            this.srcWidth = srcW;
            this.srcHeight = srcH;
            if (intermediateSurfaceTexture != null) {
                intermediateSurfaceTexture.setDefaultBufferSize(srcW, srcH);
            }
            AppLog.d(TAG, "Camera " + cameraId + " src size updated: " + srcW + "x" + srcH);
        }
    }

    // ===== 渲染 =====

    private void drawFrame() {
        if (!isInitialized || isReleased) return;
        if (intermediateSurfaceTexture == null) return;
        try {
            intermediateSurfaceTexture.updateTexImage();
            intermediateSurfaceTexture.getTransformMatrix(texMatrix);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);
            GLES20.glViewport(0, 0, outWidth, outHeight);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);
            GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0);
            GLES20.glUniform1i(textureHandle, 0);
            GLES20.glUniform1f(k1Handle, k1);
            GLES20.glUniform1f(k2Handle, k2);
            GLES20.glUniform1f(zoomHandle, zoom);
            GLES20.glUniform2f(centerHandle, centerX, centerY);
            GLES20.glUniform1f(rotationHandle, rotation);
            GLES20.glUniform2f(srcRatioHandle, (float) srcWidth, (float) srcHeight);
            GLES20.glUniform2f(dstRatioHandle, (float) outWidth, (float) outHeight);
            GLES20.glUniform1f(coverScaleHandle, coverScale);
            GLES20.glUniform2f(panHandle, panX, panY);

            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(texCoordHandle);
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(texCoordHandle);

            // PBO 异步读回：本帧发起读请求（立即返回），取上一帧的数据
            readbackWithPbo();

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " drawFrame error", e);
        }
    }

    /**
     * PBO 双缓冲读回：
     * - 本次绑定 pboIds[pboIndex] 发起 glReadPixels（异步，立即返回）
     * - 如果 pboReady=true，map 上一次的 pboIds[pboIndex ^ 1] 取回数据交给回调
     * - 翻转索引，下一帧再取
     */
    private void readbackWithPbo() {
        int cur = pboIndex;
        int prev = pboIndex ^ 1;

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[cur]);
        GLES30.glReadPixels(0, 0, outWidth, outHeight,
                GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0);

        if (pboReady) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[prev]);
            ByteBuffer buf = (ByteBuffer) GLES30.glMapBufferRange(
                    GLES30.GL_PIXEL_PACK_BUFFER, 0, outWidth * outHeight * 4,
                    GLES30.GL_MAP_READ_BIT);
            if (buf != null && frameCallback != null) {
                // 拷贝一份给回调（map 出来的 buffer 在 unmap 后失效）
                ByteBuffer copy = ByteBuffer.allocateDirect(buf.remaining())
                        .order(ByteOrder.nativeOrder());
                copy.put(buf);
                copy.flip();
                frameCallback.onFrame(copy, outWidth, outHeight);
            }
            GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER);
        }

        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
        pboIndex = prev;
        pboReady = true;
    }

    // ===== EGL/GL 初始化 =====

    private void initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("no EGL display");
        int[] ver = new int[2];
        EGL14.eglInitialize(eglDisplay, ver, 0, ver, 1);

        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] num = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, num, 0)) {
            throw new RuntimeException("eglChooseConfig failed");
        }
        eglConfig = configs[0];

        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            // 回退到 GLES 2.0（PBO 可能不可用，但 glReadPixels 同步读回仍可工作）
            AppLog.w(TAG, "GLES 3.0 context failed, fallback to 2.0");
            int[] ctx2 = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
            eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctx2, 0);
        }
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext failed");

        // 用 1x1 pbuffer 让 context current（不需要 window surface）
        int[] pbufAttribs = { EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE };
        EGLSurface pbuf = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufAttribs, 0);
        if (!EGL14.eglMakeCurrent(eglDisplay, pbuf, pbuf, eglContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
        AppLog.d(TAG, "Camera " + cameraId + " EGL ready (pbuffer context)");
    }

    private void initGl() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (program == 0) throw new RuntimeException("shader program failed");

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix");
        textureHandle = GLES20.glGetUniformLocation(program, "sTexture");
        k1Handle = GLES20.glGetUniformLocation(program, "uK1");
        k2Handle = GLES20.glGetUniformLocation(program, "uK2");
        zoomHandle = GLES20.glGetUniformLocation(program, "uZoom");
        centerHandle = GLES20.glGetUniformLocation(program, "uCenter");
        rotationHandle = GLES20.glGetUniformLocation(program, "uRotation");
        srcRatioHandle = GLES20.glGetUniformLocation(program, "uSrcRatio");
        dstRatioHandle = GLES20.glGetUniformLocation(program, "uDstRatio");
        coverScaleHandle = GLES20.glGetUniformLocation(program, "uCoverScale");
        panHandle = GLES20.glGetUniformLocation(program, "uPan");

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        oesTextureId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        vertexBuffer = createFloatBuffer(VERTICES);
        texCoordBuffer = createFloatBuffer(TEX_COORDS);
    }

    private void initFbo() {
        // FBO + 一张目标分辨率的 2D 纹理作为颜色附件
        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        fboId = fbo[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId);

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        renderTextureId = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, renderTextureId);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                outWidth, outHeight, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, renderTextureId, 0);
        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("FBO incomplete: 0x" + Integer.toHexString(status));
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        AppLog.d(TAG, "Camera " + cameraId + " FBO ready " + outWidth + "x" + outHeight);
    }

    private void initPbo() {
        int size = outWidth * outHeight * 4;
        GLES30.glGenBuffers(2, pboIds, 0);
        for (int i = 0; i < 2; i++) {
            GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboIds[i]);
            GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, size, null, GLES30.GL_DYNAMIC_READ);
        }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0);
        pboIndex = 0;
        pboReady = false;
    }

    private void releaseFbo() {
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{fboId}, 0);
            fboId = 0;
        }
        if (renderTextureId != 0) {
            GLES20.glDeleteTextures(1, new int[]{renderTextureId}, 0);
            renderTextureId = 0;
        }
    }

    private void releasePbo() {
        if (pboIds[0] != 0 || pboIds[1] != 0) {
            GLES30.glDeleteBuffers(2, pboIds, 0);
            pboIds[0] = pboIds[1] = 0;
        }
        pboReady = false;
    }

    // ===== 生命周期 =====

    /** 启动渲染线程。在 init() 前调用。 */
    public void startRenderThread() {
        if (renderThread != null) return;
        renderThread = new HandlerThread("StreamGlEncoder-" + cameraId, Process.THREAD_PRIORITY_DISPLAY);
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());
    }

    public Handler getRenderHandler() {
        return renderHandler;
    }

    /** 在 renderHandler 上执行任务。 */
    public void postOnRenderThread(Runnable r) {
        if (renderHandler != null) renderHandler.post(r);
        else r.run();
    }

    public void release() {
        if (isReleased) return;
        isReleased = true;
        isInitialized = false;
        AppLog.d(TAG, "Camera " + cameraId + " releasing StreamGlEncoder");

        final Runnable teardown = () -> {
            if (intermediateSurfaceTexture != null) {
                intermediateSurfaceTexture.setOnFrameAvailableListener(null);
                intermediateSurfaceTexture.release();
                intermediateSurfaceTexture = null;
            }
            if (intermediateSurface != null) {
                intermediateSurface.release();
                intermediateSurface = null;
            }
            releasePbo();
            releaseFbo();
            if (program != 0) {
                GLES20.glDeleteProgram(program);
                program = 0;
            }
            if (oesTextureId != 0) {
                GLES20.glDeleteTextures(1, new int[]{oesTextureId}, 0);
                oesTextureId = 0;
            }
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(eglDisplay, eglContext);
                    eglContext = EGL14.EGL_NO_CONTEXT;
                }
                EGL14.eglTerminate(eglDisplay);
                eglDisplay = EGL14.EGL_NO_DISPLAY;
            }
        };

        if (renderHandler != null) {
            renderHandler.post(teardown);
        } else {
            teardown.run();
        }
    }

    /** 释放渲染线程，必须在 release 之后调用。 */
    public void shutdownRenderThread() {
        if (renderThread != null) {
            renderThread.quitSafely();
            try { renderThread.join(); } catch (InterruptedException ignored) {}
            renderThread = null;
            renderHandler = null;
        }
    }

    // ===== 工具 =====

    private int createProgram(String vs, String fs) {
        int v = loadShader(GLES20.GL_VERTEX_SHADER, vs);
        int f = loadShader(GLES20.GL_FRAGMENT_SHADER, fs);
        if (v == 0 || f == 0) return 0;
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] link = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, link, 0);
        if (link[0] != GLES20.GL_TRUE) {
            AppLog.e(TAG, "link failed: " + GLES20.glGetProgramInfoLog(p));
            GLES20.glDeleteProgram(p);
            return 0;
        }
        GLES20.glDeleteShader(v);
        GLES20.glDeleteShader(f);
        return p;
    }

    private int loadShader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        if (s == 0) return 0;
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            AppLog.e(TAG, "compile failed: " + GLES20.glGetShaderInfoLog(s));
            GLES20.glDeleteShader(s);
            return 0;
        }
        return s;
    }

    private FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4).order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
