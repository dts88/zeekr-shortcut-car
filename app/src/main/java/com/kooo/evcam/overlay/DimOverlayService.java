package com.kooo.evcam.overlay;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.UserExit;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.service.RecordingFloatingService;

/**
 * Full-screen dim that sits on top of other apps the same way Super mirror does:
 * a {@code TYPE_APPLICATION_OVERLAY} window. Touches pass through by default so
 * maps still works. The floating button is raised back above this window.
 */
public class DimOverlayService extends Service {

    private static final String TAG = "DimOverlayService";

    public static final String ACTION_SHOW = "com.kooo.evcam.action.SHOW_DIM_OVERLAY";
    public static final String ACTION_HIDE = "com.kooo.evcam.action.HIDE_DIM_OVERLAY";
    public static final String ACTION_APPLY = "com.kooo.evcam.action.APPLY_DIM_OVERLAY";

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WindowManager windowManager;
    private View shade;
    private WindowManager.LayoutParams layoutParams;

    public static void show(Context context) {
        start(context, ACTION_SHOW);
    }

    public static void hide(Context context) {
        start(context, ACTION_HIDE);
    }

    public static void apply(Context context) {
        start(context, ACTION_APPLY);
    }

    private static void start(Context context, String action) {
        Intent intent = new Intent(context, DimOverlayService.class);
        intent.setAction(action);
        context.startService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (UserExit.blocks(this, "DimOverlayService")) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent != null && intent.getAction() != null
                ? intent.getAction() : ACTION_SHOW;
        mainHandler.post(() -> dispatch(action));
        return START_NOT_STICKY;
    }

    private void dispatch(String action) {
        if (ACTION_HIDE.equals(action)) {
            detach();
            stopSelf();
            return;
        }
        AppConfig config = new AppConfig(this);
        if (!config.isDimOverlayEnabled()) {
            detach();
            stopSelf();
            return;
        }
        if (!WakeUpHelper.hasOverlayPermission(this)) {
            AppLog.e(TAG, "没有悬浮窗权限，遮罩不开");
            stopSelf();
            return;
        }
        if (shade == null) {
            attach(config);
        } else {
            paint(config);
        }
        raiseFloatingButton();
    }

    private void attach(AppConfig config) {
        shade = new View(this);
        shade.setClickable(false);
        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                overlayType(),
                flags(config),
                PixelFormat.TRANSLUCENT);
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        Point real = realSize();
        layoutParams.width = real.x;
        layoutParams.height = real.y;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            layoutParams.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        shade.setBackgroundColor(color(config));
        try {
            windowManager.addView(shade, layoutParams);
            AppLog.i(TAG, "屏幕遮罩已打开 " + real.x + "x" + real.y);
        } catch (Exception e) {
            AppLog.e(TAG, "屏幕遮罩添加失败", e);
            shade = null;
            stopSelf();
        }
    }

    private void paint(AppConfig config) {
        if (shade == null || layoutParams == null) {
            return;
        }
        Point real = realSize();
        layoutParams.width = real.x;
        layoutParams.height = real.y;
        layoutParams.flags = flags(config);
        shade.setBackgroundColor(color(config));
        try {
            windowManager.updateViewLayout(shade, layoutParams);
        } catch (Exception e) {
            AppLog.e(TAG, "屏幕遮罩更新失败", e);
        }
    }

    private void detach() {
        if (shade != null && windowManager != null) {
            try {
                windowManager.removeView(shade);
            } catch (Exception e) {
                AppLog.e(TAG, "屏幕遮罩移除失败", e);
            }
        }
        shade = null;
        layoutParams = null;
    }

    /** The record button was added first, so this window would cover it. Put the button back on top. */
    private void raiseFloatingButton() {
        if (!new AppConfig(this).isRecordingFloatingEnabled()) {
            return;
        }
        Intent raise = new Intent(this, RecordingFloatingService.class);
        raise.setAction(RecordingFloatingService.ACTION_RAISE);
        try {
            startService(raise);
        } catch (Exception e) {
            AppLog.e(TAG, "悬浮按钮置顶失败", e);
        }
    }

    private int color(AppConfig config) {
        return DimShade.argb(config.getDimOpacity(), config.getDimBrightness(), config.getDimWarmth());
    }

    private int flags(AppConfig config) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (config.isDimPassThrough()) {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        return flags;
    }

    private int overlayType() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        }
        return WindowManager.LayoutParams.TYPE_PHONE;
    }

    private Point realSize() {
        Point point = new Point();
        windowManager.getDefaultDisplay().getRealSize(point);
        if (point.x <= 0 || point.y <= 0) {
            point.x = getResources().getDisplayMetrics().widthPixels;
            point.y = getResources().getDisplayMetrics().heightPixels;
        }
        return point;
    }

    @Override
    public void onDestroy() {
        detach();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
