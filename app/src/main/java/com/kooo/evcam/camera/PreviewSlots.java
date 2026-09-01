package com.kooo.evcam.camera;

/**
 * 「这套配置里有哪几路画面」这一条规则。
 *
 * <h3>为什么值得单独拎出来</h3>
 *
 * <p>四个槽位按顺序是 front(1) / back(2) / left(3) / right(4)。上游只有 1、2、4 路
 * 的布局，左右天然成对出现，于是好几处代码把 left 和 right 一起卡在
 * {@code >= 4} —— 在 1/2/4 路下这么写<b>看不出错</b>。</p>
 *
 * <p>「环视+座舱3路」是第一个 3 路配置，它把 left 当作第三个槽位用，同一处笔误
 * 已经咬过两次：一次是 {@code texture_left} 拿不到监听器，就绪计数永远差一个，
 * 相机根本没被打开（<b>三路全黑</b>）；一次是录制集合里没有 left，
 * 预览有画面而录出来的文件少一路。</p>
 *
 * <p>所以这条规则只留一份，并且钉上测试。</p>
 */
public final class PreviewSlots {

    /** 槽位顺序。下标 + 1 就是它的槽位号。 */
    public static final String[] KEYS = {"front", "back", "left", "right"};

    /** 布局最多就这四路。 */
    public static final int MAX_SLOTS = 4;

    private PreviewSlots() {
    }

    /**
     * 某一路的槽位号（从 1 起）。
     *
     * @return 不认识的名字返回 0
     */
    public static int slotOf(String cameraKey) {
        for (int i = 0; i < KEYS.length; i++) {
            if (KEYS[i].equals(cameraKey)) {
                return i + 1;
            }
        }
        return 0;
    }

    /**
     * 这套配置里有没有这一路。
     *
     * <p>判据是<b>槽位号</b>，不是「左右成对」。</p>
     */
    public static boolean exists(int cameraCount, String cameraKey) {
        int slot = slotOf(cameraKey);
        return slot > 0 && slot <= cameraCount;
    }

    /**
     * 要等几路 TextureView 就绪，才能去开相机。
     *
     * <p>就是这套布局<b>实际会挂监听的路数</b> —— 不是「要录几路」。
     * 这两个数曾经被混用：自定义模式下按录制开关去算，
     * 默认四路全开，而布局只给了一两个 TextureView，
     * 那道闸门于是永远不成立，相机一次都没开过。</p>
     */
    public static int requiredTextures(int cameraCount) {
        if (cameraCount < 1) {
            return 1;
        }
        return Math.min(cameraCount, MAX_SLOTS);
    }

    /** 就绪的路数够不够开相机。 */
    public static boolean canStartCamera(int readyCount, int cameraCount) {
        return readyCount >= requiredTextures(cameraCount);
    }
}
