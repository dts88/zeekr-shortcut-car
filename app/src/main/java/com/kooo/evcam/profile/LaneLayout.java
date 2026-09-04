package com.kooo.evcam.profile;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 一格画面在主界面上怎么摆、怎么显示。
 *
 * <h3>位置和尺寸用比例，不用像素</h3>
 *
 * <p>车机内屏是 3200×2000，副屏是 2560×536。存像素的话换一块屏就全错位了。
 * 存 0–1 的比例，容器多大都对得上。</p>
 *
 * <h3>为什么把旋转、镜像、裁切也收进来</h3>
 *
 * <p>这三样今天散在两个地方：{@code CustomLayoutManager} 按相机存旋转 / 镜像 /
 * 四边裁切，{@code PreviewCorrection} 另外按相机存缩放 / 平移。它们和「摆在哪里」
 * 是同一件事的两半 —— 都是「这一格最终长什么样」，没有理由分成两套。</p>
 */
public final class LaneLayout {

    /** 这一格显示哪一路画面：合成流的 lane 序号（0–3），或普通相机的 -1。 */
    public int laneIndex = -1;

    /** 左上角位置，容器宽高的比例。 */
    public float x;
    public float y;

    /** 尺寸，容器宽高的比例。 */
    public float width = 1f;
    public float height = 1f;

    /** 顺时针旋转角度：0 / 90 / 180 / 270。 */
    public int rotation;

    /** 左右镜像。后视那一路默认开着，和真实后视镜一致。 */
    public boolean mirrored;

    /** 四边各裁掉多少，画面宽高的比例。用来切掉分隔带残留或不想要的边缘。 */
    public float cropTop;
    public float cropBottom;
    public float cropLeft;
    public float cropRight;

    /**
     * 画面在这一格里的缩放与平移，就是原来那个「预览矫正」。
     *
     * <h3>和裁切的区别</h3>
     *
     * <p>两者都在动源矩形，但意图不同：<b>裁切</b>是「这几条边我不要」，
     * 会改变画面的长宽比；<b>缩放平移</b>是「画面偏了 / 大小不对，挪一下」，
     * 长宽比不变。修一个装歪了的摄像头用后者，切掉分隔带残留用前者。</p>
     *
     * <p>平移是相对这一格宽高的比例，正值向右 / 向下。</p>
     */
    public float scaleX = 1f;
    public float scaleY = 1f;
    public float translateX;
    public float translateY;

    public static LaneLayout cell(int laneIndex, float x, float y, float width, float height) {
        LaneLayout layout = new LaneLayout();
        layout.laneIndex = laneIndex;
        layout.x = x;
        layout.y = y;
        layout.width = width;
        layout.height = height;
        return layout;
    }

    public Map<String, String> toMap() {
        Map<String, String> out = new LinkedHashMap<>();
        out.put("laneIndex", String.valueOf(laneIndex));
        out.put("x", fraction(x));
        out.put("y", fraction(y));
        out.put("width", fraction(width));
        out.put("height", fraction(height));
        out.put("rotation", String.valueOf(rotation));
        out.put("mirrored", String.valueOf(mirrored));
        out.put("cropTop", fraction(cropTop));
        out.put("cropBottom", fraction(cropBottom));
        out.put("cropLeft", fraction(cropLeft));
        out.put("cropRight", fraction(cropRight));
        out.put("scaleX", fraction(scaleX));
        out.put("scaleY", fraction(scaleY));
        out.put("translateX", fraction(translateX));
        out.put("translateY", fraction(translateY));
        return out;
    }

    public static LaneLayout fromMap(Map<String, String> values) {
        LaneLayout layout = new LaneLayout();
        if (values == null) {
            return layout;
        }
        layout.laneIndex = integer(values, "laneIndex", -1);
        layout.x = decimal(values, "x", 0f);
        layout.y = decimal(values, "y", 0f);
        layout.width = decimal(values, "width", 1f);
        layout.height = decimal(values, "height", 1f);
        layout.rotation = integer(values, "rotation", 0);
        layout.mirrored = Boolean.parseBoolean(values.get("mirrored"));
        layout.cropTop = decimal(values, "cropTop", 0f);
        layout.cropBottom = decimal(values, "cropBottom", 0f);
        layout.cropLeft = decimal(values, "cropLeft", 0f);
        layout.cropRight = decimal(values, "cropRight", 0f);
        layout.scaleX = decimal(values, "scaleX", 1f);
        layout.scaleY = decimal(values, "scaleY", 1f);
        layout.translateX = decimal(values, "translateX", 0f);
        layout.translateY = decimal(values, "translateY", 0f);
        return layout;
    }

    /** 四位小数够了：3200px 宽的屏上，0.0001 是 0.32 个像素。 */
    private static String fraction(float value) {
        return String.format(Locale.US, "%.4f", value);
    }

    private static float decimal(Map<String, String> values, String key, float fallback) {
        try {
            String value = values.get(key);
            return value == null || value.isEmpty() ? fallback : Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int integer(Map<String, String> values, String key, int fallback) {
        try {
            String value = values.get(key);
            return value == null || value.isEmpty() ? fallback : Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "lane%d (%.2f,%.2f) %.2fx%.2f%s%s",
                laneIndex, x, y, width, height,
                rotation != 0 ? " 旋转" + rotation : "",
                mirrored ? " 镜像" : "")
                + (scaleX != 1f || scaleY != 1f
                        ? String.format(Locale.US, " 缩放%.2fx%.2f", scaleX, scaleY) : "")
                + (translateX != 0f || translateY != 0f
                        ? String.format(Locale.US, " 平移%.2f,%.2f", translateX, translateY) : "")
                + (cropTop != 0f || cropBottom != 0f || cropLeft != 0f || cropRight != 0f
                        ? String.format(Locale.US, " 裁切%.2f/%.2f/%.2f/%.2f",
                                cropTop, cropBottom, cropLeft, cropRight) : "");
    }
}
