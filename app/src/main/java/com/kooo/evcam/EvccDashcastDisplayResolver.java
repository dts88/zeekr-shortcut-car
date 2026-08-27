package com.kooo.evcam;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

/**
 * 把 EVCC 仪表投屏的"-100"占位符解析成真实 Display ID。
 *
 * 背景：EVCC (com.kooo.evcc) 把仪表投屏渲染到一块运行时创建的 VirtualDisplay 上。
 * 这块虚拟屏每次重启 EVCC 后真实 displayId 都会变，没法在 EVCam 这种第三方 app
 * 里硬编码。EVCC 的解决办法是：
 *   - 给虚拟屏起一个固定的名字 "EVCC_DashCast_Primary"
 *   - 用 -100 作为占位符（真实 Display ID 都 ≥ 0，不会冲突）
 *   - 通过 ContentProvider content://com.kooo.evcc.displays 把 name→id 映射暴露出来
 *
 * EVCam 的副屏补盲想投到仪表屏时，存储的就是占位符 -100；运行时调
 * {@link #resolve(Context, int)} 翻成真实 Display ID 再交给 DisplayManager。
 */
public final class EvccDashcastDisplayResolver {

    /** EVCC 仪表投屏占位 ID，存进 SharedPreferences 的就是这个值。 */
    public static final int PICK_ID = -100;

    /** EVCC ContentProvider authority。 */
    private static final String AUTHORITY = "com.kooo.evcc.displays";

    /** EVCC 仪表投屏主 VirtualDisplay 的固定名字。 */
    private static final String PRIMARY_NAME = "EVCC_DashCast_Primary";

    private static final String COL_DISPLAY_ID = "displayId";
    private static final String COL_WIDTH = "width";
    private static final String COL_HEIGHT = "height";

    private EvccDashcastDisplayResolver() {
    }

    /** 当前选择的副屏是否就是 EVCC 仪表投屏占位符。 */
    public static boolean isEvccPick(int rawDisplayId) {
        return rawDisplayId == PICK_ID;
    }

    /**
     * 把存储的副屏 displayId 翻译成真实可用的 Display ID。
     *
     *  - 不是 {@link #PICK_ID} → 原样返回
     *  - 是 {@link #PICK_ID} 且 EVCC 仪表投屏正在运行 → 返回真实 ID
     *  - 是 {@link #PICK_ID} 但 EVCC 没装/没起投屏 → 返回 -1（调用方应跳过显示，等 EVCC 起来再试）
     */
    public static int resolve(Context context, int rawDisplayId) {
        if (rawDisplayId != PICK_ID) {
            return rawDisplayId;
        }
        Info info = queryPrimary(context);
        return info != null ? info.displayId : -1;
    }

    /** 查询 EVCC 仪表投屏当前真实尺寸；EVCC 没启动时返回 null。供 UI 显示用。 */
    @androidx.annotation.Nullable
    public static Info queryPrimary(Context context) {
        Uri uri = new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendQueryParameter("name", PRIMARY_NAME)
                .build();
        try (Cursor c = context.getContentResolver().query(uri, null, null, null, null)) {
            if (c == null || !c.moveToFirst()) {
                return null;
            }
            int idIdx = c.getColumnIndex(COL_DISPLAY_ID);
            int wIdx = c.getColumnIndex(COL_WIDTH);
            int hIdx = c.getColumnIndex(COL_HEIGHT);
            if (idIdx < 0) return null;
            int displayId = c.getInt(idIdx);
            int w = wIdx >= 0 ? c.getInt(wIdx) : 0;
            int h = hIdx >= 0 ? c.getInt(hIdx) : 0;
            return new Info(displayId, w, h);
        } catch (Exception e) {
            // EVCC 未安装 / Provider 未导出 / 包可见性被拦 — 当作不可用
            return null;
        }
    }

    public static final class Info {
        public final int displayId;
        public final int widthPx;
        public final int heightPx;

        public Info(int displayId, int widthPx, int heightPx) {
            this.displayId = displayId;
            this.widthPx = widthPx;
            this.heightPx = heightPx;
        }
    }
}
