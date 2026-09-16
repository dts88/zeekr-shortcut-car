package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.Bitmap;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.profile.RecordSpecs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * 工程模式：把相机直出的<b>整帧原图</b>连同它的几何参数存一份。
 *
 * <h3>这是给谁用的</h3>
 *
 * <p>合成流是四路竖着排成一条长条。应用平时存盘的是重排成 2×2、盖了角标之后的结果，
 * 那张图已经被动过好几道手 —— 拿它去反推「这一路到底占哪一块像素」「鱼眼的光心在哪里」
 * 「视场角是多少」，量出来的都是二手数据。</p>
 *
 * <p>所以这里存的是<b>没动过的那一张</b>：不切分、不重排、不盖角标。
 * 旁边再写一个同名的 txt，把当时的判定结果一并记下来（这一帧多大、程序认为它是不是合成流、
 * 每一路落在哪个矩形、当时的录制配置是什么）。有了这两个文件，画面几何就能在电脑上量准，
 * 而不是照着模型猜。</p>
 *
 * <p>存在 {@code photos/raw/} 下，不在图片回看的扫描范围内 —— 回看列出的是拍下来的照片，
 * 而这是一份取证材料，混在一起只会让人以为应用突然开始存重复的图。</p>
 */
public final class RawFrameDump {

    private static final String TAG = "RawFrameDump";

    /** 原图另存的子目录名。 */
    public static final String DIR_NAME = "raw";

    private RawFrameDump() {
    }

    /**
     * 存一份原图和它的说明。
     *
     * <p>任何一步失败都只记日志：这是附带的一份材料，不该影响正常那张照片。</p>
     *
     * @param frame     相机直出、尚未重排的整帧
     * @param photoDir  照片目录，本方法在它下面建 {@link #DIR_NAME}
     * @param timestamp 和正常那张照片同一个时间戳，便于对上
     */
    public static void save(Context context, Bitmap frame, File photoDir, String timestamp,
                            String cameraId, String position) {
        if (context == null || frame == null || frame.isRecycled() || photoDir == null) {
            return;
        }
        File dir = new File(photoDir, DIR_NAME);
        if (!dir.exists() && !dir.mkdirs()) {
            AppLog.w(TAG, "建不了原图目录: " + dir.getAbsolutePath());
            return;
        }

        String name = timestamp + "_" + position + "_raw";
        File image = new File(dir, name + ".jpg");
        // 95 而不是平时的 90：这张图是拿去量几何的，压缩痕迹越少越好
        try (FileOutputStream output = new FileOutputStream(image)) {
            frame.compress(Bitmap.CompressFormat.JPEG, 95, output);
            output.flush();
            AppLog.i(TAG, "原始整帧已存: " + image.getAbsolutePath()
                    + " " + frame.getWidth() + "x" + frame.getHeight());
        } catch (Exception e) {
            AppLog.e(TAG, "原始整帧存不下来: " + e);
            return;
        }

        File notes = new File(dir, name + ".txt");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(notes),
                StandardCharsets.UTF_8)) {
            writer.write(describe(context, frame, timestamp, cameraId, position));
        } catch (Exception e) {
            AppLog.w(TAG, "说明写不下来（图已经存好了）: " + e);
        }
    }

    /** 这一帧的来历和程序对它的判定，逐行写清楚。 */
    private static String describe(Context context, Bitmap frame, String timestamp,
                                   String cameraId, String position) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 原始整帧\n");
        sb.append("时间: ").append(timestamp).append('\n');
        sb.append("相机 id: ").append(cameraId).append('\n');
        sb.append("这一路: ").append(position).append('\n');
        sb.append("尺寸: ").append(frame.getWidth()).append('x').append(frame.getHeight())
                .append('\n');
        sb.append("设备: ").append(android.os.Build.MODEL)
                .append(" / Android ").append(android.os.Build.VERSION.RELEASE).append('\n');

        sb.append("\n## 合成流判定\n");
        try {
            boolean looksComposite = CompositeStreamGeometry.looksLikeComposite(
                    cameraId, frame.getWidth(), frame.getHeight());
            sb.append("认作合成流: ").append(looksComposite).append('\n');
            CompositeStreamGeometry.Plan plan = CompositeStreamGeometry.analyse(
                    cameraId, frame.getWidth(), frame.getHeight());
            sb.append("方案: ").append(plan).append('\n');
            for (int i = 0; i < plan.laneCount(); i++) {
                sb.append("  lane ").append(i).append(": ").append(plan.lane(i)).append('\n');
            }
        } catch (Exception e) {
            sb.append("读不出来: ").append(e).append('\n');
        }

        sb.append("\n## 当时的录制配置\n");
        try {
            sb.append(RecordSpecs.forCameraKey(context, position)).append('\n');
        } catch (Exception e) {
            sb.append("读不出来: ").append(e).append('\n');
        }

        sb.append("\n## 当时的鱼眼校正设置\n");
        try {
            AppConfig config = new AppConfig(context);
            sb.append("后视镜校正: ").append(config.isRearViewFisheyeCorrection())
                    .append("，视野 ").append(config.getRearViewFov()).append("°\n");
            sb.append("图片回看校正: ").append(config.isPhotoFisheyeCorrection())
                    .append("，视野 ").append(FisheyeProjection.PHOTO_FOV_DEGREES).append("°\n");
        } catch (Exception e) {
            sb.append("读不出来: ").append(e).append('\n');
        }
        return sb.toString();
    }
}
