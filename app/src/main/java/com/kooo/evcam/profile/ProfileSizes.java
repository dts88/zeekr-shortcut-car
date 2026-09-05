package com.kooo.evcam.profile;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.ImageFormat;
import android.util.Size;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.zeekr.StreamLayoutTable;

/**
 * 「这一路的这条流该用多大」——配置化之后唯一的取值口。
 *
 * <h3>为什么要有这么一个地方</h3>
 *
 * <p>第 2 步只把<b>预览</b>接上了配置，录制和拍照还各走各的老路：录制流的尺寸取自
 * 预览尺寸，拍照直接用声明的最大值。于是配置编辑里改录制或拍照的分辨率毫无反应，
 * 改预览却把三条流一起带动了 —— 界面上写着三个独立的值，实际只有一个在起作用。</p>
 *
 * <p>三条流各问各的，答案才可能和界面上写的一致。</p>
 */
public final class ProfileSizes {

    private static final String TAG = "ProfileSizes";

    private ProfileSizes() {
    }

    /** 录制流要用的相机输出尺寸；返回 null 表示「跟着预览走」（配置里是 auto）。 */
    public static Size record(Context context, String role, Size previewSize) {
        return forStream(context, role, spec -> spec.record, previewSize);
    }

    /** 拍照流要用的尺寸；返回 null 表示「用声明的最大值」。 */
    public static Size photo(Context context, String role, Size previewSize) {
        return forStream(context, role, spec -> spec.photo, previewSize);
    }

    private interface Pick {
        StreamSpec from(CameraProfile camera);
    }

    private static Size forStream(Context context, String role, Pick pick, Size previewSize) {
        if (context == null || role == null) {
            return null;
        }
        try {
            CameraProfile camera = new ProfileStore(context).current().camera(role);
            if (camera == null) {
                return null;
            }
            StreamSpec spec = pick.from(camera);
            if (spec == null || StreamSpec.RESOLUTION_AUTO.equals(spec.resolution)) {
                return null;
            }
            int[] probed = previewSize == null
                    ? null : new int[]{previewSize.getWidth(), previewSize.getHeight()};
            int[] max = declaredMax(context, role);
            ProfileResolution.Size size =
                    ProfileResolution.resolve(spec.resolution, probed, max);
            return size.specified() ? new Size(size.width, size.height) : null;
        } catch (Exception e) {
            AppLog.w(TAG, "取 " + role + " 的流尺寸失败: " + e);
            return null;
        }
    }

    /**
     * 这一路的「最大」尺寸 —— 拍照的 {@code max} 就是它。
     *
     * <h3>合成流那一路不能按像素总数比</h3>
     *
     * <p>它的每个尺寸装的都是同一份四格内容，只是被压成不同形状：</p>
     *
     * <pre>
     *   1280×5140   每格 1280×1285   像素 1.64M   短边 1280
     *   3840×2160   每格 3840×540    像素 2.07M   短边 540
     * </pre>
     *
     * <p>按像素总数比，3840×2160 赢 —— 但那一格是一个<b>方形画面被压成 7:1</b>，
     * 真正的细节被短边卡死在 540 行，比 1280×5140 差一半还多。拍出来是一张
     * 7680×1080 的超宽图，四个画面全是扁的。</p>
     *
     * <p>所以这一路按<b>每格短边</b>挑。座舱那两路不拆，一格就是整幅画面，
     * 按像素总数挑没有问题。</p>
     */
    public static int[] declaredMax(Context context, String role) {
        String cameraId = cameraIdFor(context, role);
        if (cameraId == null) {
            return null;
        }
        try {
            CameraManager manager =
                    (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            StreamConfigurationMap map = manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map == null) {
                return null;
            }
            Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
            if (sizes == null || sizes.length == 0) {
                return null;
            }
            boolean splits = CameraProfile.ROLE_COMPOSITE.equals(role);
            Size best = sizes[0];
            long bestScore = -1;
            for (Size size : sizes) {
                long score = score(size, splits);
                if (score > bestScore) {
                    bestScore = score;
                    best = size;
                }
            }
            return new int[]{best.getWidth(), best.getHeight()};
        } catch (Exception e) {
            AppLog.w(TAG, "读不到 " + role + " 的最大尺寸: " + e);
            return null;
        }
    }

    /**
     * 拆分的那一路按每格短边打分，不拆的按像素总数。
     *
     * <p>四格竖排时每格是 {@code 宽 × 高/4}，短边就是这一格能表达的真实细节。</p>
     */
    private static long score(Size size, boolean splits) {
        if (!splits) {
            return (long) size.getWidth() * size.getHeight();
        }
        int laneHeight = size.getHeight() / 4;
        return Math.min(size.getWidth(), laneHeight);
    }

    /**
     * 相机键（front / back / left）对应的角色。
     *
     * <p>接线那一层用的是槽位名，配置里用的是角色。这里是两者之间唯一的换算点。</p>
     */
    public static String roleForCameraKey(String cameraKey) {
        if ("front".equals(cameraKey)) {
            return CameraProfile.ROLE_COMPOSITE;
        }
        if ("back".equals(cameraKey)) {
            return CameraProfile.ROLE_CABIN_1;
        }
        if ("left".equals(cameraKey)) {
            return CameraProfile.ROLE_CABIN_2;
        }
        return null;
    }

    private static String cameraIdFor(Context context, String role) {
        String composite = StreamLayoutTable.compositeCameraId();
        try {
            CameraManager manager =
                    (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            String[] ids = manager.getCameraIdList();
            if (CameraProfile.ROLE_COMPOSITE.equals(role)) {
                return composite != null ? composite : (ids.length > 0 ? ids[0] : null);
            }
            java.util.List<String> others = new java.util.ArrayList<>();
            for (String id : ids) {
                if (!id.equals(composite)) {
                    others.add(id);
                }
            }
            int index = CameraProfile.ROLE_CABIN_1.equals(role) ? 0 : 1;
            return index < others.size() ? others.get(index) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
