package com.kooo.evcam.camera;

import android.hardware.camera2.CameraCharacteristics;
import android.util.Range;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 相机声明的能力里，运行期还要用到的那几项。
 *
 * <h3>为什么要记下来</h3>
 *
 * <p>「原始帧率」这一项以前显示的是一个<b>写死的 25</b>，跟相机没有关系。
 * 而这台车机上，环视这一路声明的是 15–30、实际送出 29 —— 界面上那个 25
 * 既不是相机能给的，也不是我们真录到的，两头都不沾。</p>
 *
 * <p>设置界面拿不到相机对象（相机可能还没开），所以在相机打开时把这几个数
 * 记在这里，界面用的时候来问。<b>没记到就不显示数字</b> ——
 * 与其编一个，不如老实说「跟随相机」。</p>
 */
public final class CameraCapabilities {

    private static final Map<String, Integer> MAX_FPS = new ConcurrentHashMap<>();
    private static final Map<String, int[][]> FPS_RANGES = new ConcurrentHashMap<>();

    private CameraCapabilities() {
    }

    /** 相机打开时调一次，把它声明的最高帧率记下来。 */
    public static void record(String cameraId, CameraCharacteristics characteristics) {
        if (cameraId == null || characteristics == null) {
            return;
        }
        Range<Integer>[] ranges =
                characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) {
            return;
        }
        int highest = 0;
        for (Range<Integer> range : ranges) {
            if (range != null && range.getUpper() != null) {
                highest = Math.max(highest, range.getUpper());
            }
        }
        if (highest > 0) {
            MAX_FPS.put(cameraId, highest);
        }
        int[][] pairs = new int[ranges.length][2];
        for (int i = 0; i < ranges.length; i++) {
            pairs[i][0] = ranges[i] == null || ranges[i].getLower() == null ? 0 : ranges[i].getLower();
            pairs[i][1] = ranges[i] == null || ranges[i].getUpper() == null ? 0 : ranges[i].getUpper();
        }
        FPS_RANGES.put(cameraId, pairs);
    }

    /** 某台相机声明的全部帧率区间；没记到时返回 null。 */
    public static int[][] fpsRanges(String cameraId) {
        return cameraId == null ? null : FPS_RANGES.get(cameraId);
    }

    /**
     * 已知的最高声明帧率；一个都没记到时返回 0。
     *
     * <p>取所有相机里最高的那个：设置里那一项是全局的，而各路相机的声明
     * 通常一致；不一致时也该按能力最强的那一路给上限，不是最弱的。</p>
     */
    public static int declaredMaxFps() {
        int highest = 0;
        for (Integer value : MAX_FPS.values()) {
            if (value != null) {
                highest = Math.max(highest, value);
            }
        }
        return highest;
    }

    /** 有没有从相机读到过帧率声明。 */
    public static boolean hasDeclaredFps() {
        return declaredMaxFps() > 0;
    }

    public static void reset() {
        MAX_FPS.clear();
        FPS_RANGES.clear();
    }
}
