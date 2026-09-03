package com.kooo.evcam.zeekr;

import android.content.Context;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;

/**
 * 把「环视流尺寸」这个开发者选项翻译成一条排布声明。
 *
 * <h3>为什么要单独走一趟</h3>
 *
 * <p>{@link CompositeStreamGeometry} 是纯几何，不认识 SharedPreferences；
 * 而预览、录制、后视镜、照片拼合四条路径都要用<b>同一套</b>拆分结果。
 * 所以在进程启动时读一次设置，把结论告诉几何类，四边自然一致。</p>
 *
 * <p>强制指定的尺寸一律按<b>四格竖排</b>声明：这一路目前已知的两个尺寸
 * （1280×5140 与 3840×2160）都是竖排。真出现横排的固件，再加一个选项不迟 ——
 * 现在就加一个没人验证过的开关，只是多一个可能选错的地方。</p>
 */
public final class CompositeDeclaration {

    private static final String TAG = "CompositeDeclaration";

    private CompositeDeclaration() {
    }

    /** 进程启动时调一次。设置改了要重启应用才生效，这也是设置里那句提示的由来。 */
    public static void applyFrom(Context context) {
        int[] size = AppConfig.parseResolution(new AppConfig(context).getCompositeSizeOverride());
        if (size == null) {
            CompositeStreamGeometry.clearDeclaration();
            return;
        }
        CompositeStreamGeometry.declareComposite(size[0], size[1],
                CompositeStreamGeometry.Stacking.VERTICAL);
        AppLog.i(TAG, "环视流被声明为四格竖排：" + size[0] + "x" + size[1]
                + "（长宽比判定对这个尺寸不成立，按声明拆分）");
    }
}
