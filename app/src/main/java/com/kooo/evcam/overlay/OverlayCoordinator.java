package com.kooo.evcam.overlay;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.service.RecordingFloatingService;
import com.kooo.evcam.zeekr.RearViewMirrorService;

/**
 * 两个悬浮窗（超级后视镜、录制悬浮按钮）「该不该开、能不能开、什么时候开」。
 *
 * <h3>为什么要有这么个地方</h3>
 *
 * <p>同一个问题原先在三处各答一遍：主界面启动时、设置页开关时、前台服务在开机自启时。
 * 三份答案已经不一致了 —— 最明显的一处是<b>超级后视镜的开关没查悬浮窗权限</b>：
 * 没授权时开关拨上去、提示语还告诉你手势怎么用，屏幕上什么都没有，
 * 服务在 {@code onStartCommand} 里 {@code stopSelf()} 走了，只往 logcat 写了一行。
 * 画面悬浮窗和录制悬浮按钮的开关都查了，唯独它漏了。</p>
 *
 * <p>这类「设置里显示开着、实际没开」的毛病，根子是同一个判断被抄了好几份。
 * 抄的时候都对，改的时候只改一处。所以这里只留一份。</p>
 */
public final class OverlayCoordinator {

    private static final String TAG = "OverlayCoordinator";

    /** 后视镜要绑相机，相机这会儿还在开，等一下再拉。 */
    private static final long REAR_VIEW_DELAY_MS = 2000;

    /** 悬浮窗服务起来之后才收得到状态广播。 */
    private static final long STATE_PUSH_DELAY_MS = 500;

    private OverlayCoordinator() {
    }

    // ------------------------------------------------------------------ 判断

    /** 有没有悬浮窗权限。没有的话，下面这些一个都开不起来。 */
    public static boolean canShowOverlay(Context context) {
        return WakeUpHelper.hasOverlayPermission(context);
    }


    // ------------------------------------------------------------------ 启动时恢复

    /**
     * 按设置把该开的悬浮窗都开起来。
     *
     * @param afterPreviewWindowStarted 画面悬浮窗起来之后要做的事（推录制状态过去）；
     *                                  没开这个窗时不会被调用
     */
    public static void restoreOnLaunch(Context context, Runnable afterPreviewWindowStarted) {
        AppConfig config = new AppConfig(context);
        boolean allowed = canShowOverlay(context);

        if (config.isRearViewEnabled() && allowed) {
            // 这一段以前没有：开关存着「开」，但没人在启动时把服务拉起来，
            // 于是每次重开应用都要去设置里关一次再开一次它才出现。
            main().postDelayed(() -> {
                RearViewMirrorService.start(context);
                AppLog.d(TAG, "超级后视镜已按设置自动开启");
            }, REAR_VIEW_DELAY_MS);
        }

        if (config.isRecordingFloatingEnabled() && allowed) {
            sendToRecordingFloating(context, RecordingFloatingService.ACTION_SHOW);
            AppLog.d(TAG, "悬浮按钮已启动");
            main().postDelayed(() -> {
                if (afterPreviewWindowStarted != null) {
                    afterPreviewWindowStarted.run();
                }
            }, STATE_PUSH_DELAY_MS);
        }
    }

    // ------------------------------------------------------------------ 开关

    /**
     * 开 / 关悬浮按钮。
     *
     * <p>0.45 起只有这一个按钮：原来那个只管「打开应用」的已经并进来，
     * 成了它默认的单击动作。</p>
     *
     * @return 是否真的按要求生效；没有悬浮窗权限时返回 {@code false}，
     *         调用方应当把开关保持在原位而不是拨上去
     */
    public static boolean setRecordButtonEnabled(Context context, boolean enabled) {
        if (enabled && !canShowOverlay(context)) {
            return false;
        }
        new AppConfig(context).setRecordingFloatingEnabled(enabled);
        sendToRecordingFloating(context, enabled
                ? RecordingFloatingService.ACTION_SHOW
                : RecordingFloatingService.ACTION_HIDE);
        return true;
    }

    /**
     * 开 / 关超级后视镜。返回值含义同 {@link #setRecordButtonEnabled}。
     *
     * <p>这里的权限检查是补上的 —— 原来没有，没授权时开关会拨上去而窗口不出现。</p>
     */
    public static boolean setRearViewEnabled(Context context, boolean enabled) {
        if (enabled && !canShowOverlay(context)) {
            return false;
        }
        new AppConfig(context).setRearViewEnabled(enabled);
        if (enabled) {
            RearViewMirrorService.start(context);
        } else {
            RearViewMirrorService.stop(context);
        }
        return true;
    }


    /**
     * 主界面销毁时的清理。
     *
     * <p>什么都不用停：后视镜和悬浮按钮是<b>脱离主界面用的</b>，
     * 主界面没了它们还该在 —— 那本来就是它们存在的理由。</p>
     */
    public static void onActivityDestroyed(Context context) {
        // 有意留空
    }

    // ------------------------------------------------------------------

    private static void sendToRecordingFloating(Context context, String action) {
        Intent intent = new Intent(context, RecordingFloatingService.class);
        intent.setAction(action);
        context.startService(intent);
    }

    private static Handler main() {
        return new Handler(Looper.getMainLooper());
    }
}
