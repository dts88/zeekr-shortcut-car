package com.kooo.evcam.profile;

/**
 * 把配置里的「意图」翻译成一个具体尺寸。
 *
 * <h3>为什么这一步要单独存在</h3>
 *
 * <p>配置里存的是 {@code auto} / {@code max} / {@code "1280x5140"} 这样的意图，
 * 不是数字（理由见 {@link StreamSpec}）。真要开相机时总得有个数 ——
 * 这里就是那个唯一的翻译点。</p>
 *
 * <p>翻译需要两样外部信息：这一路<b>探测到的尺寸</b>（合成流那一路是 1280×5140），
 * 和这一路<b>声明的最大尺寸</b>。都由调用方给，所以这段逻辑不碰 Android，可以单独测。</p>
 */
public final class ProfileResolution {

    /** 解析结果。{@code width <= 0} 表示「不指定，交给下游自己挑」。 */
    public static final class Size {
        public final int width;
        public final int height;

        Size(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public boolean specified() {
            return width > 0 && height > 0;
        }

        @Override
        public String toString() {
            return specified() ? width + "x" + height : "不指定";
        }
    }

    /** 不指定：让 {@code SingleCamera.chooseOptimalSize} 按它原来的规则挑。 */
    public static final Size UNSPECIFIED = new Size(0, 0);

    private ProfileResolution() {
    }

    /**
     * @param intent    配置里的分辨率意图
     * @param probed    这一路探测到的尺寸，形如 {@code {宽, 高}}；没有就传 null
     * @param declaredMax 这一路声明的最大尺寸；没有就传 null
     */
    public static Size resolve(String intent, int[] probed, int[] declaredMax) {
        if (intent == null || intent.isEmpty() || StreamSpec.RESOLUTION_AUTO.equals(intent)) {
            // auto：有探测结果就用探测结果（合成流那一路），没有就不指定
            return probed != null ? new Size(probed[0], probed[1]) : UNSPECIFIED;
        }
        if (StreamSpec.RESOLUTION_MAX.equals(intent)) {
            return declaredMax != null ? new Size(declaredMax[0], declaredMax[1]) : UNSPECIFIED;
        }
        int[] parsed = parse(intent);
        return parsed != null ? new Size(parsed[0], parsed[1]) : UNSPECIFIED;
    }

    /** {@code "1280x5140"} → {@code {1280, 5140}}；认不出返回 null。 */
    public static int[] parse(String text) {
        if (text == null) {
            return null;
        }
        int split = text.indexOf('x');
        if (split <= 0 || split >= text.length() - 1) {
            return null;
        }
        try {
            int width = Integer.parseInt(text.substring(0, split).trim());
            int height = Integer.parseInt(text.substring(split + 1).trim());
            return width > 0 && height > 0 ? new int[]{width, height} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
