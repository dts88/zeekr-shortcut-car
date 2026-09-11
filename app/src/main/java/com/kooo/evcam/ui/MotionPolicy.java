package com.kooo.evcam.ui;

import android.content.Context;
import android.provider.Settings;

import com.kooo.evcam.AppConfig;

/**
 * 装饰性动效现在该不该放。
 *
 * <h3>什么算装饰性</h3>
 *
 * <p>界面之间的过渡、四宫格与单画面之间的放大、数字滚动这类 —— 去掉之后信息一点不少，
 * 只是切换变成了直接切。录制键的点和进度环、按下去的涟漪<b>不算</b>：
 * 它们本身就是信息（在不在录、这一段还有多久、按到没有），任何时候都留着。</p>
 *
 * <h3>两种情况下不放</h3>
 *
 * <ul>
 *   <li>系统里把「动画时长缩放」关了 —— 用户已经说过不要动画；</li>
 *   <li>正在录制，而且设置里开着「录制时减少动效」（默认开）——
 *       编码器正在用 GPU，界面不跟它抢。</li>
 * </ul>
 */
public final class MotionPolicy {

    private static volatile boolean recording;

    private MotionPolicy() {
    }

    /** 由录制键的状态机维护：准备中、录制中都算在录。 */
    public static void setRecording(boolean value) {
        recording = value;
    }

    /** 现在能不能放装饰性动效。 */
    public static boolean decorative(Context context) {
        if (context == null) {
            return true;
        }
        float scale = 1f;
        try {
            scale = Settings.Global.getFloat(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
        } catch (Exception ignored) {
            // 读不到就当没关动画
        }
        return allows(recording, new AppConfig(context).isReduceMotionWhileRecording(), scale);
    }

    /** 判断本身，单独拿出来测。 */
    static boolean allows(boolean recording, boolean reduceWhileRecording, float animatorScale) {
        if (animatorScale <= 0f) {
            return false;
        }
        return !(recording && reduceWhileRecording);
    }
}
