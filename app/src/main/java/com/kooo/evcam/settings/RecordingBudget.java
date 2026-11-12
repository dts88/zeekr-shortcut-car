/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Size;

import androidx.annotation.Nullable;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.camera.EncodeSize;
import com.kooo.evcam.camera.StorageBudget;
import com.kooo.evcam.camera.TargetBitrate;
import com.kooo.evcam.profile.CameraProfile;
import com.kooo.evcam.profile.Profile;
import com.kooo.evcam.profile.ProfileResolution;
import com.kooo.evcam.profile.ProfileSizes;
import com.kooo.evcam.profile.QualityPreset;
import com.kooo.evcam.profile.StreamSpec;
import com.kooo.evcam.zeekr.StreamLayoutTable;

import java.util.ArrayList;
import java.util.List;

/**
 * 一份视频流配置录下来是多大：每一路向相机要多大、编码成多大、配多少码率，合起来一小时写多少、U 盘还能录多久。
 *
 * <p>原来是「详细参数」那一页（{@link ProfileEditorFragment}）自己的算法；4.0 起设置 → 录制「录制画质」那一行、
 * 存储「存储上限」那一行也要写同样的数（ui-spec §十六），所以搬到这里，三处问的是同一份 —— 不能各算各的。
 * 码率走的是录制那边同一个 {@link TargetBitrate}：界面写的数就是实际配下去的数。</p>
 */
final class RecordingBudget {

    private static final String TAG = "RecordingBudget";

    private final Context context;
    @Nullable
    private final CameraManager cameraManager;
    private final Profile profile;

    RecordingBudget(Context context, @Nullable CameraManager cameraManager, Profile profile) {
        this.context = context;
        this.cameraManager = cameraManager;
        this.profile = profile;
    }

    /** 不在编辑器里时用：相机服务自己取。 */
    RecordingBudget(Context context, Profile profile) {
        this(context, (CameraManager) context.getSystemService(Context.CAMERA_SERVICE), profile);
    }

    /** 这份配置里有没有哪一路开着、而帧率码率不是选的那一档（单独调过）。 */
    boolean tuned() {
        for (CameraProfile camera : profile.cameras) {
            if (camera.enabled && !profile.quality.matches(camera.record)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这一路的这个尺寸，真正会编码成多大。
     *
     * <h3>相机 id 必须是这一路自己的</h3>
     *
     * <p>拆不拆只看相机 id（{@code StreamLayoutTable.stackingFor}）。
     * 以前这里不管哪一路都传环视的 id，于是座舱的 1280×800
     * 被当成四格竖条拆了：每格 1280×200，2×2 拼回去就是那个
     * 凭空出现的 2560×400（1280×720 则是 2560×360）。</p>
     */
    EncodeSize landingFor(CameraProfile camera, int[] source) {
        boolean grid = splitsFor(camera.role) && camera.record != null && camera.record.grid;
        return EncodeSize.forSource(cameraIdFor(camera.role), source[0], source[1], grid);
    }

    /**
     * 现在开着的这几路各配多少码率（bps）。
     *
     * @param preset 不为 null 时按这一档算（三张卡上那两个数），null 表示按现在的设置算
     */
    int[] enabledRates(@Nullable QualityPreset preset) {
        List<Integer> rates = new ArrayList<>();
        for (CameraProfile camera : profile.cameras) {
            if (!camera.enabled) {
                continue;
            }
            String fps = preset == null ? camera.record.fps : preset.fps;
            String bitrate = preset == null ? camera.record.bitrate : preset.bitrate;
            rates.add(estimatedBitrate(camera, fps, bitrate) + soundBitrate(camera));
        }
        int[] bits = new int[rates.size()];
        for (int i = 0; i < bits.length; i++) {
            bits[i] = rates.get(i);
        }
        return bits;
    }

    /**
     * 环视那一路录着声音时多出来的码率（开发者选项「录制声音」）：开着、有权限、走 MediaCodec 录制时才真录 ——
     * 和定录法问的是同一处（{@code FootageAudio.sourceFor}）。界面写的码率、每小时用量都要把它算进去。
     */
    int soundBitrate(CameraProfile camera) {
        if (!CameraProfile.ROLE_COMPOSITE.equals(camera.role)) {
            return 0;
        }
        AppConfig config = new AppConfig(context);
        return com.kooo.evcam.camera.FootageAudio.sourceFor(context, config) != null
                && config.shouldUseCodecRecording() ? com.kooo.evcam.camera.FootageAudio.BIT_RATE : 0;
    }

    /** 这份配置一小时落盘多少字节。 */
    long bytesPerHourFor(@Nullable QualityPreset preset) {
        return StorageBudget.bytesPerHour(enabledRates(preset));
    }

    /**
     * 这一路按这组参数会配多少码率。
     *
     * <p>走的是录制那边同一个 {@link TargetBitrate} —— 界面写的数和实际配下去的
     * 必须是同一个，不能在这里另算一遍。</p>
     */
    int estimatedBitrate(CameraProfile camera, String fps, String bitrate) {
        int[] source = resolvedSource(camera.role, camera.record);
        if (source == null) {
            return 0;
        }
        EncodeSize size = landingFor(camera, source);
        int max = com.kooo.evcam.camera.CameraCapabilities.declaredMaxFps();
        int nominal = com.kooo.evcam.profile.RecordSpecs.nominal(fps,
                max > 0 ? max : AppConfig.RECORDER_MAX_FPS);
        boolean h264 = com.kooo.evcam.profile.RecordSpecs.forceH264(camera.record.codec)
                || new AppConfig(context).isForceH264Encoding();
        return TargetBitrate.compute(
                com.kooo.evcam.profile.RecordSpecs.qualityLevel(bitrate),
                size.width, size.height, nominal, !h264);
    }

    long freeBytes() {
        java.io.File sdCard =
                com.kooo.evcam.StorageHelper.getExternalSdCardRoot(context);
        return sdCard == null ? 0 : com.kooo.evcam.StorageHelper.getAvailableSpace(sdCard);
    }

    /**
     * 这条流最后会向相机要多大；解不出来返回 null。
     *
     * <p>{@code auto} 在会拆的那一路是「每格最清楚的那个声明尺寸」，其他路跟随预览
     * ——和 {@link ProfileSizes} 是同一条规则，两边不能各说各的。</p>
     */
    int[] resolvedSource(String role, StreamSpec spec) {
        int[] parsed = ProfileResolution.parse(spec.resolution);
        if (parsed != null) {
            return parsed;
        }
        int[] max = ProfileSizes.declaredMax(context, role);
        if (StreamSpec.RESOLUTION_MAX.equals(spec.resolution)) {
            return max;
        }
        if (CameraProfile.ROLE_COMPOSITE.equals(role)) {
            return max;
        }
        // 座舱那两路的 auto：拍照用声明的最大值，预览按「最接近 1280×800」
        // 挑，录制跟着预览走。这三条以前在这里一律返回 null，于是卡上的码率
        // 写成 0 kbps，「还能录多久」也完全不受这两路开关的影响 ——
        // 而这个数是算得出来的，算得出来就不能写 0。
        CameraProfile camera = profile.camera(role);
        if (camera == null) {
            return previewDefaultSize(role);
        }
        if (spec == camera.photo) {
            return max;
        }
        if (spec != camera.preview && camera.preview != null) {
            return resolvedSource(role, camera.preview);
        }
        return previewDefaultSize(role);
    }

    /**
     * 配置里写 auto 时，预览会挑中的那个尺寸。
     *
     * <p>和 {@code SingleCamera.chooseOptimalSize} 同一条规则：先找 1280×800，
     * 找不到就找最接近的。两边必须一致 —— 界面上写的数就是实际配下去的数。</p>
     */
    int[] previewDefaultSize(String role) {
        int[] best = null;
        int bestDiff = Integer.MAX_VALUE;
        for (int[] size : declaredPreviewSizes(role)) {
            if (size[0] == 1280 && size[1] == 800) {
                return size;
            }
            int diff = Math.abs(1280 - size[0]) + Math.abs(800 - size[1]);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = size;
            }
        }
        return best;
    }

    /** 预览走的是 PRIVATE / SurfaceTexture 那一份声明，不是 JPEG 那一份。 */
    List<int[]> declaredPreviewSizes(String role) {
        List<int[]> out = new ArrayList<>();
        String cameraId = cameraIdFor(role);
        if (cameraId == null || cameraManager == null) {
            return out;
        }
        try {
            StreamConfigurationMap map = cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return out;
            }
            Size[] sizes = map.getOutputSizes(ImageFormat.PRIVATE);
            if (sizes == null || sizes.length == 0) {
                sizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
            }
            if (sizes == null) {
                return out;
            }
            for (Size size : sizes) {
                out.add(new int[]{size.getWidth(), size.getHeight()});
            }
        } catch (Exception e) {
            AppLog.w(TAG, "读不到 " + role + " 的预览尺寸列表: " + e);
        }
        return out;
    }

    /**
     * 这一路拆不拆。
     *
     * <p>环视那一路在 {@link StreamLayoutTable} 里，也就是<b>一定</b>拆 —— 和分辨率无关，
     * 也和「这次相机开没开起来」无关。以前这里还要求表里已经登记了相机 id，
     * 于是相机还没起来时进设置，编辑器会把环视说成不拆的，连每一格都不给编。</p>
     */
    boolean splitsFor(String role) {
        return CameraProfile.ROLE_COMPOSITE.equals(role);
    }

    String cameraIdFor(String role) {
        String composite = StreamLayoutTable.compositeCameraId();
        try {
            if (cameraManager == null) {
                return null;
            }
            String[] ids = cameraManager.getCameraIdList();
            if (CameraProfile.ROLE_COMPOSITE.equals(role)) {
                return composite != null ? composite : (ids.length > 0 ? ids[0] : null);
            }
            List<String> others = new ArrayList<>();
            for (String id : ids) {
                if (!id.equals(composite)) {
                    others.add(id);
                }
            }
            int index = CameraProfile.ROLE_CABIN_1.equals(role) ? 0 : 1;
            return index < others.size() ? others.get(index) : null;
        } catch (Exception e) {
            AppLog.w(TAG, "读不到相机列表: " + e);
            return null;
        }
    }
}
