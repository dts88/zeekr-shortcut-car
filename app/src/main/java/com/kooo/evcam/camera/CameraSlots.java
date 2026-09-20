package com.kooo.evcam.camera;

/**
 * 相机槽位的两套名字，以及它们的对应关系。<b>只有这一个地方知道两套名字都存在。</b>
 *
 * <h3>对外的名字（文件名、回放界面）</h3>
 *
 * <ul>
 *   <li>{@link #SURROUND} —— 环视合成流。<b>它本身就是一个 2×2 的画面</b>，
 *       前后左右四路装在同一张图 / 同一个视频文件里；</li>
 *   <li>{@link #CABIN_FRONT} —— 前座舱；</li>
 *   <li>{@link #CABIN_REAR} —— 后座舱。</li>
 * </ul>
 *
 * <p>都是单个词，不含下划线 —— 文件名形如 {@code 20250101_120000_surround.mp4}，
 * 解析时取的是<b>最后一个下划线之后</b>的部分，名字里再有下划线就会被切错。</p>
 *
 * <h3>代码里的 key 是历史遗留的方位名</h3>
 *
 * <p>{@code "front"} / {@code "back"} / {@code "left"} 这三个 key 在代码里到处都是，
 * 而且<b>存进了车型配置和偏好设置</b>，改动要配数据迁移，波及补盲、预览槽位一整片。
 * 所以它们留着，由这里翻译成对外的名字。</p>
 *
 * <p><b>要紧的是别被它们的字面意思骗了：</b>{@code "front"} 指的不是「前方那一路」，
 * 而是<b>整张环视合成流</b>；{@code "back"} 是前座舱，{@code "left"} 是后座舱。
 * 这三个名字来自最早只有四路独立相机时的设想，极氪 7X 上根本不是那么回事。
 * 环视里真正的前后左右是那张 2×2 里的四格，和这三个 key 没有关系。</p>
 *
 * <h3>旧文件一样认</h3>
 *
 * <p>改名之前录下来的文件全是 {@code _front} / {@code _back} / {@code _left}。
 * <b>写用新名，读两种都认</b> —— 否则 U 盘上已有的素材会从回放里凭空消失。
 * {@link #canonical} 就是那道归一化。</p>
 */
public final class CameraSlots {

    // ---- 对外：文件名和回放界面用这三个 ----

    /** 环视合成流（一张图里 2×2 四格）。 */
    public static final String SURROUND = "surround";
    /** 前座舱。 */
    public static final String CABIN_FRONT = "cabinfront";
    /** 后座舱。 */
    public static final String CABIN_REAR = "cabinrear";

    // ---- 代码内部：历史遗留的方位名，见类说明 ----

    /** 环视那一路的内部 key。<b>不是「前方」。</b> */
    public static final String KEY_SURROUND = "front";
    /** 前座舱的内部 key。<b>不是「后方」。</b> */
    public static final String KEY_CABIN_FRONT = "back";
    /** 后座舱的内部 key。<b>不是「左侧」。</b> */
    public static final String KEY_CABIN_REAR = "left";
    /** 第四路。极氪 7X 上不存在，自定义车型那种四面各一个相机的接法才用得到。 */
    public static final String KEY_FOURTH = "right";

    private CameraSlots() {
    }

    /**
     * 内部 key → 写进文件名的后缀。
     *
     * <p>认不出来的 key（自定义车型那种）原样返回 —— 那种情况下名字归用户，
     * 我们不该替他重命名。</p>
     */
    public static String suffixFor(String cameraKey) {
        if (KEY_SURROUND.equals(cameraKey)) {
            return SURROUND;
        }
        if (KEY_CABIN_FRONT.equals(cameraKey)) {
            return CABIN_FRONT;
        }
        if (KEY_CABIN_REAR.equals(cameraKey)) {
            return CABIN_REAR;
        }
        return cameraKey;
    }

    /**
     * 文件名后缀 → 内部 key。新旧两种名字都认。
     *
     * <p>认不出来的原样返回。</p>
     */
    public static String keyForSuffix(String suffix) {
        if (SURROUND.equals(suffix) || KEY_SURROUND.equals(suffix)) {
            return KEY_SURROUND;
        }
        if (CABIN_FRONT.equals(suffix) || KEY_CABIN_FRONT.equals(suffix)) {
            return KEY_CABIN_FRONT;
        }
        if (CABIN_REAR.equals(suffix) || KEY_CABIN_REAR.equals(suffix)) {
            return KEY_CABIN_REAR;
        }
        return suffix;
    }

    /**
     * 把文件名后缀归一成对外的名字。旧文件的 {@code _front} 在这里变成 {@code surround}。
     *
     * <p>回放界面按归一之后的名字分桶，新旧文件于是落进同一个格子。</p>
     */
    public static String canonical(String suffix) {
        return suffixFor(keyForSuffix(suffix));
    }

    /** 是不是改名之前那一套写法。只用来在日志和报告里说明「这是旧文件」。 */
    public static boolean isLegacySuffix(String suffix) {
        return KEY_SURROUND.equals(suffix)
                || KEY_CABIN_FRONT.equals(suffix)
                || KEY_CABIN_REAR.equals(suffix);
    }
}
