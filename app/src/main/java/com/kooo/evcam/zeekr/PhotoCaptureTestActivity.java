package com.kooo.evcam.zeekr;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.ImageFormat;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.kooo.evcam.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 拍照通道实测：每一路相机、每一个声明的 JPEG 尺寸，真的拍一张。
 *
 * <h3>为什么要单独测这个</h3>
 *
 * <p>现在的照片是从 {@code TextureView} 抓预览画面得来的 —— 那是一张<b>屏幕截图</b>，
 * 分辨率被预览缓冲区卡住，也没有任何 EXIF。要换成相机自己的 JPEG 输出通道，
 * 得先确认两件事：</p>
 *
 * <ol>
 *   <li>每一路、每个声明的尺寸，JPEG 通道<b>是不是真的出图</b>；</li>
 *   <li>出来的图里<b>带了哪些标签</b>（尺寸、时间、曝光、焦距、机型…）。</li>
 * </ol>
 *
 * <p>诊断报告里三路相机的 JPEG 尺寸列表和预览列表完全一致，但「声明支持」和
 * 「真能出图」是两回事 —— 帧率那件事已经教过一次了。</p>
 *
 * <h3>这个界面不动录制</h3>
 *
 * <p>它自己开相机、自己关。录制中进来会抢不到相机，测试结果会是「打不开」，
 * 那不是相机的问题。测之前先停录制。</p>
 */
public class PhotoCaptureTestActivity extends Activity {

    private static final String TAG = "PhotoCaptureTest";

    /** 打开相机 / 配置会话 / 等一张图的上限。 */
    private static final long TIMEOUT_MS = 6000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private TextView status;
    private TextView results;
    private Button runButton;

    private CameraManager cameraManager;
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private volatile boolean running;

    /** 存一张样片的目录；只留最后一次测试的结果。 */
    private File sampleDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraThread = new HandlerThread("photo-capture-test");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        sampleDir = new File(getExternalFilesDir(null), "photo-test");
        setContentView(buildLayout());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        closeCamera();
        if (cameraThread != null) {
            cameraThread.quitSafely();
        }
    }

    // ------------------------------------------------------------------ 界面

    private View buildLayout() {
        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFF1A1A1A);

        // 车机没有系统返回键，这个界面必须自带退路
        root.addView(button("← 返回", v -> finish()));
        root.addView(title("拍照通道实测"));
        root.addView(hint("每一路相机、每一个声明的 JPEG 尺寸，真的拍一张，"
                + "记下能不能出图、图有多大、带了哪些标签。\n"
                + "会占用相机，录制中请先停止录制再测。"));

        runButton = button("开始测试（全部相机 × 全部尺寸）", v -> startTest());
        root.addView(runButton);
        root.addView(button("打开样片目录路径", v -> toastPath()));

        status = hint("");
        root.addView(status);

        results = new TextView(this);
        results.setTextColor(0xFFDDDDDD);
        results.setTextSize(13f);
        results.setTypeface(android.graphics.Typeface.MONOSPACE);
        results.setPadding(0, dp(8), 0, 0);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(results);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private TextView title(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xFFFFFFFF);
        view.setTextSize(20f);
        view.setPadding(0, dp(8), 0, dp(4));
        return view;
    }

    private TextView hint(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(0xFFAAAAAA);
        view.setTextSize(13f);
        view.setPadding(0, dp(4), 0, dp(8));
        return view;
    }

    private Button button(String text, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(text);
        view.setAllCaps(false);
        view.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(6);
        view.setLayoutParams(params);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density);
    }

    private void toastPath() {
        append("样片目录：" + sampleDir.getAbsolutePath());
    }

    // ------------------------------------------------------------------ 测试

    private void startTest() {
        if (running) {
            return;
        }
        running = true;
        runButton.setEnabled(false);
        results.setText("");
        if (!sampleDir.exists() && !sampleDir.mkdirs()) {
            AppLog.w(TAG, "样片目录建不了: " + sampleDir);
        }
        new Thread(this::runAll, "photo-capture-test").start();
    }

    private void runAll() {
        try {
            String[] ids = cameraManager.getCameraIdList();
            for (String id : ids) {
                if (!running) {
                    break;
                }
                List<Size> sizes = jpegSizes(id);
                append("");
                append("=== 相机 " + id + " · " + facingOf(id)
                        + " · 声明 " + sizes.size() + " 个 JPEG 尺寸 ===");
                for (Size size : sizes) {
                    if (!running) {
                        break;
                    }
                    setStatus("相机 " + id + " · " + size + " …");
                    append(testOne(id, size));
                }
            }
            append("");
            append("样片存在：" + sampleDir.getAbsolutePath());
        } catch (Exception e) {
            AppLog.e(TAG, "测试出错", e);
            append("测试中断：" + e);
        } finally {
            closeCamera();
            running = false;
            ui.post(() -> {
                runButton.setEnabled(true);
                setStatus("完成");
            });
        }
    }

    /** 拍一张，返回一行结果。 */
    private String testOne(String cameraId, Size size) {
        long started = System.currentTimeMillis();
        try {
            byte[] jpeg = captureOnce(cameraId, size);
            long tookMs = System.currentTimeMillis() - started;
            if (jpeg == null || jpeg.length == 0) {
                return String.format(Locale.US, "%-12s 失败  没有拿到数据", text(size));
            }

            // 解码只读尺寸，不真的把整张图读进内存
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);

            File sample = new File(sampleDir,
                    "cam" + cameraId + "_" + size.getWidth() + "x" + size.getHeight() + ".jpg");
            try (FileOutputStream out = new FileOutputStream(sample)) {
                out.write(jpeg);
            }

            String actual = bounds.outWidth + "x" + bounds.outHeight;
            String matches = (bounds.outWidth == size.getWidth()
                    && bounds.outHeight == size.getHeight()) ? "" : "  << 实际尺寸不符";
            return String.format(Locale.US, "%-12s 正常  %s  %.0fKB  %dms%s%n     标签: %s",
                    text(size), actual, jpeg.length / 1024f, tookMs, matches, readTags(sample));
        } catch (Exception e) {
            String reason = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : "：" + e.getMessage());
            return String.format(Locale.US, "%-12s 失败  %s", text(size), reason);
        } finally {
            closeCamera();
        }
    }

    private static String text(Size size) {
        return size.getWidth() + "x" + size.getHeight();
    }

    /**
     * 开相机 → 建会话（只挂一个 JPEG 输出）→ 拍一张 → 收工。
     *
     * <p>用 {@code TEMPLATE_STILL_CAPTURE} 而不是预览模板：那是相机厂商为静态
     * 拍照调过的一套参数（曝光、降噪、锐化），也是我们最终要走的通道。</p>
     */
    private byte[] captureOnce(String cameraId, Size size) throws Exception {
        reader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                ImageFormat.JPEG, 2);
        final byte[][] result = new byte[1][];
        final Object lock = new Object();
        final boolean[] done = new boolean[1];

        reader.setOnImageAvailableListener(r -> {
            try (Image image = r.acquireNextImage()) {
                if (image != null) {
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    result[0] = bytes;
                }
            } catch (Exception e) {
                AppLog.w(TAG, "读图失败: " + e);
            }
            signal(lock, done);
        }, cameraHandler);

        openAndCapture(cameraId, reader.getSurface());

        synchronized (lock) {
            if (!done[0]) {
                lock.wait(TIMEOUT_MS);
            }
        }
        if (result[0] == null) {
            throw new IllegalStateException("等了 " + (TIMEOUT_MS / 1000) + " 秒没有出图");
        }
        return result[0];
    }

    private void openAndCapture(String cameraId, Surface target) throws Exception {
        final Object lock = new Object();
        final boolean[] ready = new boolean[1];
        final Exception[] failure = new Exception[1];

        cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                device = camera;
                try {
                    List<Surface> targets = new ArrayList<>();
                    targets.add(target);
                    camera.createCaptureSession(targets, new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession configured) {
                            session = configured;
                            try {
                                CaptureRequest.Builder builder = camera.createCaptureRequest(
                                        CameraDevice.TEMPLATE_STILL_CAPTURE);
                                builder.addTarget(target);
                                builder.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                                configured.capture(builder.build(),
                                        new CameraCaptureSession.CaptureCallback() {
                                            @Override
                                            public void onCaptureFailed(
                                                    CameraCaptureSession s,
                                                    CaptureRequest request,
                                                    android.hardware.camera2.CaptureFailure f) {
                                                failure[0] = new IllegalStateException(
                                                        "拍摄失败，reason=" + f.getReason());
                                                signal(lock, ready);
                                            }

                                            @Override
                                            public void onCaptureCompleted(
                                                    CameraCaptureSession s,
                                                    CaptureRequest request,
                                                    TotalCaptureResult res) {
                                                signal(lock, ready);
                                            }
                                        }, cameraHandler);
                            } catch (Exception e) {
                                failure[0] = e;
                                signal(lock, ready);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession configured) {
                            failure[0] = new IllegalStateException("会话配置失败");
                            signal(lock, ready);
                        }
                    }, cameraHandler);
                } catch (Exception e) {
                    failure[0] = e;
                    signal(lock, ready);
                }
            }

            @Override
            public void onDisconnected(CameraDevice camera) {
                failure[0] = new IllegalStateException("相机被断开（可能被别的应用占用）");
                signal(lock, ready);
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                failure[0] = new IllegalStateException("相机错误 " + error);
                signal(lock, ready);
            }
        }, cameraHandler);

        synchronized (lock) {
            if (!ready[0]) {
                lock.wait(TIMEOUT_MS);
            }
        }
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    private static void signal(Object lock, boolean[] flag) {
        synchronized (lock) {
            flag[0] = true;
            lock.notifyAll();
        }
    }

    private void closeCamera() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
                // 关不上就算了，下面还会把设备关掉
            }
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }

    // ------------------------------------------------------------------ 标签

    /**
     * 读出这张图里带了哪些 EXIF 标签。
     *
     * <p>只列<b>真的有值</b>的那些 —— 目的是看清相机往里写了什么，
     * 而不是打印一张空表。</p>
     */
    private String readTags(File file) {
        String[] tags = {
                ExifInterface.TAG_IMAGE_WIDTH, ExifInterface.TAG_IMAGE_LENGTH,
                ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL,
                ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL,
                ExifInterface.TAG_ORIENTATION, ExifInterface.TAG_EXPOSURE_TIME,
                ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_ISO_SPEED_RATINGS,
                ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_WHITE_BALANCE,
                ExifInterface.TAG_FLASH, ExifInterface.TAG_GPS_LATITUDE,
        };
        StringBuilder sb = new StringBuilder();
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            for (String tag : tags) {
                String value = exif.getAttribute(tag);
                if (value != null && !value.isEmpty()) {
                    if (sb.length() > 0) {
                        sb.append(", ");
                    }
                    sb.append(shortName(tag)).append('=').append(value);
                }
            }
        } catch (Exception e) {
            return "读不出来（" + e.getClass().getSimpleName() + "）";
        }
        return sb.length() == 0 ? "一个都没有（相机没往 JPEG 里写 EXIF）" : sb.toString();
    }

    /** {@code DateTimeOriginal} 这种长名字在车机屏上太占地方。 */
    private static String shortName(String tag) {
        switch (tag) {
            case ExifInterface.TAG_DATETIME_ORIGINAL:
                return "拍摄时间";
            case ExifInterface.TAG_DATETIME:
                return "时间";
            case ExifInterface.TAG_MAKE:
                return "厂商";
            case ExifInterface.TAG_MODEL:
                return "机型";
            case ExifInterface.TAG_EXPOSURE_TIME:
                return "曝光";
            case ExifInterface.TAG_ISO_SPEED_RATINGS:
                return "ISO";
            case ExifInterface.TAG_FOCAL_LENGTH:
                return "焦距";
            case ExifInterface.TAG_ORIENTATION:
                return "方向";
            default:
                return tag;
        }
    }

    // ------------------------------------------------------------------ 相机信息

    private List<Size> jpegSizes(String cameraId) throws CameraAccessException {
        List<Size> out = new ArrayList<>();
        StreamConfigurationMap map = cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return out;
        }
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null) {
            return out;
        }
        for (Size size : sizes) {
            out.add(size);
        }
        // 从大到小：最想确认的是最大那个能不能出图
        java.util.Collections.sort(out, (a, b) -> Long.compare(
                (long) b.getWidth() * b.getHeight(), (long) a.getWidth() * a.getHeight()));
        return out;
    }

    private String facingOf(String cameraId) {
        try {
            Integer facing = cameraManager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.LENS_FACING);
            if (facing == null) {
                return "未知";
            }
            switch (facing) {
                case CameraCharacteristics.LENS_FACING_FRONT:
                    return "FRONT";
                case CameraCharacteristics.LENS_FACING_BACK:
                    return "BACK";
                case CameraCharacteristics.LENS_FACING_EXTERNAL:
                    return "EXTERNAL（环视合成流）";
                default:
                    return String.valueOf(facing);
            }
        } catch (Exception e) {
            return "读不到";
        }
    }

    // ------------------------------------------------------------------ 输出

    private void setStatus(String text) {
        ui.post(() -> status.setText(text));
    }

    private void append(String line) {
        AppLog.i(TAG, line);
        ui.post(() -> results.append(line + "\n"));
    }
}
