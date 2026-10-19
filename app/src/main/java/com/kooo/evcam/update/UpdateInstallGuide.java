package com.kooo.evcam.update;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

import com.bumptech.glide.Glide;
import com.kooo.evcam.R;

/**
 * 检查更新的安装提醒：点「下载并安装」之后、真正开始下载之前，先看一张图。
 *
 * <h3>为什么要有这一页</h3>
 *
 * <p>下载完直接跳进车机自带的安装界面。那个界面有 bug：装好后点中间的「打开」就回不去，
 * 下一次安装、升级会出错；点左上角的「返回」没事。到了那一页应用已经不在前台，
 * 没法再提醒 —— 所以只能在下载之前说。</p>
 *
 * <p>样子照超级镜子指南（{@code ui/RearViewGuide}）：压暗的底、居中一张 16:9 的图。
 * 图按语言分两套：{@code drawable-zh-nodpi}（中文）和 {@code drawable-nodpi}（英文）——
 * 只有中文界面看中文图，其他语言一律看英文图。源文件在 {@code design/update-guide}（不入库）。</p>
 *
 * <p>每次下载都弹，不记「看过」：升级隔得久，上一次看过的早忘了，而点错一次的代价是下次装不上。</p>
 */
final class UpdateInstallGuide {

    /** 说明图画的时候是 1600×900。 */
    private static final float ASPECT = 16f / 9f;

    private UpdateInstallGuide() {
    }

    /** 弹出提醒；点「知道了，开始下载」才执行 {@code onProceed}，按返回键就什么都不做。 */
    static void show(Activity activity, Runnable onProceed) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_update_guide);
        dialog.setCancelable(true);
        // 点图外面不关：这一页挡在下载前面，点空了等于没下载
        dialog.setCanceledOnTouchOutside(false);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.72f);
        }

        // 和后视镜指南一样大：宽不超过屏幕的八成，高不超过七成二，谁先顶到按谁
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int width = Math.round(Math.min(metrics.widthPixels * 0.8f,
                metrics.heightPixels * 0.72f * ASPECT));
        int height = Math.round(width / ASPECT);

        ImageView image = dialog.findViewById(R.id.guide_image);
        image.setLayoutParams(new LinearLayout.LayoutParams(width, height));
        final float radius = 16 * metrics.density;
        image.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        image.setClipToOutline(true);
        // 图是 2304×1296，按显示大小解码
        Glide.with(image).load(R.drawable.update_install_guide).override(width, height).into(image);

        dialog.findViewById(R.id.guide_done).setOnClickListener(v -> {
            dialog.dismiss();
            onProceed.run();
        });
        dialog.show();
    }
}
