package com.kooo.evcam.camera;

/**
 * 角标上那几行字怎么拼。
 *
 * <h3>为什么抽出来</h3>
 *
 * <p>录像和照片各画各的：录像在 GL 里画，照片在 Canvas 上画。两边各拼一次字符串，
 * 结果就是同一台设备录出来的视频和拍出来的照片，角标写的东西不一样 ——
 * 而它们本该是同一件事的两种记录。</p>
 *
 * <p>纯字符串拼接，可以单独测。</p>
 */
public final class WatermarkText {

    /** 同一行里两段信息之间的分隔。 */
    private static final String SEP = "  ";

    private WatermarkText() {
    }

    /**
     * 左上角第一行：应用名 + 版本，填了车牌号就跟在后面。
     *
     * <p>这一行是<b>无条件</b>盖的：录出来的东西常常拿去当凭据或者发给别人，
     * 落上是哪个应用、哪个版本录的，回头才对得上。车牌号是可选的，
     * 因为那是车主自己的信息，要不要落在画面里应当由他决定。</p>
     */
    public static String brandLine(String appName, String version, String plate) {
        StringBuilder sb = new StringBuilder();
        sb.append(appName == null ? "" : appName);
        if (version != null && !version.isEmpty()) {
            sb.append(" v").append(version);
        }
        if (plate != null && !plate.isEmpty()) {
            sb.append(SEP).append(plate);
        }
        return sb.toString().trim();
    }

    /**
     * 照片的规格行：只有尺寸。
     *
     * <p>照片没有帧率、码率、编码可言 —— 那几项是录像才有的。
     * 与其为了「和视频一致」硬凑几个数，不如只写这一张图真的具备的信息。</p>
     */
    public static String photoSpecLine(int width, int height) {
        if (width <= 0 || height <= 0) {
            return "";
        }
        return width + "x" + height;
    }
}
