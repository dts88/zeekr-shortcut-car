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

import java.util.Locale;

/**
 * 预览画面矫正悬浮窗
 * 悬浮在主界面预览之上，用户可实时调节缩放/平移参数
 */
public class PreviewCorrectionFloatingWindow {
    private static final String TAG = "PreviewCorrectionFloating";

    private static final float SCALE_MIN = 0.50f;
    private static final float SCALE_MAX = 3.00f;
    private static final float SCALE_STEP = 0.01f;

    private static final float TRANSLATE_MIN = -1.50f;
    private static final float TRANSLATE_MAX = 1.50f;
    private static final float TRANSLATE_STEP = 0.01f;

    private final Context context;
    private final AppConfig appConfig;
    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams layoutParams;

    private Button btnCamFront, btnCamBack, btnCamLeft, btnCamRight;
    private SeekBar seekScaleX, seekScaleY, seekTranslateX, seekTranslateY;
    private TextView tvScaleX, tvScaleY, tvTranslateX, tvTranslateY;
    private String currentCameraPos = "front";

    private Runnable onDismissCallback;

    public PreviewCorrectionFloatingWindow(Context context) {
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

        floatingView = LayoutInflater.from(context).inflate(R.layout.view_preview_correction_floating, null);
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
        // 获取状态栏高度，避免悬浮窗被状态栏遮挡
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

        seekScaleX = floatingView.findViewById(R.id.seek_scale_x);
        seekScaleY = floatingView.findViewById(R.id.seek_scale_y);
        seekTranslateX = floatingView.findViewById(R.id.seek_translate_x);
        seekTranslateY = floatingView.findViewById(R.id.seek_translate_y);
        tvScaleX = floatingView.findViewById(R.id.tv_scale_x);
        tvScaleY = floatingView.findViewById(R.id.tv_scale_y);
        tvTranslateX = floatingView.findViewById(R.id.tv_translate_x);
        tvTranslateY = floatingView.findViewById(R.id.tv_translate_y);
    }

    private void initSeekBars() {
        seekScaleX.setMax((int) Math.round((SCALE_MAX - SCALE_MIN) / SCALE_STEP));
        seekScaleY.setMax((int) Math.round((SCALE_MAX - SCALE_MIN) / SCALE_STEP));
        seekTranslateX.setMax((int) Math.round((TRANSLATE_MAX - TRANSLATE_MIN) / TRANSLATE_STEP));
        seekTranslateY.setMax((int) Math.round((TRANSLATE_MAX - TRANSLATE_MIN) / TRANSLATE_STEP));
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
        Button closeButton = floatingView.findViewById(R.id.btn_close);
        closeButton.setOnClickListener(v -> dismiss());

        // 摄像头选择按钮
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
                if (seekBar == seekScaleX) {
                    float v = progressToScale(progress);
                    tvScaleX.setText(format2(v));
                    appConfig.setPreviewCorrectionScaleX(currentCameraPos, v);
                } else if (seekBar == seekScaleY) {
                    float v = progressToScale(progress);
                    tvScaleY.setText(format2(v));
                    appConfig.setPreviewCorrectionScaleY(currentCameraPos, v);
                } else if (seekBar == seekTranslateX) {
                    float v = progressToTranslate(progress);
                    tvTranslateX.setText(format2(v));
                    appConfig.setPreviewCorrectionTranslateX(currentCameraPos, v);
                } else if (seekBar == seekTranslateY) {
                    float v = progressToTranslate(progress);
                    tvTranslateY.setText(format2(v));
                    appConfig.setPreviewCorrectionTranslateY(currentCameraPos, v);
                } else {
                    return;
                }
                notifyMainActivityRefresh();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        };
        seekScaleX.setOnSeekBarChangeListener(seekListener);
        seekScaleY.setOnSeekBarChangeListener(seekListener);
        seekTranslateX.setOnSeekBarChangeListener(seekListener);
        seekTranslateY.setOnSeekBarChangeListener(seekListener);

        // 恢复当前摄像头默认
        Button resetButton = floatingView.findViewById(R.id.btn_reset);
        resetButton.setOnClickListener(v -> {
            appConfig.resetPreviewCorrection(currentCameraPos);
            loadFromConfig(currentCameraPos);
            notifyMainActivityRefresh();
        });

        // 全部重置
        Button resetAllButton = floatingView.findViewById(R.id.btn_reset_all);
        resetAllButton.setOnClickListener(v -> {
            appConfig.resetAllPreviewCorrection();
            loadFromConfig(currentCameraPos);
            notifyMainActivityRefresh();
        });
    }

    private void selectCamera(String cameraPos) {
        if (cameraPos.equals(currentCameraPos)) return;
        currentCameraPos = cameraPos;
        updateCameraButtonStyles();
        loadFromConfig(currentCameraPos);
    }

    /**
     * 高亮当前选中的摄像头按钮，其余恢复普通样式
     */
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
        float scaleX = appConfig.getPreviewCorrectionScaleX(cameraPos);
        float scaleY = appConfig.getPreviewCorrectionScaleY(cameraPos);
        float translateX = appConfig.getPreviewCorrectionTranslateX(cameraPos);
        float translateY = appConfig.getPreviewCorrectionTranslateY(cameraPos);

        seekScaleX.setProgress(scaleToProgress(scaleX));
        seekScaleY.setProgress(scaleToProgress(scaleY));
        seekTranslateX.setProgress(translateToProgress(translateX));
        seekTranslateY.setProgress(translateToProgress(translateY));

        tvScaleX.setText(format2(scaleX));
        tvScaleY.setText(format2(scaleY));
        tvTranslateX.setText(format2(translateX));
        tvTranslateY.setText(format2(translateY));
    }

    private void notifyMainActivityRefresh() {
        MainActivity mainActivity = MainActivity.getInstance();
        if (mainActivity != null) {
            mainActivity.refreshPreviewCorrection();
        }
    }

    private int scaleToProgress(float scale) {
        float clamped = Math.max(SCALE_MIN, Math.min(SCALE_MAX, scale));
        return (int) Math.round((clamped - SCALE_MIN) / SCALE_STEP);
    }

    private float progressToScale(int progress) {
        return SCALE_MIN + progress * SCALE_STEP;
    }

    private int translateToProgress(float translate) {
        float clamped = Math.max(TRANSLATE_MIN, Math.min(TRANSLATE_MAX, translate));
        return (int) Math.round((clamped - TRANSLATE_MIN) / TRANSLATE_STEP);
    }

    private float progressToTranslate(int progress) {
        return TRANSLATE_MIN + progress * TRANSLATE_STEP;
    }

    private String format2(float v) {
        return String.format(Locale.US, "%.2f", v);
    }
}
