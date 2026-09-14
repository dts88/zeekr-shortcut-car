package com.kooo.evcam.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.PagerSnapHelper;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;

/**
 * 超级后视镜使用指南：五张说明图，左右划翻页，最后一张是「知道了」。
 *
 * <h3>为什么是图，不是一段文字</h3>
 *
 * <p>后视镜窗口没有按钮，全靠手势，而手势是按<b>位置</b>分的 —— 左右两侧拖、
 * 中间划。这件事用文字讲要三句，看一眼图只要一秒。原来开启时弹的那条提示
 * （{@code msg_rearview_on}）一闪就没了，想再看一遍没有地方。</p>
 *
 * <h3>什么时候出现</h3>
 *
 * <ul>
 *   <li>第一次在抽屉或设置里打开后视镜时弹一次（{@link #showOnce}）；</li>
 *   <li>设置 → 超级后视镜 → 使用指南，随时再看（{@link #show}）。</li>
 * </ul>
 *
 * <p>从悬浮按钮打开后视镜时不弹：那时主界面多半不在前台，为了一张说明图把应用拽到
 * 前面，打断的正是在用车机的人。</p>
 *
 * <h3>怎么关</h3>
 *
 * <p>点图片外面任何地方、按返回键、或者翻到最后一张点「知道了」。不设关闭的 ×：
 * 图外整片都是关闭区，一个小 × 只会让人去找它。</p>
 *
 * <p>图片按语言分两套：{@code drawable-nodpi}（中文）和 {@code drawable-en-nodpi}（英文），
 * 源文件是 {@code design/mirror-guide} 里的五张画板（不入库）。</p>
 */
public final class RearViewGuide {

    /** 说明图画的时候是 1600×900。 */
    private static final float ASPECT = 16f / 9f;

    private static final int[] PAGES = {
            R.drawable.rearview_guide_1,
            R.drawable.rearview_guide_2,
            R.drawable.rearview_guide_3,
            R.drawable.rearview_guide_4,
            R.drawable.rearview_guide_5,
    };

    private RearViewGuide() {
    }

    /** 第一次打开超级后视镜时弹一次；弹过就不再弹。 */
    public static void showOnce(Activity activity) {
        if (activity == null) {
            return;
        }
        AppConfig config = new AppConfig(activity);
        if (config.isRearViewGuideSeen()) {
            return;
        }
        config.setRearViewGuideSeen();
        show(activity);
    }

    /** 什么时候调都打开。 */
    public static void show(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_rearview_guide);
        dialog.setCancelable(true);
        Window window = dialog.getWindow();
        if (window != null) {
            // 窗口铺满、底透明：图片外面整片都是「点一下就关」的地方
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.72f);
        }

        // 一张图占多大：宽不超过屏幕的八成，高不超过七成二，谁先顶到按谁
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int pageWidth = Math.round(Math.min(metrics.widthPixels * 0.8f,
                metrics.heightPixels * 0.72f * ASPECT));
        int pageHeight = Math.round(pageWidth / ASPECT);

        RecyclerView pages = dialog.findViewById(R.id.guide_pages);
        LinearLayout dots = dialog.findViewById(R.id.guide_dots);
        TextView hint = dialog.findViewById(R.id.guide_hint);
        View done = dialog.findViewById(R.id.guide_done);

        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(pageWidth, pageHeight);
        pages.setLayoutParams(size);
        LinearLayoutManager manager =
                new LinearLayoutManager(activity, LinearLayoutManager.HORIZONTAL, false);
        pages.setLayoutManager(manager);
        pages.setAdapter(new PageAdapter(pageWidth, pageHeight));
        // 一次翻一整张，停在正中，不停在两张中间
        new PagerSnapHelper().attachToRecyclerView(pages);

        buildDots(dots);
        showPage(0, dots, hint, done);
        pages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
                if (state != RecyclerView.SCROLL_STATE_IDLE) {
                    return;
                }
                int position = manager.findFirstCompletelyVisibleItemPosition();
                if (position != RecyclerView.NO_POSITION) {
                    showPage(position, dots, hint, done);
                }
            }
        });

        // 图片那一块由列表自己吃掉触摸（它要翻页），落不到这里；落到这里的就是图片外面
        dialog.findViewById(R.id.guide_root).setOnClickListener(v -> dialog.dismiss());
        done.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private static void buildDots(LinearLayout dots) {
        Context context = dots.getContext();
        int dot = dp(context, 10);
        int gap = dp(context, 10);
        for (int i = 0; i < PAGES.length; i++) {
            View view = new View(context);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dot, dot);
            if (i > 0) {
                params.setMarginStart(gap);
            }
            view.setLayoutParams(params);
            dots.addView(view);
        }
    }

    /** 翻到第几张：圆点跟着走，最后一张把提示换成「知道了」。 */
    private static void showPage(int position, LinearLayout dots, TextView hint, View done) {
        Context context = dots.getContext();
        int active = ContextCompat.getColor(context, R.color.energy);
        int idle = ContextCompat.getColor(context, R.color.status_overlay_text_dim);
        for (int i = 0; i < dots.getChildCount(); i++) {
            GradientDrawable shape = new GradientDrawable();
            shape.setShape(GradientDrawable.OVAL);
            shape.setColor(i == position ? active : idle);
            dots.getChildAt(i).setBackground(shape);
        }
        boolean last = position == PAGES.length - 1;
        hint.setVisibility(last ? View.INVISIBLE : View.VISIBLE);
        done.setVisibility(last ? View.VISIBLE : View.GONE);
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * 一页一张图。
     *
     * <p>图是 2304×1296 的，五张全按原尺寸解码要占五十多 MB。交给 Glide 按这一页
     * 实际显示的大小解码，列表只留着眼前这一两张。</p>
     */
    private static final class PageAdapter extends RecyclerView.Adapter<PageAdapter.Holder> {

        private final int width;
        private final int height;

        PageAdapter(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            ImageView image = new ImageView(parent.getContext());
            image.setLayoutParams(new RecyclerView.LayoutParams(width, height));
            image.setScaleType(ImageView.ScaleType.FIT_XY);
            final float radius = 16 * parent.getContext().getResources().getDisplayMetrics().density;
            image.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            image.setClipToOutline(true);
            return new Holder(image);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            ImageView image = (ImageView) holder.itemView;
            image.setContentDescription(image.getContext().getString(
                    R.string.rearview_guide_page, position + 1, PAGES.length));
            Glide.with(image).load(PAGES[position]).override(width, height).into(image);
        }

        @Override
        public int getItemCount() {
            return PAGES.length;
        }

        static final class Holder extends RecyclerView.ViewHolder {
            Holder(View view) {
                super(view);
            }
        }
    }
}
