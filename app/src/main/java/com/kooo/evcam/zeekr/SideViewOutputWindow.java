package com.kooo.evcam.zeekr;

import android.util.Size;
import android.view.TextureView;

/**
 * 盲区画面一个出口的窗口：中控屏上的 {@link SideViewWindow}、HUD 上的 {@link SideViewHudWindow}。
 * 服务（{@link SideViewService}）对两者一视同仁：挂上（备着 / 显示）、收起、移走，画布交给环视声明。
 *
 * <p>三种状态两者一样：没挂；备着（窗口挂着、看不见，画面留在环视的会话里）；显示。</p>
 */
interface SideViewOutputWindow {

    /** 相机要写进的 TextureView：服务把它的 SurfaceTexture 声明成环视的输出，会话由通道调度建。 */
    TextureView getTextureView();

    /** 合成流的真实尺寸；不像合成流的忽略。 */
    void setSourceSize(Size size);

    boolean hasGeometry();

    boolean isAttached();

    boolean isShowing();

    /** 显示（或上一次显示）的是哪一侧。 */
    int lane();

    /** 挂上但不显示（备着）；已经挂着就只是藏起来。 */
    void attachHidden(int side);

    /** 显示 {@code side} 那一侧：没挂就挂上，备着就显示出来，显示着另一侧就换过去。 */
    void show(int side);

    /** 藏起来，窗口留着、画面留在会话里（备着）。 */
    void conceal();

    /** 当场移走：画布在这一下被收走，服务在 onSurfaceTextureDestroyed 里把它交给管线留着。只从服务调。 */
    void detach();

    /** 画布可见不可见：显示着、或者还没建好时可见，备着时不可见、不逐帧画。 */
    void syncCanvas();

    /** 服务每收到一帧（onSurfaceTextureUpdated）调一次。 */
    void noteFrame();

    /** 改了标定角度：重画一次。 */
    void applyAim();

    /** 黑匣子、卡顿报告里的一行：窗口此刻的样子（记号，不是给人读的句子）。 */
    String describe();
}
