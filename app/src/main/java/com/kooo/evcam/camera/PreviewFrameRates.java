package com.kooo.evcam.camera;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 各路预览的实时出帧率。
 *
 * <p>做成静态的，是因为记帧的地方（主界面的 TextureView 回调）和看结果的地方
 * （开发者选项里的相机能力清单、诊断报告）之间没有任何现成的联系，
 * 为这一件事去串一条引用链不划算。</p>
 *
 * <p>注意它测的是<b>预览</b>：录制时这个数不会变高，只可能因为整机负载变低。
 * 所以它是这条视频流的上限，而不是录制的结果。</p>
 */
public final class PreviewFrameRates {

    private static final Map<String, FrameRateMeter> METERS = new ConcurrentHashMap<>();

    private PreviewFrameRates() {
    }

    /** 某一路来了一帧。 */
    public static void onFrame(String cameraKey) {
        if (cameraKey == null) {
            return;
        }
        FrameRateMeter meter = METERS.get(cameraKey);
        if (meter == null) {
            meter = new FrameRateMeter();
            METERS.put(cameraKey, meter);
        }
        meter.onFrame(android.os.SystemClock.elapsedRealtime());
    }

    /** 某一路最近测到的帧率；没有读数时返回 0。 */
    public static float fps(String cameraKey) {
        FrameRateMeter meter = METERS.get(cameraKey);
        return meter == null ? 0f : meter.fps();
    }

    /** 某一路从开始到现在收到的总帧数。 */
    public static long totalFrames(String cameraKey) {
        FrameRateMeter meter = METERS.get(cameraKey);
        return meter == null ? 0L : meter.totalFrames();
    }

    /** 一行可读的汇总，给诊断报告和测试界面用。 */
    public static String describe() {
        if (METERS.isEmpty()) {
            return "没有任何一路在出帧（主界面没打开过，或相机没起来）";
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, FrameRateMeter> entry : METERS.entrySet()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            FrameRateMeter meter = entry.getValue();
            sb.append(String.format(Locale.US, "  %-6s %s   累计 %d 帧",
                    entry.getKey(), meter, meter.totalFrames()));
        }
        return sb.toString();
    }

    public static void reset() {
        METERS.clear();
    }
}
