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

    /** 这一路声明的最大 JPEG 尺寸。拍照的 max 就是它。 */
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
            Size largest = sizes[0];
            for (Size size : sizes) {
                if ((long) size.getWidth() * size.getHeight()
                        > (long) largest.getWidth() * largest.getHeight()) {
                    largest = size;
                }
            }
            return new int[]{largest.getWidth(), largest.getHeight()};
        } catch (Exception e) {
            AppLog.w(TAG, "读不到 " + role + " 的最大尺寸: " + e);
            return null;
        }
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
