package com.kooo.evcam.camera;

import java.util.Set;

/**
 * 主界面摆什么布局、挂哪几块画布：只看配置里启用了哪几路（{@code RecordSpecs.enabledCameraKeys}），一路一块画布。
 *
 * <h3>为什么值得单独拎出来</h3>
 *
 * <p>以前按「开了几路」配槽位号：1 路就是只有环视的四宫格，2 路是环视 + 前座舱，3 路才有后座舱。
 * 只开一路座舱时摆的是环视的四宫格、建的也只有环视，那一路座舱打不开；只开两路座舱时，环视没开也被打开来预览，
 * 后座舱却没有画布（2026-10-16）。现在不数路数，只问每一路开没开。</p>
 *
 * <p>更早还有一处同类笔误：三路时 {@code texture_left} 拿不到监听器，就绪计数永远差一个，相机没被打开（三路全黑）。
 * 所以规则只留一份，并且钉上测试。</p>
 */
public final class PreviewSlots {

    /** 主界面上的几格画布，按布局里的顺序（不是开的次序）。 */
    public static final String[] KEYS = {"front", "back", "left", "right"};

    private PreviewSlots() {
    }

    /**
     * 用只有环视的单路布局（一路合成流，由四宫格容器拆成四格）：配置里只启用了环视。
     * 其余（启用了座舱、或者一路都没启用）用多路布局，没启用的那几格藏起来。
     */
    public static boolean singleLayout(Set<String> enabled) {
        return enabled.size() == 1 && enabled.contains(CameraSlots.KEY_SURROUND);
    }

    /**
     * 画布都就绪了，可以接上管线、登记预览：启用的每一路都有一块就绪的画布。
     *
     * <p>要等的就是这套布局<b>实际挂了监听</b>的那几块 —— 启用的那几路，一路一块。</p>
     */
    public static boolean canStartCamera(int readyCount, Set<String> enabled) {
        return readyCount >= enabled.size();
    }
}
