package com.kooo.evcam;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PixelFormat;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;

import java.util.Locale;

/**
 * 鱼眼矫正参数调节悬浮窗
 * 悬浮在主界面预览之上，用户可实时调节鱼眼矫正参数
 */
public class FisheyeCorrectionFloatingWindow {
    private static final String TAG = "FisheyeCorrectionFloat";

    // K1 范围：-2.0 ~ 2.0，步长 0.01
    private static final float K1_MIN = -2.00f;
    private static final float K1_MAX = 2.00f;
    private static final float K1_STEP = 0.01f;

    // K2 范围：-2.0 ~ 2.0，步长 0.01
    private static final float K2_MIN = -2.00f;
    private static final float K2_MAX = 2.00f;
    private static final float K2_STEP = 0.01f;

    // Zoom 范围：0.50 ~ 3.00，步长 0.01
    private static final float ZOOM_MIN = 0.50f;
    private static final float ZOOM_MAX = 3.00f;
    private static final float ZOOM_STEP = 0.01f;

    // Center 范围：0.00 ~ 1.00，步长 0.01
    private static final float CENTER_MIN = 0.00f;
    private static final float CENTER_MAX = 1.00f;
    private static final float CENTER_STEP = 0.01f;

    private final Context context;
    private final AppConfig appConfig;
    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams layoutParams;

    private Button btnCamFront, btnCamBack, btnCamLeft, btnCamRight;
    private SeekBar seekK1, seekK2, seekZoom, seekCenterX, seekCenterY;
    private TextView tvK1, tvK2, tvZoom, tvCenterX, tvCenterY;
    private String currentCameraPos = "front";

    private Runnable onDismissCallback;

    public FisheyeCorrectionFloatingWindow(Context context) {
        this.context = context;
        this.appConfig = new AppConfig(context);
    }

    public void setOnDismissCallback(Runnable callback) {
        this.onDismissCallback = callback;
    }

    public void show() {
        if (floatingView != null) return;
        if (!WakeUpHelper.hasOverlayPermission(context)) return;

        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return;

        floatingView = LayoutInflater.from(context).inflate(R.layout.view_fisheye_correction_floating, null);
        initViews();
        initSeekBars();
        loadFromConfig(currentCameraPos);
        updateCameraButtonStyles();
        setupListeners();

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        layoutParams.x = 50;
        int statusBarHeight = 0;
        int resId = context.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            statusBarHeight = context.getResources().getDimensionPixelSize(resId);
        }
        layoutParams.y = statusBarHeight + 20;

        try {
            windowManager.addView(floatingView, layoutParams);
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to show floating window: " + e.getMessage());
            floatingView = null;
        }
    }

    public void dismiss() {
        if (windowManager != null && floatingView != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                // Ignore
            }
        }
        floatingView = null;
        windowManager = null;
        if (onDismissCallback != null) {
            onDismissCallback.run();
        }
    }

    public boolean isShowing() {
        return floatingView != null;
    }

    private void initViews() {
        btnCamFront = floatingView.findViewById(R.id.btn_cam_front);
        btnCamBack = floatingView.findViewById(R.id.btn_cam_back);
        btnCamLeft = floatingView.findViewById(R.id.btn_cam_left);
        btnCamRight = floatingView.findViewById(R.id.btn_cam_right);

        seekK1 = floatingView.findViewById(R.id.seek_k1);
        seekK2 = floatingView.findViewById(R.id.seek_k2);
        seekZoom = floatingView.findViewById(R.id.seek_zoom);
        seekCenterX = floatingView.findViewById(R.id.seek_center_x);
        seekCenterY = floatingView.findViewById(R.id.seek_center_y);

        tvK1 = floatingView.findViewById(R.id.tv_k1);
        tvK2 = floatingView.findViewById(R.id.tv_k2);
        tvZoom = floatingView.findViewById(R.id.tv_zoom);
        tvCenterX = floatingView.findViewById(R.id.tv_center_x);
        tvCenterY = floatingView.findViewById(R.id.tv_center_y);
    }

    private void initSeekBars() {
        seekK1.setMax(Math.round((K1_MAX - K1_MIN) / K1_STEP));
        seekK2.setMax(Math.round((K2_MAX - K2_MIN) / K2_STEP));
        seekZoom.setMax(Math.round((ZOOM_MAX - ZOOM_MIN) / ZOOM_STEP));
        seekCenterX.setMax(Math.round((CENTER_MAX - CENTER_MIN) / CENTER_STEP));
        seekCenterY.setMax(Math.round((CENTER_MAX - CENTER_MIN) / CENTER_STEP));
    }

    private void setupListeners() {
        // 拖动
        TextView dragHandle = floatingView.findViewById(R.id.tv_drag_handle);
        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (layoutParams == null || windowManager == null || floatingView == null) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        layoutParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        layoutParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        try {
                            windowManager.updateViewLayout(floatingView, layoutParams);
                        } catch (Exception e) {
                            // Ignore
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        return true;
                }
                return false;
            }
        });

        // 关闭
        floatingView.findViewById(R.id.btn_close).setOnClickListener(v -> dismiss());

        // 摄像头选择
        View.OnClickListener camClickListener = v -> {
            if (v == btnCamFront) selectCamera("front");
            else if (v == btnCamBack) selectCamera("back");
            else if (v == btnCamLeft) selectCamera("left");
            else if (v == btnCamRight) selectCamera("right");
        };
        btnCamFront.setOnClickListener(camClickListener);
        btnCamBack.setOnClickListener(camClickListener);
        btnCamLeft.setOnClickListener(camClickListener);
        btnCamRight.setOnClickListener(camClickListener);

        // SeekBar 实时调节
        SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (seekBar == seekK1) {
                    float v = progressToK1(progress);
                    tvK1.setText(format2(v));
                    appConfig.setFisheyeCorrectionK1(currentCameraPos, v);
                } else if (seekBar == seekK2) {
                    float v = progressToK2(progress);
                    tvK2.setText(format2(v));
                    appConfig.setFisheyeCorrectionK2(currentCameraPos, v);
                } else if (seekBar == seekZoom) {
                    float v = progressToZoom(progress);
                    tvZoom.setText(format2(v));
                    appConfig.setFisheyeCorrectionZoom(currentCameraPos, v);
                } else if (seekBar == seekCenterX) {
                    float v = progressToCenter(progress);
                    tvCenterX.setText(format2(v));
                    appConfig.setFisheyeCorrectionCenterX(currentCameraPos, v);
                } else if (seekBar == seekCenterY) {
                    float v = progressToCenter(progress);
                    tvCenterY.setText(format2(v));
                    appConfig.setFisheyeCorrectionCenterY(currentCameraPos, v);
                } else {
                    return;
                }
                notifyCameraUpdateParams();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        seekK1.setOnSeekBarChangeListener(seekListener);
        seekK2.setOnSeekBarChangeListener(seekListener);
        seekZoom.setOnSeekBarChangeListener(seekListener);
        seekCenterX.setOnSeekBarChangeListener(seekListener);
        seekCenterY.setOnSeekBarChangeListener(seekListener);

        // 恢复当前摄像头默认
        floatingView.findViewById(R.id.btn_reset).setOnClickListener(v -> {
            appConfig.resetFisheyeCorrection(currentCameraPos);
            loadFromConfig(currentCameraPos);
            notifyCameraUpdateParams();
        });

        // 全部重置
        floatingView.findViewById(R.id.btn_reset_all).setOnClickListener(v -> {
            appConfig.resetAllFisheyeCorrection();
            loadFromConfig(currentCameraPos);
            notifyAllCamerasUpdateParams();
        });
    }

    private void selectCamera(String cameraPos) {
        if (cameraPos.equals(currentCameraPos)) return;
        currentCameraPos = cameraPos;
        updateCameraButtonStyles();
        loadFromConfig(currentCameraPos);
    }

    private void updateCameraButtonStyles() {
        ColorStateList accentTint = ContextCompat.getColorStateList(context, R.color.button_accent);
        ColorStateList normalTint = ContextCompat.getColorStateList(context, R.color.button_background);
        int whiteColor = ContextCompat.getColor(context, R.color.white);
        int normalTextColor = ContextCompat.getColor(context, R.color.button_text);

        btnCamFront.setBackgroundTintList("front".equals(currentCameraPos) ? accentTint : normalTint);
        btnCamFront.setTextColor("front".equals(currentCameraPos) ? whiteColor : normalTextColor);
        btnCamBack.setBackgroundTintList("back".equals(currentCameraPos) ? accentTint : normalTint);
        btnCamBack.setTextColor("back".equals(currentCameraPos) ? whiteColor : normalTextColor);
        btnCamLeft.setBackgroundTintList("left".equals(currentCameraPos) ? accentTint : normalTint);
        btnCamLeft.setTextColor("left".equals(currentCameraPos) ? whiteColor : normalTextColor);
        btnCamRight.setBackgroundTintList("right".equals(currentCameraPos) ? accentTint : normalTint);
        btnCamRight.setTextColor("right".equals(currentCameraPos) ? whiteColor : normalTextColor);
    }

    private void loadFromConfig(String cameraPos) {
        float k1 = appConfig.getFisheyeCorrectionK1(cameraPos);
        float k2 = appConfig.getFisheyeCorrectionK2(cameraPos);
        float zoom = appConfig.getFisheyeCorrectionZoom(cameraPos);
        float cx = appConfig.getFisheyeCorrectionCenterX(cameraPos);
        float cy = appConfig.getFisheyeCorrectionCenterY(cameraPos);

        seekK1.setProgress(k1ToProgress(k1));
        seekK2.setProgress(k2ToProgress(k2));
        seekZoom.setProgress(zoomToProgress(zoom));
        seekCenterX.setProgress(centerToProgress(cx));
        seekCenterY.setProgress(centerToProgress(cy));

        tvK1.setText(format2(k1));
        tvK2.setText(format2(k2));
        tvZoom.setText(format2(zoom));
        tvCenterX.setText(format2(cx));
        tvCenterY.setText(format2(cy));
    }

    /**
     * 通知当前摄像头实时更新鱼眼参数（无需重建 session）
     */
    private void notifyCameraUpdateParams() {
        MultiCameraManager cm = CameraManagerHolder.getInstance().getCameraManager();
        if (cm == null) return;
        SingleCamera camera = cm.getCamera(currentCameraPos);
        if (camera != null) {
            camera.updateFisheyeParams(appConfig);
        }
    }

    /**
     * 通知所有摄像头更新参数（全部重置时调用）
     */
    private void notifyAllCamerasUpdateParams() {
        MultiCameraManager cm = CameraManagerHolder.getInstance().getCameraManager();
        if (cm == null) return;
        String[] positions = {"front", "back", "left", "right"};
        for (String pos : positions) {
            SingleCamera camera = cm.getCamera(pos);
            if (camera != null) {
                camera.updateFisheyeParams(appConfig);
            }
        }
    }

    // ===== 进度条 ↔ 数值转换 =====

    private int k1ToProgress(float k1) {
        float clamped = Math.max(K1_MIN, Math.min(K1_MAX, k1));
        return Math.round((clamped - K1_MIN) / K1_STEP);
    }

    private float progressToK1(int progress) {
        return K1_MIN + progress * K1_STEP;
    }

    private int k2ToProgress(float k2) {
        float clamped = Math.max(K2_MIN, Math.min(K2_MAX, k2));
        return Math.round((clamped - K2_MIN) / K2_STEP);
    }

    private float progressToK2(int progress) {
        return K2_MIN + progress * K2_STEP;
    }

    private int zoomToProgress(float zoom) {
        float clamped = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom));
        return Math.round((clamped - ZOOM_MIN) / ZOOM_STEP);
    }

    private float progressToZoom(int progress) {
        return ZOOM_MIN + progress * ZOOM_STEP;
    }

    private int centerToProgress(float center) {
        float clamped = Math.max(CENTER_MIN, Math.min(CENTER_MAX, center));
        return Math.round((clamped - CENTER_MIN) / CENTER_STEP);
    }

    private float progressToCenter(int progress) {
        return CENTER_MIN + progress * CENTER_STEP;
    }

    private String format2(float v) {
        return String.format(Locale.US, "%.2f", v);
    }
}
