/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * Portions Copyright (C) 2026 lelelolo - https://github.com/lelelolo
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.zeekr;

import android.util.Size;
import android.view.TextureView;

/**
 * 盲区画面一个出口的窗口：中控屏上的 {@link SideViewWindow}（一个窗口一个画面，左右两块各一个）、
 * HUD 上的 {@link SideViewHudWindow}（一个窗口里左右两个画面，项目所有者 2026-11-04）。
 * 服务（{@link SideViewService}）对两者一视同仁：挂上（备着 / 显示）、按侧显示和收起、移走，画布交给环视声明。
 *
 * <p>三种状态两者一样：没挂；备着（窗口挂着、看不见，画面留在环视的会话里）；显示（至少一侧）。</p>
 */
interface SideViewOutputWindow {

    /** 相机要写进的 TextureView：服务把它的 SurfaceTexture 声明成环视的输出，会话由通道调度建。 */
    TextureView getTextureView();

    /** 合成流的真实尺寸；不像合成流的忽略。 */
    void setSourceSize(Size size);

    boolean hasGeometry();

    boolean isAttached();

    /** 至少一侧显示着。 */
    boolean isShowing();

    /** {@code side} 那一侧显示着。 */
    boolean isShowing(int side);

    /** 挂上但不显示（备着）；已经挂着就只是藏起来。{@code side}：中控屏的窗口停在哪一侧（HUD 用不到）。 */
    void attachHidden(int side);

    /**
     * 显示 {@code side} 那一侧：没挂就挂上，备着就显示出来。中控屏的窗口一次一个画面，显示着另一侧就换过去；
     * HUD 的窗口两侧可以同时显示。淡出中的回到正常。
     */
    void show(int side);

    /** 收起 {@code side} 那一侧（没在显示它就什么都不做）；一侧都不显示了，窗口留着、画面留在会话里（备着）。 */
    void conceal(int side);

    /**
     * 淡出（项目所有者 2026-11-04，{@link SideViewFade}）：显示着的 {@code side} 那一侧在 {@code durationMs} 内从正常淡到看不见；
     * 窗口照旧挂着、显示着，淡完由服务收起。已经在淡就不重来。再显示（{@link #show}）回到正常，收起、移走时停下。
     */
    void fadeOut(int side, long durationMs);

    /** {@code side} 那一侧在淡出（{@link #fadeOut}）。 */
    boolean isFading(int side);

    /** 当场移走：画布在这一下被收走，服务在 onSurfaceTextureDestroyed 里把它交给管线留着。只从服务调。 */
    void detach();

    /** 画布可见不可见：显示着、或者还没建好时可见，备着时不可见、不逐帧画。 */
    void syncCanvas();

    /** 服务每收到一帧（onSurfaceTextureUpdated）调一次。 */
    void noteFrame();

    /** 改了标定角度：重画一次。 */
    void applyAim();

    /**
     * {@code side} 那一侧画面的边框（{@link SideViewAlert}：后视镜上的灯亮着时描边）：服务每次判定都给一次，变了才重画；
     * 只在那一侧显示着时画出来。
     */
    void setAlert(int side, int level);

    /** 黑匣子、卡顿报告里的一行：窗口此刻的样子（记号，不是给人读的句子）。 */
    String describe();
}
