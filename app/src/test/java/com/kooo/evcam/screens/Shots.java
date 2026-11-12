/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.screens;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.View;

import org.robolectric.Shadows;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 把界面画成图片（项目所有者 2026-11-12 同意引入截图测试）：不上车也能逐页看，改界面时拿它对照设计。
 *
 * <p>用 Robolectric 的原生绘图在电脑上画，尺寸照车机的应用区域：1600 × 848 dp、密度 2.0（3200 × 1696 像素）。
 * 这是 2026-11-13 从实车量出来的（诊断信息里环视画面那一块 1403 × 1601 像素，加上四周的留白；照片上各块的比例也对得上）；
 * 4.0.0 照 2133 × 1197 dp、密度 1.5 画，比实车「小一号」，于是动作栏在车上排不下、设置的两列挤成一团。
 * 诊断信息「3. 显示屏」现在写着应用看到的尺寸和密度，再有出入以它为准。
 * 图片写到 {@code app/build/outputs/screenshots/}，CI 把这个文件夹传上去。</p>
 *
 * <p>画出来的和车上不是每个像素都一样（字体不同、没有相机画面），它管的是排布、文字、颜色、有没有截断。</p>
 */
public final class Shots {

    /** 车机应用区域，中文，白天。 */
    public static final String DAY = "zh-rCN-w1600dp-h848dp-land-notnight-xhdpi";
    /** 车机应用区域，中文，夜间。 */
    public static final String NIGHT = "zh-rCN-w1600dp-h848dp-land-night-xhdpi";

    private static final File FOLDER = new File("build/outputs/screenshots");

    private Shots() {
    }

    /** 系统的动画关掉：装饰动效不放（{@code MotionPolicy}），界面一步到位，画出来的就是停下来的样子。 */
    public static void stillFrames(Activity activity) {
        Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0f);
    }

    /** 把排队的界面工作做完（布局、列表绑定、fragment 事务）。 */
    public static void settle() {
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** 把这个界面此刻的样子存成 {@code name.png}。 */
    public static File save(Activity activity, String name) {
        settle();
        View root = activity.getWindow().getDecorView();
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int width = metrics.widthPixels;
        int height = metrics.heightPixels;
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
        settle();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        File out = new File(FOLDER, name + ".png");
        //noinspection ResultOfMethodCallIgnored
        FOLDER.mkdirs();
        try (FileOutputStream stream = new FileOutputStream(out)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
        } catch (IOException e) {
            throw new IllegalStateException("无法写入截图 " + out, e);
        } finally {
            bitmap.recycle();
        }
        return out;
    }
}
