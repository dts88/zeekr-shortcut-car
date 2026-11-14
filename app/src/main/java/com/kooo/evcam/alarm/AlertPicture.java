/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import android.graphics.Bitmap;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.zeekr.FisheyeCorrector;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 警报发出去的那张（或那几张）图：怎么切、要不要掰直、压多大。纯图形逻辑，不碰网络。
 *
 * <h3>一张还是四张</h3>
 *
 * <p>环视拍下来的是 2×2 四宫格：左上前面、右上后面、左下左侧、右下右侧（{@code CompositeBitmapComposer}
 * 的默认排列 {@code {0,1,2,3}}，座次见 {@code LaneCycle}）。整张发出去最省事，一眼看全；
 * 但四个画面挤在一张里，人只有原图四分之一大。所以两种都留着：</p>
 *
 * <ul>
 *   <li>{@link Layout#GRID}：一张 2×2，四个画面在一起；</li>
 *   <li>{@link Layout#SEPARATE}：按勾选的那几路各发一张，每张在手机上是整屏大小。</li>
 * </ul>
 *
 * <h3>掰直</h3>
 *
 * <p>用的就是回看界面上那个鱼眼校正（{@link FisheyeCorrector#correctGrid}，同一套几何、同一组参数），
 * 不是另做一套 —— 车主看到的和录下来的得是一回事。</p>
 *
 * <h3>压多大</h3>
 *
 * <p>车上 4G 上传越小越不容易被掐断，所以压到 {@link #MAX_BYTES} 以下：先 80，不行再降一档，
 * 最低 50。四张分开发也按这个预算各自压。</p>
 */
public final class AlertPicture {

    /** 环视合成图是几路。 */
    public static final int LANES = 4;

    /** 环视合成图的格子排布：2 列 2 行。 */
    public static final int COLUMNS = 2;
    public static final int ROWS = 2;

    /** 发出去的字节上限。 */
    public static final int MAX_BYTES = 900 * 1024;

    /** 降档次序：先 80，太大再降。 */
    private static final int[] QUALITIES = {80, 70, 60, 50};

    private static final String TAG = "AlertPicture";

    /** 发几张。 */
    public enum Layout {
        /** 一张 2×2 四宫格。 */
        GRID,
        /** 勾选的那几路各一张。 */
        SEPARATE
    }

    private AlertPicture() {
    }

    /** 设置里存的那个值 → 排版；认不出来的按四宫格算。 */
    public static Layout layout(String value) {
        return "separate".equalsIgnoreCase(value == null ? "" : value.trim())
                ? Layout.SEPARATE : Layout.GRID;
    }

    /** 设置里存的那个值：{@link Layout} → 存什么。 */
    public static String layoutValue(Layout layout) {
        return layout == Layout.SEPARATE ? "separate" : "grid";
    }

    /**
     * 「要发哪几路」的字符串 → 路号。空、乱写、越界的一律当「全都要」。
     *
     * <p>顺序固定从小到大，去重：勾了哪几路与用户点选的先后无关。</p>
     */
    public static int[] lanes(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return new int[] {0, 1, 2, 3};
        }
        List<Integer> kept = new ArrayList<>();
        for (String part : csv.split(",")) {
            try {
                int lane = Integer.parseInt(part.trim());
                if (lane >= 0 && lane < LANES && !kept.contains(lane)) {
                    kept.add(lane);
                }
            } catch (NumberFormatException ignored) {
                // 认不出来的那一段跳过
            }
        }
        if (kept.isEmpty()) {
            return new int[] {0, 1, 2, 3};
        }
        java.util.Collections.sort(kept);
        int[] out = new int[kept.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = kept.get(i);
        }
        return out;
    }

    /**
     * 反方向：路号 → 设置里存的字符串。
     *
     * <p>先拼成串再走一遍 {@link #lanes(String)}：写入的规范只有一处 ——
     * 从小到大、不重复。存进去的值和读出来的一样，比较才靠得住。</p>
     */
    public static String lanesValue(int[] lanes) {
        if (lanes == null || lanes.length == 0) {
            return "";
        }
        StringBuilder raw = new StringBuilder();
        for (int lane : lanes) {
            if (raw.length() > 0) {
                raw.append(',');
            }
            raw.append(lane);
        }
        StringBuilder out = new StringBuilder();
        for (int lane : lanes(raw.toString())) {
            if (out.length() > 0) {
                out.append(',');
            }
            out.append(lane);
        }
        return out.toString();
    }

    /** 四路都在里面没有。 */
    public static boolean allLanes(int[] lanes) {
        return lanes != null && lanes.length == LANES;
    }

    /**
     * 掰直整张四宫格。开关关着、或者没图时原样还回来。
     *
     * @param fovDegrees 视野角度（和回看界面那一项同一个含义）
     * @param projection 投影方式
     */
    public static Bitmap straighten(Bitmap grid, boolean straighten, float fovDegrees,
                                    String projection) {
        if (!straighten || grid == null || grid.isRecycled()) {
            return grid;
        }
        try {
            return FisheyeCorrector.correctGrid(grid, COLUMNS, ROWS, fovDegrees, projection);
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w(TAG, "straighten failed, sending the picture as it is: " + e);
            return grid;
        }
    }

    /**
     * 从 2×2 里裁出这一路。
     *
     * @param lane 0 前、1 后、2 左、3 右（{@code LaneCycle}，和存盘时的默认排列一致）
     * @return 这一格；参数不对时返回 {@code null}
     */
    public static Bitmap quadrant(Bitmap grid, int lane) {
        if (grid == null || grid.isRecycled() || lane < 0 || lane >= LANES) {
            return null;
        }
        int cellWidth = grid.getWidth() / COLUMNS;
        int cellHeight = grid.getHeight() / ROWS;
        if (cellWidth < 2 || cellHeight < 2) {
            return null;
        }
        int x = (lane % COLUMNS) * cellWidth;
        int y = (lane / COLUMNS) * cellHeight;
        try {
            return Bitmap.createBitmap(grid, x, y, cellWidth, cellHeight);
        } catch (RuntimeException | OutOfMemoryError e) {
            AppLog.w(TAG, "could not cut lane " + lane + ": " + e);
            return null;
        }
    }

    /** 压成能发的 JPEG：按 {@link #QUALITIES} 一档档降，到 {@link #MAX_BYTES} 以下就停。 */
    public static byte[] jpeg(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return null;
        }
        byte[] best = null;
        for (int quality : QUALITIES) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, bytes);
            best = bytes.toByteArray();
            if (best.length <= MAX_BYTES) {
                break;
            }
        }
        return best;
    }
}
