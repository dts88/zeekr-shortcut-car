package com.kooo.evcam.zeekr;

import android.graphics.Canvas;
import android.util.Size;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;

/**
 * 盲区画面的画面本身：分格反投影（{@link FisheyeMesh}，虚拟相机先转过角度 {@link SideViewFraming}），和「不显示过时的画面」那条规矩。
 * 中控屏的窗口（{@link SideViewWindow}）和 HUD 的窗口（{@link SideViewHudWindow}）共用这一份，各自只管窗口。
 *
 * <h3>不显示过时的画面</h3>
 *
 * <p>画布设回可见那一下，TextureView 先取一次缓冲区里留着的那一帧 —— 相机要是停着，那一帧可能是很久以前的。
 * 所以每次显示先盖成黑的，显示之后来了第二帧才露出来；显示着时 {@link #STALE_MS} 没有新帧也盖上。
 * 盯着一张过时的侧方画面变道，比看见一块黑的危险得多（同超级镜子画面停住时盖上的道理）。HUD 上黑就是不亮，盖上就是什么都不显示。</p>
 *
 * <p>只在主线程上用。</p>
 */
final class SideViewPicture {

    private static final String TAG = "SideViewPicture";

    /** 显示着时多久没有新帧就盖上：最省的档位也有 10 fps，半秒是五帧没来。 */
    static final long STALE_MS = 500L;

    private final AppConfig appConfig;
    private final FisheyeMesh mesh = new FisheyeMesh();
    private final SideViewFraming framing = new SideViewFraming();
    private final FisheyeMesh.SourceMap turnedMap = framing::sourcePoint;
    /** 合成流的真实尺寸算出来的拆分几何；不知道时画面是黑的。 */
    private CompositeStreamGeometry.Plan plan;
    /** 这一次显示以来来了几帧：第一帧可能是画布设回可见时取的旧帧。 */
    private int framesSinceShown;
    private long lastFrameUptimeMs;
    private boolean stale;

    SideViewPicture(AppConfig appConfig) {
        this.appConfig = appConfig;
    }

    /**
     * 合成流的真实尺寸；不像合成流的忽略（同 {@link RearViewMirrorView#setSourceSize}）。
     *
     * @return true：几何变了，要重画
     */
    boolean setSourceSize(Size size) {
        if (size == null || size.getWidth() <= 0 || size.getHeight() <= 0) {
            return false;
        }
        String id = StreamLayoutTable.compositeCameraId();
        if (!CompositeStreamGeometry.looksLikeComposite(id, size.getWidth(), size.getHeight())) {
            AppLog.d(TAG, "忽略非合成流尺寸 " + size);
            return false;
        }
        plan = CompositeStreamGeometry.analyse(id, size.getWidth(), size.getHeight());
        return true;
    }

    boolean hasGeometry() {
        return plan != null && plan.isComposite();
    }

    /** 新的一次显示：先盖着，等真正的新帧（显示着换一侧不用：画布一直可见，帧一直是新的）。 */
    void beginShowing(long nowUptimeMs) {
        framesSinceShown = 0;
        stale = false;
        lastFrameUptimeMs = nowUptimeMs;
    }

    /**
     * 来了一帧（服务每收到一帧调一次）。
     *
     * @return true：盖不盖变了，要重画
     */
    boolean noteFrame(long nowUptimeMs, boolean shown) {
        lastFrameUptimeMs = nowUptimeMs;
        if (!shown) {
            return false;
        }
        boolean wasCovered = covered();
        framesSinceShown++;
        stale = false;
        return wasCovered != covered();
    }

    /**
     * 显示着时隔一会儿看一次新帧停没停。
     *
     * @return true：刚停住或刚恢复，要重画
     */
    boolean checkStale(long nowUptimeMs) {
        boolean nowStale = nowUptimeMs - lastFrameUptimeMs > STALE_MS;
        if (nowStale == stale) {
            return false;
        }
        stale = nowStale;
        return true;
    }

    boolean isStale() {
        return stale;
    }

    /** 盖着：这一次显示之后还没来真正的新帧，或者新帧停了。 */
    boolean covered() {
        return framesSinceShown < 2 || stale;
    }

    /** 此刻能画出画面：没盖着、几何已知。 */
    boolean live() {
        return !covered() && hasGeometry();
    }

    /**
     * 画显示着的画面。盖着、几何不知道时照样画一次子视图（不画它就不取帧，新帧也就不来），再整个盖黑。
     * 角度在容器重画时照设置读（内存里的值；开发者选项锁着时是默认值）；没变的时候 SideViewFraming 不重算。
     */
    void draw(Canvas canvas, int width, int height, int lane, FisheyeMesh.Painter painter) {
        if (!live()) {
            painter.paint(canvas);
            canvas.drawColor(0xFF000000);
            return;
        }
        framing.configure(appConfig.getSideViewFov(), appConfig.getSideViewBack(), appConfig.getSideViewUp(),
                lane, width / (float) height);
        RearViewGeometry.ShaderRects r = RearViewGeometry.toShaderRects(plan, lane, RearViewGeometry.Viewport.full());
        mesh.prepare(FisheyeProjection.MESH_DIVISIONS,
                r.laneOffsetX * width, r.laneOffsetY * height,
                r.laneScaleX * width, r.laneScaleY * height, turnedMap);
        mesh.draw(canvas, 0f, 0f, width, height, painter);
    }

    /** 此刻实际用的横向视野（装不下时自动收窄过）。 */
    float effectiveFovDegrees() {
        return framing.effectiveFovDegrees();
    }
}
