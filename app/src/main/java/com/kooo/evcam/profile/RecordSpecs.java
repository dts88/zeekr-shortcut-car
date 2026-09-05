package com.kooo.evcam.profile;

import android.content.Context;

import com.kooo.evcam.AppLog;

/**
 * 录制那几个参数：帧率、码率、编码、分段。
 *
 * <h3>为什么要有这么一个地方</h3>
 *
 * <p>配置编辑里这四项一直是<b>只写不读</b>的：编辑器存进配置、界面照着配置显示，
 * 而录制链路读的还是设置里那几个全局键。也就是说在配置编辑里把帧率改成 15，
 * 界面从此写着 15，录出来的仍然是原来那个值 —— 界面显示值和实际生效值分了家。</p>
 *
 * <p>这个类是「录制该用什么参数」的唯一取值口。取值的规则（尤其是帧率那两个数）
 * 是纯函数，可以单独测。</p>
 *
 * <h3>帧率为什么要两个数</h3>
 *
 * <ul>
 *   <li><b>上限</b>（{@link #cap}）给渲染节流用，可以是「不限制」（0）；</li>
 *   <li><b>标称值</b>（{@link #nominal}）给编码器和码率估算用，必须是正数 ——
 *       给编码器 0 会让它按最低码率工作，给相机 0 会让自动曝光挑最慢的那个区间。</li>
 * </ul>
 */
public final class RecordSpecs {

    private static final String TAG = "RecordSpecs";

    /** 「不限制」在录制链路里就是 0：一帧都不丢。 */
    public static final int FPS_UNLIMITED = 0;

    /** 读不到配置时用的分段时长，和旧的全局默认一致。 */
    public static final int DEFAULT_SEGMENT_MINUTES = 1;

    private RecordSpecs() {
    }

    /**
     * 这一路的录制配置。
     *
     * @param cameraKey 接线用的槽位名（front / back / left）
     * @return 永远不为 null —— 读不到配置也得录，给一份默认值
     */
    public static StreamSpec forCameraKey(Context context, String cameraKey) {
        return forRole(context, ProfileSizes.roleForCameraKey(cameraKey));
    }

    /** 同上，按角色取。 */
    public static StreamSpec forRole(Context context, String role) {
        if (context == null || role == null) {
            return defaults();
        }
        try {
            CameraProfile camera = new ProfileStore(context).current().camera(role);
            if (camera == null || camera.record == null) {
                return defaults();
            }
            return camera.record;
        } catch (Exception e) {
            AppLog.w(TAG, "读不到 " + role + " 的录制配置，用默认值: " + e);
            return defaults();
        }
    }

    /**
     * 配置里启用的那几路，用接线的槽位名表示。
     *
     * <p>「录哪几路」和「用哪几路」本来就是同一件事：关掉的相机不开、不录、不占流。
     * 以前它们是两处设置，可以配出「这一路开着但不录」——
     * 那条路上的相机照样占着流，只是录不到东西，没人说得清它为什么在那儿。</p>
     */
    public static java.util.Set<String> enabledCameraKeys(Context context) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        if (context == null) {
            return keys;
        }
        try {
            for (CameraProfile camera : new ProfileStore(context).current().cameras) {
                if (!camera.enabled) {
                    continue;
                }
                String key = ProfileSizes.cameraKeyForRole(camera.role);
                if (key != null) {
                    keys.add(key);
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "读不到配置里启用的相机: " + e);
        }
        return keys;
    }

    /**
     * 有没有哪一路要在编码前重排成 2×2。
     *
     * <p>重排要用 GL，只有 MediaCodec 那条录制路径插得上手 —— 这个问题问的是
     * 「这次录制能不能用 MediaRecorder」，所以只要有一路要重排就得走 MediaCodec。</p>
     */
    public static boolean anyGridEnabled(Context context) {
        if (context == null) {
            return true;
        }
        try {
            for (CameraProfile camera : new ProfileStore(context).current().cameras) {
                if (camera.enabled && camera.record != null && camera.record.grid) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            AppLog.w(TAG, "读不到配置里的排列，按四宫格处理: " + e);
            return true;
        }
    }

    /** 读不到配置时的那一份：不限帧率、中等码率、跟随编码器、1 分钟分段。 */
    public static StreamSpec defaults() {
        return StreamSpec.record(StreamSpec.RESOLUTION_AUTO, StreamSpec.FPS_UNLIMITED,
                "medium", "auto", DEFAULT_SEGMENT_MINUTES);
    }

    /**
     * 节流上限。
     *
     * @return {@link #FPS_UNLIMITED} 表示不节流
     */
    public static int cap(String fps, int hardwareMaxFps) {
        if (fps == null || fps.isEmpty() || StreamSpec.FPS_UNLIMITED.equals(fps)) {
            return FPS_UNLIMITED;
        }
        return explicit(fps, hardwareMaxFps);
    }

    /** 标称帧率，永远是正数。 */
    public static int nominal(String fps, int hardwareMaxFps) {
        if (fps == null || fps.isEmpty() || StreamSpec.FPS_UNLIMITED.equals(fps)) {
            // 不限制时，「预期能跑到多少」就是硬件能给到多少
            return Math.max(5, hardwareMaxFps);
        }
        return explicit(fps, hardwareMaxFps);
    }

    private static int explicit(String fps, int hardwareMaxFps) {
        try {
            return Math.max(5, Math.min(hardwareMaxFps, Integer.parseInt(fps)));
        } catch (NumberFormatException e) {
            AppLog.w(TAG, "解不开的录制帧率「" + fps + "」，按硬件上限处理");
            return Math.max(5, hardwareMaxFps);
        }
    }

    /**
     * 码率等级 → 画质档 0–3。
     *
     * <p>{@code auto} 与 {@code medium} 同档：配置里的「跟随」指的是跟随画质等级
     * 自动算码率，而中等就是那条基准线。</p>
     */
    public static int qualityLevel(String bitrate) {
        if ("low".equals(bitrate)) {
            return 1;
        }
        if ("high".equals(bitrate)) {
            return 3;
        }
        return 2;
    }

    /** 是否强制 H.264。{@code auto} 交给编码器自己挑（也就是优先 H.265）。 */
    public static boolean forceH264(String codec) {
        return "h264".equals(codec);
    }

    /**
     * 分段时长（毫秒）。
     *
     * <p>0 或负数按默认处理，不当成「不分段」—— 一个永远不落盘的文件在断电时
     * 什么都留不下，而行车记录仪最需要留下的恰恰是断电前那一段。</p>
     */
    public static long segmentMs(int minutes) {
        int safe = minutes > 0 ? minutes : DEFAULT_SEGMENT_MINUTES;
        return safe * 60L * 1000L;
    }
}
