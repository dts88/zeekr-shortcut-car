package com.kooo.evcam.zeekr;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.util.Range;
import android.util.Size;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 相机能力清单（开发者选项）。
 *
 * <h3>为什么单独做一屏</h3>
 *
 * <p>诊断报告里那几行相机信息是给我看的，字段名直接写的是 Camera2 的术语。
 * 这一屏把同样的数据摊开，每一项都写清楚<b>它决定什么</b> ——
 * 「除了 1280×5140 还有哪些能用」这种问题，看完这一屏应该能自己判断。</p>
 *
 * <p>只读，不改任何设置，也不动正在跑的相机会话。</p>
 */
public class CameraProbeActivity extends Activity {

    private static final String TAG = "CameraProbe";

    private LinearLayout content;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        content.setPadding(pad, pad, pad, pad);
        content.setBackgroundColor(0xFF1A1A1A);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);

        content.addView(button("← 返回", v -> finish()));
        content.addView(head("相机能力清单"));
        content.addView(dim("只读。这些数字决定了「能选哪些分辨率、最高多少帧」——"
                + "改设置之前先看这里，比试出来快。"));

        render();
    }

    private void render() {
        CameraManager manager = (CameraManager) getSystemService(CAMERA_SERVICE);
        if (manager == null) {
            content.addView(body("拿不到 CameraManager。"));
            return;
        }
        try {
            String[] ids = manager.getCameraIdList();
            content.addView(body("共 " + ids.length + " 个相机。"));
            for (String id : ids) {
                describeCamera(manager, id);
            }
        } catch (CameraAccessException | RuntimeException e) {
            AppLog.e(TAG, "枚举相机失败", e);
            content.addView(body("枚举相机失败: " + e));
        }
        appendInUse();
        appendGlossary();
    }

    // ------------------------------------------------------------------ 单个相机

    private void describeCamera(CameraManager manager, String id) {
        content.addView(head("相机 " + id));
        CameraCharacteristics cc;
        try {
            cc = manager.getCameraCharacteristics(id);
        } catch (CameraAccessException | RuntimeException e) {
            content.addView(body("读不到特性: " + e));
            return;
        }

        content.addView(body("朝向: " + facing(cc) + "    硬件级别: " + level(cc)));
        content.addView(dim("朝向只是 HAL 的标注，不代表它装在车的哪一侧；"
                + "硬件级别 LIMITED 表示不支持手动曝光/对焦这类高级控制，对录制没影响。"));

        StreamConfigurationMap map =
                cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            content.addView(body("!! 没有 StreamConfigurationMap —— 这一路多半用不了。"));
            return;
        }

        content.addView(sub("可用于预览 / 录制的尺寸（SurfaceTexture）"));
        appendSizes(map.getOutputSizes(SurfaceTexture.class));

        content.addView(sub("可用于拍照的尺寸（JPEG）"));
        appendSizes(map.getOutputSizes(ImageFormat.JPEG));

        content.addView(sub("帧率范围"));
        Range<Integer>[] ranges = cc.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) {
            content.addView(body("未声明 —— 相机不承诺任何帧率，跑多少是多少。"));
        } else {
            StringBuilder sb = new StringBuilder();
            int highest = 0;
            for (Range<Integer> range : ranges) {
                if (sb.length() > 0) {
                    sb.append(",  ");
                }
                sb.append(range.getLower()).append("–").append(range.getUpper());
                highest = Math.max(highest, range.getUpper());
            }
            content.addView(body(sb.toString()));
            content.addView(dim("最高 " + highest + " fps。这是相机<b>声明</b>能给的上限，"
                    + "不等于实际能稳定跑到；而且本应用目前并没有向相机请求任何帧率区间。"));
        }
    }

    /**
     * 尺寸清单，按像素从大到小，并标出宽高比。
     *
     * <p>宽高比是挑分辨率时最实际的一条：合成流是 1280×5140 这种极端比例，
     * 而 16:9 / 4:3 的那些是普通相机的常规档位。</p>
     */
    private void appendSizes(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            content.addView(body("（无）"));
            return;
        }
        List<Size> list = new ArrayList<>();
        Collections.addAll(list, sizes);
        Collections.sort(list, (a, b) ->
                b.getWidth() * b.getHeight() - a.getWidth() * a.getHeight());

        StringBuilder sb = new StringBuilder();
        for (Size size : list) {
            sb.append(String.format(Locale.US, "%d×%d   %-6s  %.1f MP%s\n",
                    size.getWidth(), size.getHeight(), ratioOf(size),
                    size.getWidth() * size.getHeight() / 1_000_000f,
                    ZeekrCompositeProfile.isKnownSize(size) ? "   ← 环视合成流" : ""));
        }
        content.addView(mono(sb.toString().trim()));
    }

    /** 约分后的宽高比，认不出常见比例时给一个小数。 */
    static String ratioOf(Size size) {
        int w = size.getWidth();
        int h = size.getHeight();
        int g = gcd(w, h);
        int rw = w / g;
        int rh = h / g;
        if (rw <= 32 && rh <= 32) {
            return rw + ":" + rh;
        }
        return String.format(Locale.US, "%.2f:1", (float) w / h);
    }

    private static int gcd(int a, int b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    // ------------------------------------------------------------------ 当前在用什么

    private void appendInUse() {
        content.addView(head("本应用当前实际在用"));
        AppConfig config = new AppConfig(this);
        content.addView(body("视频流配置: " + config.getCarModel()
                + "    录制分辨率设置: " + config.getTargetResolution()));

        MultiCameraManager manager = CameraManagerHolder.getInstance().getCameraManager();
        if (manager == null) {
            content.addView(dim("相机还没初始化 —— 先回主界面让画面出来，再进来看这一节。"));
            return;
        }
        for (String slot : new String[]{"front", "back", "left", "right"}) {
            SingleCamera camera = manager.getCamera(slot);
            if (camera == null) {
                continue;
            }
            content.addView(body(slot + ": 相机 " + camera.getCameraId()
                    + "    预览尺寸 " + camera.getPreviewSize()
                    + "    缓冲区 " + camera.getPreviewBufferSize()));
        }
        content.addView(dim("「预览尺寸」是录制用的那个；"
                + "「缓冲区」是相机实际输出的那个 —— 两者不同只在开了「预览用低分辨率」时发生。"));
    }

    // ------------------------------------------------------------------ 名词解释

    private void appendGlossary() {
        content.addView(head("这些数字分别决定什么"));
        content.addView(body(
                "• SurfaceTexture 尺寸 —— 预览与录制能选的档位。录制画面的清晰度由它决定。\n\n"
                + "• JPEG 尺寸 —— 相机拍照能出的档位。注意：本应用目前的拍照是"
                + "从预览画面上抓一帧，所以照片的清晰度受限于上面那个预览尺寸，"
                + "而不是这一栏。要用上这一栏的分辨率，得改走相机的拍照通道。\n\n"
                + "• 帧率范围 —— 相机声明能跑的区间。设置里选的帧率只是我们这边的"
                + "上限：相机给不到那么多帧，编码器就出不到那么多帧，最终文件里的"
                + "帧率是两者取小。\n\n"
                + "• 宽高比 —— 挑分辨率时最实际的一条。环视合成流是 1280×5140 这种"
                + "极端比例（四格竖排），普通相机则是 16:9 / 4:3 的常规档位。"));
    }

    // ------------------------------------------------------------------ 小工具

    private static String facing(CameraCharacteristics cc) {
        Integer facing = cc.get(CameraCharacteristics.LENS_FACING);
        if (facing == null) {
            return "未知";
        }
        switch (facing) {
            case CameraCharacteristics.LENS_FACING_FRONT:
                return "FRONT";
            case CameraCharacteristics.LENS_FACING_BACK:
                return "BACK";
            case CameraCharacteristics.LENS_FACING_EXTERNAL:
                return "EXTERNAL";
            default:
                return String.valueOf(facing);
        }
    }

    private static String level(CameraCharacteristics cc) {
        Integer level = cc.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        if (level == null) {
            return "未知";
        }
        switch (level) {
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                return "LEGACY";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                return "LIMITED";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                return "FULL";
            case CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                return "LEVEL_3";
            default:
                return String.valueOf(level);
        }
    }

    private TextView head(String text) {
        TextView view = make(text, 20, 0xFFFFFFFF);
        view.setPadding(0, dp(18), 0, dp(4));
        return view;
    }

    private TextView sub(String text) {
        TextView view = make(text, 15, 0xFF4A90D9);
        view.setPadding(0, dp(10), 0, dp(2));
        return view;
    }

    private TextView body(String text) {
        return make(text, 15, 0xFFDDDDDD);
    }

    private TextView dim(String text) {
        TextView view = make(android.text.Html.fromHtml(text,
                android.text.Html.FROM_HTML_MODE_LEGACY).toString(), 13, 0xFF999999);
        return view;
    }

    private TextView mono(String text) {
        TextView view = make(text, 14, 0xFFDDDDDD);
        view.setTypeface(android.graphics.Typeface.MONOSPACE);
        return view;
    }

    private TextView make(String text, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setPadding(0, dp(3), 0, dp(3));
        return view;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        view.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.START;
        view.setLayoutParams(params);
        return view;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
