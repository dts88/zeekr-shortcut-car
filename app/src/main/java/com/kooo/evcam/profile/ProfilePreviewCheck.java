package com.kooo.evcam.profile;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.zeekr.FourLaneContainer;

import java.util.ArrayList;
import java.util.List;

/**
 * 保存配置前，把画面真的开出来看一眼。
 *
 * <h3>为什么是看画面，不是看一个帧率数字</h3>
 *
 * <p>原来这里采一段帧率再把数字摆出来。问题是：编辑界面是另一个 Activity，
 * 主界面已经退到后台、相机已经被关掉了 —— 采出来必然是 0，那个数字从来没有过意义。</p>
 *
 * <p>而且就算数字是真的，它也回答不了真正要问的问题：<b>画面出得来吗？拆分对吗？
 * 位置对吗？</b> 这三件事只有看一眼才知道。所以这里自己开一次相机，
 * 按新配置的尺寸出画面，连同倒计时一起摆出来。</p>
 *
 * <h3>默认是放弃</h3>
 *
 * <p>配错了最坏的情况是黑屏，那时候人是没法点「取消」的。所以倒计时结束不保存。</p>
 */
public final class ProfilePreviewCheck {

    private static final String TAG = "ProfilePreviewCheck";

    /** 看画面 + 决定的时间。 */
    private static final int CONFIRM_SECONDS = 15;

    /** 开相机、配会话的等待上限。 */
    private static final long OPEN_TIMEOUT_MS = 5000L;

    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread cameraThread;
    private Handler cameraHandler;

    private CameraDevice device;
    private CameraCaptureSession session;
    private AlertDialog dialog;
    private Runnable tick;

    public ProfilePreviewCheck(Activity activity) {
        this.activity = activity;
    }

    /**
     * 开一次画面让人看，确认了就回调。
     *
     * @param cameraId 要开的相机
     * @param size     按配置解析出来的预览尺寸；null 表示让相机自己挑
     * @param split    这个尺寸会不会被拆成四格
     */
    public void run(String cameraId, Size size, boolean split, Runnable onConfirmed) {
        cameraThread = new HandlerThread("profile-preview-check");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);

        final TextView note = new TextView(activity);
        note.setTextSize(14f);
        int pad = (int) (12 * activity.getResources().getDisplayMetrics().density);
        note.setPadding(pad, pad, pad, pad);
        note.setText("正在打开相机……");
        box.addView(note);

        TextureView texture = new TextureView(activity);
        ViewGroup holder;
        if (split && size != null) {
            // 拆四格的尺寸就用真正的四宫格容器，看到的排布和主界面一致
            FourLaneContainer container = new FourLaneContainer(activity);
            container.addView(texture, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            container.setSourceSize(size);
            holder = container;
        } else {
            FrameLayout frame = new FrameLayout(activity);
            frame.addView(texture, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            holder = frame;
        }
        int height = (int) (280 * activity.getResources().getDisplayMetrics().density);
        box.addView(holder, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));

        dialog = new AlertDialog.Builder(activity, R.style.AlertDialogTheme)
                .setTitle("看一眼再保存")
                .setView(box)
                .setPositiveButton("保存", (d, w) -> {
                    stop();
                    onConfirmed.run();
                })
                .setNegativeButton(R.string.action_cancel, (d, w) -> stop())
                .setCancelable(false)
                .create();
        dialog.setOnDismissListener(d -> stop());
        dialog.show();

        startCountdown(CONFIRM_SECONDS);

        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int w, int h) {
                if (size != null) {
                    surface.setDefaultBufferSize(size.getWidth(), size.getHeight());
                }
                open(cameraId, new Surface(surface), note);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int w, int h) {
                if (size != null) {
                    // TextureView 会把缓冲区改回自己的尺寸，改回来
                    surface.setDefaultBufferSize(size.getWidth(), size.getHeight());
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
    }

    private void startCountdown(int seconds) {
        final int[] left = {seconds};
        tick = new Runnable() {
            @Override
            public void run() {
                left[0]--;
                if (dialog == null || !dialog.isShowing()) {
                    return;
                }
                if (left[0] <= 0) {
                    dialog.dismiss();
                    return;
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                        .setText("保存（" + left[0] + "）");
                ui.postDelayed(this, 1000L);
            }
        };
        ui.postDelayed(tick, 1000L);
    }

    private void open(String cameraId, Surface surface, TextView note) {
        CameraManager manager =
                (CameraManager) activity.getSystemService(Activity.CAMERA_SERVICE);
        try {
            manager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    device = camera;
                    try {
                        List<Surface> targets = new ArrayList<>();
                        targets.add(surface);
                        camera.createCaptureSession(targets,
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession configured) {
                                        session = configured;
                                        try {
                                            CaptureRequest.Builder builder =
                                                    camera.createCaptureRequest(
                                                            CameraDevice.TEMPLATE_PREVIEW);
                                            builder.addTarget(surface);
                                            configured.setRepeatingRequest(
                                                    builder.build(), null, cameraHandler);
                                            say(note, "画面出来了。看清楚再决定 ——"
                                                    + "拆分对不对、位置对不对、有没有卡。");
                                        } catch (Exception e) {
                                            say(note, "开了会话但出不了画面：" + e);
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(
                                            CameraCaptureSession configured) {
                                        say(note, "会话配置失败 —— 这套组合这台车机跑不了。"
                                                + "保存了会没有画面。");
                                    }
                                }, cameraHandler);
                    } catch (Exception e) {
                        say(note, "建会话失败：" + e);
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    say(note, "相机被断开 —— 可能正在录制，或者被别的应用占着。"
                            + "停掉录制再试。");
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    say(note, "打不开相机，错误 " + error);
                }
            }, cameraHandler);
        } catch (Exception e) {
            say(note, "打不开相机：" + e);
        }
    }

    private void say(TextView note, String text) {
        AppLog.i(TAG, text);
        ui.post(() -> note.setText(text));
    }

    private void stop() {
        if (tick != null) {
            ui.removeCallbacks(tick);
            tick = null;
        }
        if (session != null) {
            try {
                session.close();
            } catch (Exception ignored) {
                // 关不上也没关系，设备关掉就一起没了
            }
            session = null;
        }
        if (device != null) {
            device.close();
            device = null;
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
            cameraThread = null;
            cameraHandler = null;
        }
    }
}
