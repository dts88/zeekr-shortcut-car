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

    /**
     * <b>槽位</b>名：这一格里装的是哪一路相机。
     *
     * <h3>为什么不能直接用上面那个</h3>
     *
     * <p>{@code front/back/left/right} 这四个词在这个项目里有两个意思：</p>
     *
     * <ul>
     *   <li><b>合成流里的第几格</b> —— 那确实是车头、车尾、左侧、右侧四个方向，
     *       {@link #ofLane} 给的就是这个。</li>
     *   <li><b>相机接在第几个槽位上</b> —— 三路配置里 front 是环视合成流、
     *       back 是前座舱、left 是后座舱，和方向毫无关系。</li>
     * </ul>
     *
     * <p>主界面和图片回看的角标要的是第二个意思，却一直在用第一个 ——
     * 于是环视那一格写着「前」、前座舱写着「后」、后座舱写着「左」。</p>
     *
     * <p>判断只看配置里有没有这一路：有，按它在配置里的角色叫；没有
     * （自定义车型那种四面各一个相机的接法），才回到方向名。</p>
     */
    public static String ofSlot(Context context, String position) {
        Integer res = roleResFor(context, position);
        return res == null ? of(context, position) : context.getString(res);
    }

    /**
     * 这一路在配置里是不是一个<b>认出来的</b>角色。
     *
     * <p>是 —— 环视、前座舱、后座舱 —— 就有定名，不让改：改了之后
     * 「配置编辑里叫后座舱、主界面角标叫别的」，对不上。</p>
     *
     * <p>不是（自定义车型那种四面各一个相机的接法，或者以后新认出来的路），
     * 返回 null，由调用方决定叫什么 —— 那种情况下名字归用户。</p>
     */
    public static Integer roleResFor(Context context, String position) {
        try {
            String role = com.kooo.evcam.profile.ProfileSizes.roleForCameraKey(position);
            if (role != null
                    && new com.kooo.evcam.profile.ProfileStore(context)
                            .current().camera(role) != null) {
                return roleRes(role);
            }
        } catch (Exception ignored) {
            // 配置读不出来就当没认出来，名字归用户
        }
        return null;
    }

    private static int roleRes(String role) {
        if (com.kooo.evcam.profile.CameraProfile.ROLE_CABIN_1.equals(role)) {
            return R.string.slot_cabin_front;
        }
        if (com.kooo.evcam.profile.CameraProfile.ROLE_CABIN_2.equals(role)) {
            return R.string.slot_cabin_rear;
        }
        // 角标要短：slot_surround 是「环视（合成流）」，那是配置界面上的说法
        return R.string.lane_surround;
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
