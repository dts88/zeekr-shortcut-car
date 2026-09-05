package com.kooo.evcam.camera;

import android.content.Context;

import com.kooo.evcam.R;

/**
 * 机位的名字：前 / 后 / 左 / 右。
 *
 * <h3>为什么单拎出来一个地方</h3>
 *
 * <p>这四个字原本散在六处，每一处都是写死的中文字面量。
 * 界面语言切到英文之后，周围的字都跟着翻了，只有这四个还是中文 ——
 * 它们从来没经过资源表。</p>
 *
 * <h3>为什么必须传界面的 Context</h3>
 *
 * <p>「应用语言」是通过界面那一层的 Context 生效的（appcompat 在 Activity 的
 * attachBaseContext 上换掉配置）。拿 Application 的 Context 取字符串，
 * 在 Android 13 以下会拿回<b>系统语言</b>那一份 —— 也就是「设置里选了英文、
 * 这四个标签仍然是中文」。所以这里只收调用方那个界面的 Context。</p>
 */
public final class CameraNames {

    /** 四个机位，顺序与合成流拆出来的 lane 一致：前 后 左 右。 */
    public static final String[] POSITIONS = {"front", "back", "left", "right"};

    private CameraNames() {
    }

    /** 机位名。位置不认识就把位置本身还回去，不编一个名字。 */
    public static String of(Context context, String position) {
        int res = labelRes(position);
        return res == 0 ? String.valueOf(position) : context.getString(res);
    }

    /** 按 lane 序号取名：0 前、1 后、2 左、3 右。 */
    public static String ofLane(Context context, int lane) {
        if (lane < 0 || lane >= POSITIONS.length) {
            return String.valueOf(lane);
        }
        return of(context, POSITIONS[lane]);
    }

    private static int labelRes(String position) {
        if (position == null) {
            return 0;
        }
        switch (position) {
            case "front":
                return R.string.zeekr_lane_front;
            case "back":
                return R.string.zeekr_lane_back;
            case "left":
                return R.string.zeekr_lane_left;
            case "right":
                return R.string.zeekr_lane_right;
            default:
                return 0;
        }
    }
}
