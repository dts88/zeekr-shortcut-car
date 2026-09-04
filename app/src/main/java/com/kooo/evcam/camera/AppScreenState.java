package com.kooo.evcam.camera;

/**
 * 应用当前停在哪个界面。
 *
 * <p>只为一件事存在：{@link PreviewSampler} 在后台每秒采一次样，它得知道
 * 那一秒用户在哪儿 —— 否则采回来一串帧率，分不清哪一段是「预览界面」、
 * 哪一段是「设置界面」、哪一段是「退到车机桌面」。</p>
 *
 * <p>做成静态的，是因为采样跑在自己的线程上，和 Activity 之间没有现成的联系。</p>
 */
public final class AppScreenState {

    /** 主界面，预览画面就在眼前。 */
    public static final String PREVIEW = "预览界面";

    /** 应用还在前台，但盖着别的界面（设置、回看…）。 */
    public static final String OTHER_SCREEN = "应用内其他界面";

    /** 应用退到后台（回车机桌面、切到别的应用）。 */
    public static final String BACKGROUND = "退到后台";

    private static volatile String current = BACKGROUND;

    private AppScreenState() {
    }

    public static void set(String state) {
        current = state == null ? BACKGROUND : state;
    }

    public static String current() {
        return current;
    }
}
