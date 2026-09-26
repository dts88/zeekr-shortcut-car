package com.kooo.evcam.zeekr;

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
import android.os.SystemClock;
import android.view.Surface;

import com.kooo.evcam.AppLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 在 GPU 上逐像素做鱼眼校正的一段管线（开发者选项）。
 *
 * <pre>
 *   生产者（相机 / 解码器）──写入──&gt; 这里的 SurfaceTexture（GL 自建）
 *                                         │ 着色器：每个输出像素算一次反投影
 *                                         ▼
 *                               屏幕上那个 TextureView 的 SurfaceTexture
 * </pre>
 *
 * <h3>为什么要有它</h3>
 *
 * <p>分格近似（{@link FisheyeMesh}）在直线投影上会让直线变成一段段折线：
 * 140° 时四宫格里最大偏 4.8px。图片回看是逐像素算的，所以直。这条管线让预览和视频回看
 * 也逐像素算，用的是同一个公式（{@link FisheyeProjection} 三种投影照搬进着色器）。</p>
 *
 * <h3>输出和输入是同一种几何</h3>
 *
 * <p>每一路在自己原来的位置上被校正：合成流进来是 1280×5140 的竖条，出去还是竖条，
 * 只是四个格子里的画面变直了。所以后面的一切 —— 四宫格拆分、放大、角标、视频的取景 ——
 * 都不用知道有这一层。开关关着的时候原样拷过去，拨开关当场生效，不用重建管线。</p>
 *
 * <h3>生产者永远不会被堵住</h3>
 *
 * <p>屏幕上那一块没了（TextureView 销毁、EGL 出错），照样每帧 {@code updateTexImage}
 * 把画面取走，只是不画 —— 相机和解码器不能因为显示的事卡住。输出的 SurfaceTexture
 * 被释放之后，管线自己收掉（每 2 秒看一次）。</p>
 *
 * <p>平台笔记 §2.1 记着「GL 自建 SurfaceTexture 当相机输出会崩」，那条已经更正过；
 * 这条管线就是在车上验证它的那一步。起不来时 {@link #start} 返回 null，调用方照旧直连。</p>
 */
public final class FisheyeGlPipe {

    private static final String TAG = "FisheyeGlPipe";
    private static final long START_TIMEOUT_MS = 1500;
    private static final long RELEASE_TIMEOUT_MS = 1000;
    private static final long WATCH_INTERVAL_MS = 2000;

    /** 录好的环视视频：2×2，左上、右上、左下、右下。 */
    public static final float[] GRID_2X2 = {
            0f, 0f, 0.5f, 0.5f,
            0.5f, 0f, 0.5f, 0.5f,
            0f, 0.5f, 0.5f, 0.5f,
            0.5f, 0.5f, 0.5f, 0.5f,
    };
    private static final int MAX_LANES = 4;

    private static final String VERTEX_SHADER =
            "attribute vec2 aPosition;\n" +
            "varying vec2 vPos;\n" +
            "void main() {\n" +
            "    gl_Position = vec4(aPosition, 0.0, 1.0);\n" +
            // 输出画面里的位置，左上为原点、0..1 —— 和 FisheyeProjection 的约定一致
            "    vPos = vec2((aPosition.x + 1.0) * 0.5, (1.0 - aPosition.y) * 0.5);\n" +
            "}\n";

    /**
     * 三种投影逐行照搬 {@link FisheyeProjection} 的 sourcePoint，强度也是同一种插值。
     * {@code FisheyeGlPipeTest} 里有一份 Java 版的逐行对照，和 sourcePoint 比过。
     */
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision highp float;\n" +
            "varying vec2 vPos;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "uniform vec4 uLanes[4];\n" +
            "uniform float uLaneCount;\n" +
            "uniform float uOn;\n" +
            "uniform float uProjection;\n" +
            "uniform float uParam;\n" +
            "uniform float uStrength;\n" +
            "const float HALF_PI = 1.5707963268;\n" +
            "vec2 corrected(vec2 p) {\n" +
            "    if (uProjection < 0.5) {\n" +
            "        vec2 plane = (p * 2.0 - 1.0) * uParam;\n" +
            "        float r = length(plane);\n" +
            "        if (r < 0.00001) return vec2(0.5);\n" +
            "        float sr = atan(r) / HALF_PI;\n" +
            "        return clamp(0.5 + plane / r * sr * 0.5, 0.0, 1.0);\n" +
            "    } else if (uProjection < 1.5) {\n" +
            "        float az = (p.x * 2.0 - 1.0) * uParam;\n" +
            "        float h = (p.y * 2.0 - 1.0) * uParam;\n" +
            "        float s = sin(az);\n" +
            "        float cosAngle = clamp(cos(az) / sqrt(1.0 + h * h), -1.0, 1.0);\n" +
            "        float sr = acos(cosAngle) / HALF_PI;\n" +
            "        float planar = sqrt(s * s + h * h);\n" +
            "        vec2 dir = planar > 0.00001 ? vec2(s, h) / planar : vec2(0.0);\n" +
            "        return clamp(0.5 + dir * sr * 0.5, 0.0, 1.0);\n" +
            "    } else {\n" +
            "        vec2 d = (p * 2.0 - 1.0) * uParam;\n" +
            "        float r = length(d);\n" +
            "        if (r < 0.00001) return vec2(0.5);\n" +
            "        float sr = 2.0 * atan(r) / HALF_PI;\n" +
            "        return clamp(0.5 + d / r * sr * 0.5, 0.0, 1.0);\n" +
            "    }\n" +
            "}\n" +
            "void main() {\n" +
            "    vec2 src = vPos;\n" +
            "    if (uOn > 0.5) {\n" +
            "        for (int i = 0; i < 4; i++) {\n" +
            "            if (float(i) >= uLaneCount) break;\n" +
            "            vec4 lane = uLanes[i];\n" +
            "            vec2 local = (vPos - lane.xy) / lane.zw;\n" +
            "            if (local.x >= 0.0 && local.x <= 1.0 && local.y >= 0.0 && local.y <= 1.0) {\n" +
            "                vec2 s = corrected(local);\n" +
            "                s = local + (s - local) * uStrength;\n" +
            "                src = lane.xy + s * lane.zw;\n" +
            "                break;\n" +
            "            }\n" +
            "        }\n" +
            "    }\n" +
            // SurfaceTexture 的矩阵按左下为原点的纹理坐标算
            "    vec4 t = uTexMatrix * vec4(src.x, 1.0 - src.y, 0.0, 1.0);\n" +
            "    gl_FragColor = texture2D(sTexture, t.xy);\n" +
            "}\n";

    private static final float[] QUAD = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};

    /** 一次设置的快照：GL 线程每帧读一次，别的线程整个换掉，不会读到一半新一半旧。 */
    private static final class Correction {
        final boolean on;
        final float projection;
        final float parameter;
        final float strength;

        Correction(boolean on, float projection, float parameter, float strength) {
            this.on = on;
            this.projection = projection;
            this.parameter = parameter;
            this.strength = strength;
        }
    }

    private final String name;
    private final SurfaceTexture output;
    private final int inputWidth;
    private final int inputHeight;
    private final float[] lanes;
    private final int laneCount;
    private final HandlerThread thread;
    private final Handler handler;

    private volatile Correction correction = new Correction(false, 0f, 1f, 1f);
    private volatile boolean released;
    private volatile boolean outputBroken;
    private volatile Runnable onReleased;

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private int program;
    private int texture;
    private int positionHandle;
    private int texMatrixHandle;
    private int lanesHandle;
    private int laneCountHandle;
    private int onHandle;
    private int projectionHandle;
    private int parameterHandle;
    private int strengthHandle;
    private SurfaceTexture input;
    private final float[] texMatrix = new float[16];
    private final int[] size = new int[2];
    private FloatBuffer quad;
    private long frames;
    private long startedAtMs;

    private FisheyeGlPipe(String name, SurfaceTexture output, int inputWidth, int inputHeight,
                          float[] lanes) {
        this.name = name;
        this.output = output;
        this.inputWidth = inputWidth;
        this.inputHeight = inputHeight;
        int count = Math.min(MAX_LANES, lanes == null ? 0 : lanes.length / 4);
        this.laneCount = count;
        this.lanes = Arrays.copyOf(lanes == null ? new float[0] : lanes, MAX_LANES * 4);
        this.thread = new HandlerThread("FisheyeGl-" + name);
        this.thread.start();
        this.handler = new Handler(thread.getLooper());
    }

    /**
     * 起一条管线，等它在自己的线程上准备好。
     *
     * @param output      屏幕上那一块的 SurfaceTexture（画到这里）
     * @param inputWidth  生产者的画面尺寸：相机按这个尺寸出流
     * @param lanes       每一路在画面里的位置（x, y, 宽, 高，归一化，左上为原点），最多 4 路
     * @return 起不来返回 null，调用方照旧直连
     */
    public static FisheyeGlPipe start(String name, SurfaceTexture output,
                                      int inputWidth, int inputHeight, float[] lanes) {
        if (output == null || inputWidth <= 0 || inputHeight <= 0) {
            return null;
        }
        FisheyeGlPipe pipe = new FisheyeGlPipe(name, output, inputWidth, inputHeight, lanes);
        CountDownLatch ready = new CountDownLatch(1);
        boolean[] ok = new boolean[1];
        pipe.handler.post(() -> {
            try {
                pipe.init();
                ok[0] = true;
            } catch (RuntimeException e) {
                AppLog.e(TAG, name + " 起不来，照旧直连: " + e);
                pipe.teardown();
            } finally {
                ready.countDown();
            }
        });
        try {
            if (!ready.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                AppLog.e(TAG, name + " " + START_TIMEOUT_MS + "ms 内没准备好，照旧直连");
                pipe.release();
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            pipe.release();
            return null;
        }
        if (!ok[0]) {
            pipe.thread.quitSafely();
            return null;
        }
        return pipe;
    }

    /** 给生产者的 Surface。每次给一个新的，用完由生产者那边 release —— 和直连时一样。 */
    public Surface newInputSurface() {
        SurfaceTexture in = input;
        return in == null || released ? null : new Surface(in);
    }

    public boolean isAlive() {
        return !released && !outputBroken;
    }

    public SurfaceTexture output() {
        return output;
    }

    /** 管线自己收掉（输出没了）时叫它。 */
    public void setOnReleased(Runnable action) {
        onReleased = action;
    }

    /**
     * 校正开没开、用什么参数。任何线程都能调，下一帧生效。
     * 关着就原样拷过去 —— 生产者始终接在这条管线上，拨开关不用重建。
     */
    public void setCorrection(boolean on, float fovDegrees, String projection, float strength) {
        correction = new Correction(on,
                FisheyeProjection.shaderProjectionCode(projection),
                FisheyeProjection.shaderParameter(fovDegrees, projection),
                Math.max(0f, Math.min(1f, strength)));
        if (!released) {
            handler.post(this::drawLatest);
        }
    }

    /** 输出多大（像素）。视频知道自己的尺寸之后按它来，放大一格时才不糊。 */
    public void setOutputSize(int width, int height) {
        if (width <= 0 || height <= 0 || released) {
            return;
        }
        handler.post(() -> {
            if (!released && !output.isReleased()) {
                output.setDefaultBufferSize(width, height);
            }
        });
    }

    /** 收掉。任何线程都能调；在别的线程上调会等它收完（最多 1 秒），免得新管线接不上同一块输出。 */
    public void release() {
        if (released) {
            return;
        }
        released = true;
        if (Looper.myLooper() == thread.getLooper()) {
            teardown();
            thread.quitSafely();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        handler.post(() -> {
            teardown();
            done.countDown();
        });
        thread.quitSafely();
        try {
            done.await(RELEASE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ------------------------------------------------------------------ GL 线程

    private void init() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw new IllegalStateException("no EGL display");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw new IllegalStateException("eglInitialize failed");
        }
        int[] attributes = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] < 1) {
            throw new IllegalStateException("no suitable EGL config");
        }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) {
            throw new IllegalStateException("eglCreateContext failed 0x"
                    + Integer.toHexString(EGL14.eglGetError()));
        }
        // 屏幕上那一块已经被别的生产者接着的话，这一步会失败 —— 那就照旧直连
        surface = EGL14.eglCreateWindowSurface(display, configs[0], output,
                new int[]{EGL14.EGL_NONE}, 0);
        if (surface == EGL14.EGL_NO_SURFACE) {
            throw new IllegalStateException("eglCreateWindowSurface failed 0x"
                    + Integer.toHexString(EGL14.eglGetError()));
        }
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw new IllegalStateException("eglMakeCurrent failed");
        }

        program = link(VERTEX_SHADER, FRAGMENT_SHADER);
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix");
        lanesHandle = GLES20.glGetUniformLocation(program, "uLanes");
        laneCountHandle = GLES20.glGetUniformLocation(program, "uLaneCount");
        onHandle = GLES20.glGetUniformLocation(program, "uOn");
        projectionHandle = GLES20.glGetUniformLocation(program, "uProjection");
        parameterHandle = GLES20.glGetUniformLocation(program, "uParam");
        strengthHandle = GLES20.glGetUniformLocation(program, "uStrength");
        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        quad.put(QUAD).position(0);

        int[] names = new int[1];
        GLES20.glGenTextures(1, names, 0);
        texture = names[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
                GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        input = new SurfaceTexture(texture);
        // 相机按这个尺寸出流：和直连时设在 TextureView 上的那个一样
        input.setDefaultBufferSize(inputWidth, inputHeight);
        input.setOnFrameAvailableListener(st -> drawFrame(), handler);
        startedAtMs = SystemClock.elapsedRealtime();
        handler.postDelayed(this::watchOutput, WATCH_INTERVAL_MS);
        AppLog.i(TAG, name + " 准备好：输入 " + inputWidth + "x" + inputHeight
                + "，" + laneCount + " 路，GL " + version[0] + "." + version[1]);
    }

    private void drawFrame() {
        if (released || input == null) {
            return;
        }
        try {
            // 不管画不画，这一帧都要取走 —— 不然生产者会被堵住
            input.updateTexImage();
            input.getTransformMatrix(texMatrix);
        } catch (RuntimeException e) {
            AppLog.w(TAG, name + " 取帧失败: " + e);
            return;
        }
        frames++;
        if (frames == 1) {
            AppLog.i(TAG, name + " 第一帧，距准备好 "
                    + (SystemClock.elapsedRealtime() - startedAtMs) + "ms");
        }
        drawLatest();
    }

    /** 把最近取到的那一帧画出去。设置变了（比如暂停中拨了开关）也叫它，不用等下一帧。 */
    private void drawLatest() {
        if (released || outputBroken || frames == 0 || surface == EGL14.EGL_NO_SURFACE) {
            return;
        }
        if (output.isReleased()) {
            outputBroken = true;
            AppLog.i(TAG, name + " 屏幕上那一块已经释放，只取帧不画");
            return;
        }
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, size, 0);
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, size, 1);
        GLES20.glViewport(0, 0, size[0], size[1]);

        Correction c = correction;
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture);
        GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0);
        GLES20.glUniform4fv(lanesHandle, MAX_LANES, lanes, 0);
        GLES20.glUniform1f(laneCountHandle, laneCount);
        GLES20.glUniform1f(onHandle, c.on ? 1f : 0f);
        GLES20.glUniform1f(projectionHandle, c.projection);
        GLES20.glUniform1f(parameterHandle, c.parameter);
        GLES20.glUniform1f(strengthHandle, c.strength);
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, quad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glDisableVertexAttribArray(positionHandle);

        if (!EGL14.eglSwapBuffers(display, surface)) {
            outputBroken = true;
            AppLog.w(TAG, name + " 画不上屏幕了 0x" + Integer.toHexString(EGL14.eglGetError())
                    + "，之后只取帧不画");
        }
    }

    /** 屏幕上那一块释放了就收掉自己：没有人会再来要这条管线。 */
    private void watchOutput() {
        if (released) {
            return;
        }
        if (output.isReleased()) {
            AppLog.i(TAG, name + " 屏幕上那一块没了，收掉（共 " + frames + " 帧）");
            Runnable action = onReleased;
            release();
            if (action != null) {
                action.run();
            }
            return;
        }
        handler.postDelayed(this::watchOutput, WATCH_INTERVAL_MS);
    }

    /** 在 GL 线程上收拾。只拆自己建的东西，不 eglTerminate：同一进程里还有别人在用 EGL。 */
    private void teardown() {
        released = true;
        handler.removeCallbacksAndMessages(null);
        try {
            if (input != null) {
                input.setOnFrameAvailableListener(null);
                input.release();
                input = null;
            }
            if (display != EGL14.EGL_NO_DISPLAY) {
                if (program != 0) {
                    GLES20.glDeleteProgram(program);
                    program = 0;
                }
                if (texture != 0) {
                    GLES20.glDeleteTextures(1, new int[]{texture}, 0);
                    texture = 0;
                }
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_CONTEXT);
                if (surface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, surface);
                    surface = EGL14.EGL_NO_SURFACE;
                }
                if (context != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, context);
                    context = EGL14.EGL_NO_CONTEXT;
                }
                EGL14.eglReleaseThread();
            }
        } catch (RuntimeException e) {
            AppLog.w(TAG, name + " 收拾时出错: " + e);
        }
        AppLog.i(TAG, name + " 已收掉，共画 " + frames + " 帧");
    }

    private static int link(String vertexSource, String fragmentSource) {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, vertexSource);
        int fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] status = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, status, 0);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        if (status[0] != GLES20.GL_TRUE) {
            String log = GLES20.glGetProgramInfoLog(program);
            GLES20.glDeleteProgram(program);
            throw new IllegalStateException("shader link failed: " + log);
        }
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] status = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
        if (status[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("shader compile failed: " + log);
        }
        return shader;
    }
}
