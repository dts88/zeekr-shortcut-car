package com.kooo.evcam.zeekr;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.util.TypedValue;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.FrameRateMeter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 分辨率实测（开发者选项）。
 *
 * <h3>为什么要真的开一次</h3>
 *
 * <p>相机<b>声明</b>支持某个尺寸，不等于它在这台车机上真的给得出画面。
 * 尤其是 EXTERNAL 那一路 —— 它不是一颗传感器，而是车机送进来的一路既成视频，
 * 声明里那一串常规尺寸很可能只是 HAL 的模板。</p>
 *
 * <p>所以这一屏对每个尺寸<b>真的配置一次会话、真的出一次画面</b>：
 * 出得来、能数到帧，才算这个声明是实的。</p>
 *
 * <h3>它会占用相机</h3>
 *
 * <p>一台相机同一时刻只能被打开一次，所以测试开始前必须先把应用自己的相机放掉。
 * 测完回主界面会重新初始化。<b>正在录制时不要进来。</b></p>
 */
public class ResolutionTestActivity extends Activity {

    private static final String TAG = "ResolutionTest";

    /** 每个尺寸测多久。够数出几十帧，又不至于把一轮全量测试拖成几分钟。 */
    private static final long PER_SIZE_MS = 3000L;
    /** 打开相机 / 配置会话的等待上限。 */
    private static final long OPEN_TIMEOUT_MS = 5000L;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private Spinner cameraSpinner;
    private Spinner sizeSpinner;
    private TextView status;
    private TextView results;
    private TextureView preview;

    private CameraManager cameraManager;
    private List<String> cameraIds = new ArrayList<>();
    private List<Size> sizes = new ArrayList<>();

    private CameraDevice device;
    private CameraCaptureSession session;
    private final FrameRateMeter meter = new FrameRateMeter(1000L);
    private volatile boolean runningBatch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cameraThread = new HandlerThread("resolution-test");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        setContentView(buildLayout());
        loadCameras();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        runningBatch = false;
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

        root.addView(button("← 返回", v -> finish()));
        root.addView(text("分辨率实测", 20, Color.WHITE));
        root.addView(text("相机声明支持某个尺寸 ≠ 它真的给得出画面。这里对每个尺寸"
                + "真的配置一次会话、真的出一次画面 —— 出得来、能数到帧，才算这个声明是实的。\n"
                + "测试会占用相机，正在录制时不要进来；测完回主界面会自动重新初始化。",
                13, 0xFF999999));

        cameraSpinner = new Spinner(this);
        root.addView(labeled("相机", cameraSpinner));
        sizeSpinner = new Spinner(this);
        root.addView(labeled("尺寸", sizeSpinner));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.addView(button("测这一个", v -> testOne()));
        buttons.addView(button("全部依次测", v -> testAll()));
        buttons.addView(button("停止", v -> {
            runningBatch = false;
            closeCamera();
            setStatus("已停止");
        }));
        root.addView(buttons);

        status = text("", 15, 0xFFDDDDDD);
        root.addView(status);

        preview = new TextureView(this);
        LinearLayout.LayoutParams previewParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(220));
        previewParams.topMargin = dp(8);
        preview.setLayoutParams(previewParams);
        root.addView(preview);

        results = text("", 14, 0xFFDDDDDD);
        results.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(results);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private View labeled(String label, View field) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView view = text(label + "  ", 15, 0xFFDDDDDD);
        row.addView(view);
        field.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(field);
        return row;
    }

    private TextView text(String content, int sizeSp, int color) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setPadding(0, dp(4), 0, dp(4));
        return view;
    }

    private Button button(String label, View.OnClickListener listener) {
        Button view = new Button(this);
        view.setText(label);
        view.setAllCaps(false);
        view.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        view.setLayoutParams(params);
        return view;
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }

    // ------------------------------------------------------------------ 数据

    private void loadCameras() {
        if (cameraManager == null) {
            setStatus("拿不到 CameraManager");
            return;
        }
        try {
            Collections.addAll(cameraIds, cameraManager.getCameraIdList());
        } catch (CameraAccessException | RuntimeException e) {
            setStatus("枚举相机失败: " + e);
            return;
        }
        cameraSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, cameraIds));
        cameraSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                loadSizes(cameraIds.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        if (!cameraIds.isEmpty()) {
            loadSizes(cameraIds.get(0));
        }
    }

    private void loadSizes(String cameraId) {
        sizes.clear();
        try {
            CameraCharacteristics cc = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map =
                    cc.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                Size[] available = map.getOutputSizes(SurfaceTexture.class);
                if (available != null) {
                    Collections.addAll(sizes, available);
                }
            }
        } catch (CameraAccessException | RuntimeException e) {
            setStatus("读特性失败: " + e);
        }
        Collections.sort(sizes, (a, b) ->
                b.getWidth() * b.getHeight() - a.getWidth() * a.getHeight());

        List<String> labels = new ArrayList<>();
        for (Size size : sizes) {
            labels.add(String.format(Locale.US, "%d×%d  (%s)",
                    size.getWidth(), size.getHeight(), CameraProbeActivity.ratioOf(size)));
        }
        sizeSpinner.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, labels));
    }

    // ------------------------------------------------------------------ 测试

    private void testOne() {
        int cameraPos = cameraSpinner.getSelectedItemPosition();
        int sizePos = sizeSpinner.getSelectedItemPosition();
        if (cameraPos < 0 || sizePos < 0 || sizePos >= sizes.size()) {
            return;
        }
        results.setText("");
        runInBackground(cameraIds.get(cameraPos), Collections.singletonList(sizes.get(sizePos)));
    }

    private void testAll() {
        int cameraPos = cameraSpinner.getSelectedItemPosition();
        if (cameraPos < 0 || sizes.isEmpty()) {
            return;
        }
        results.setText("");
        runInBackground(cameraIds.get(cameraPos), new ArrayList<>(sizes));
    }

    private void runInBackground(String cameraId, List<Size> toTest) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            setStatus("没有相机权限");
            return;
        }
        // 一台相机同一时刻只能被打开一次 —— 先把应用自己那份放掉，
        // 否则每一个尺寸都会以「相机被占用」失败，看起来像声明全是假的
        CameraManagerHolder.getInstance().release();

        runningBatch = true;
        new Thread(() -> {
            for (Size size : toTest) {
                if (!runningBatch) {
                    break;
                }
                String line = tryOne(cameraId, size);
                ui.post(() -> results.append(line + "\n"));
            }
            runningBatch = false;
            ui.post(() -> setStatus("测试结束。回主界面会重新初始化相机。"));
        }, "resolution-batch").start();
    }

    /**
     * 开一次、看有没有画面。
     *
     * @return 一行结果
     */
    private String tryOne(String cameraId, Size size) {
        ui.post(() -> setStatus("正在测 " + size.getWidth() + "×" + size.getHeight() + " …"));
        meter.reset();
        long start = android.os.SystemClock.elapsedRealtime();
        try {
            openAndPreview(cameraId, size);
        } catch (Exception e) {
            closeCamera();
            AppLog.w(TAG, size + " 失败: " + e);
            return String.format(Locale.US, "%-12s 失败  %s",
                    size.getWidth() + "×" + size.getHeight(), shortReason(e));
        }
        // 让它跑一会儿，数帧
        sleep(PER_SIZE_MS);
        float fps = meter.fps();
        long frames = meter.totalFrames();
        closeCamera();

        long elapsed = android.os.SystemClock.elapsedRealtime() - start;
        if (frames == 0) {
            return String.format(Locale.US, "%-12s 无画面  会话建起来了但一帧都没来（%d ms）",
                    size.getWidth() + "×" + size.getHeight(), elapsed);
        }
        return String.format(Locale.US, "%-12s 正常  %.1f fps，%d 帧",
                size.getWidth() + "×" + size.getHeight(), fps, frames);
    }

    private void openAndPreview(String cameraId, Size size) throws Exception {
        SurfaceTexture texture = waitForTexture();
        texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
        Surface surface = new Surface(texture);

        final Object lock = new Object();
        final Exception[] failure = new Exception[1];
        final boolean[] ready = new boolean[1];

        cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
            @Override
            public void onOpened(CameraDevice camera) {
                device = camera;
                try {
                    camera.createCaptureSession(Collections.singletonList(surface),
                            new CameraCaptureSession.StateCallback() {
                                @Override
                                public void onConfigured(CameraCaptureSession configured) {
                                    session = configured;
                                    try {
                                        CaptureRequest.Builder builder = camera.createCaptureRequest(
                                                CameraDevice.TEMPLATE_PREVIEW);
                                        builder.addTarget(surface);
                                        configured.setRepeatingRequest(
                                                builder.build(), null, cameraHandler);
                                    } catch (Exception e) {
                                        failure[0] = e;
                                    }
                                    signal(lock, ready);
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
                failure[0] = new IllegalStateException("相机断开");
                signal(lock, ready);
            }

            @Override
            public void onError(CameraDevice camera, int error) {
                failure[0] = new IllegalStateException("打开失败，错误码 " + error);
                signal(lock, ready);
            }
        }, cameraHandler);

        synchronized (lock) {
            if (!ready[0]) {
                lock.wait(OPEN_TIMEOUT_MS);
            }
        }
        if (!ready[0]) {
            throw new IllegalStateException("超时");
        }
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    private static void signal(Object lock, boolean[] ready) {
        synchronized (lock) {
            ready[0] = true;
            lock.notifyAll();
        }
    }

    /** TextureView 可能还没准备好；等它，等不到就没法测。 */
    private SurfaceTexture waitForTexture() throws Exception {
        for (int i = 0; i < 50; i++) {
            SurfaceTexture texture = preview.getSurfaceTexture();
            if (texture != null) {
                texture.setOnFrameAvailableListener(t ->
                        meter.onFrame(android.os.SystemClock.elapsedRealtime()), cameraHandler);
                return texture;
            }
            sleep(100);
        }
        throw new IllegalStateException("预览视图没有就绪");
    }

    private void closeCamera() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
                // 关不掉也要继续往下清
            }
            session = null;
        }
        if (device != null) {
            try {
                device.close();
            } catch (Exception ignored) {
                // 同上
            }
            device = null;
        }
    }

    private static String shortReason(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return message.length() > 60 ? message.substring(0, 60) + "…" : message;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void setStatus(String text) {
        if (status != null) {
            ui.post(() -> status.setText(text));
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}
