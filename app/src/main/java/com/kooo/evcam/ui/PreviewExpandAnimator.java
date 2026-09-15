package com.kooo.evcam.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.PathInterpolator;

/**
 * 主界面「点一路放大」的过渡：从它原来在屏幕上的那一块长到铺满，收回时反过来。
 *
 * <h3>起点用量出来的矩形</h3>
 *
 * <p>起点和终点都是<b>实际量到的</b>屏幕矩形，不假设四宫格或者哪一种排法。以前三路布局里
 * 环视那一格是从「只有环视时」的格子位置长出来的，座舱两路干脆没有过渡。
 * 预览摆在哪以后由「摆位」来定，过渡不能写死某一种布局。</p>
 *
 * <h3>只动变换，不动布局</h3>
 *
 * <p>布局在调用前就一步改到位（别的藏起来、这一块铺满），过渡只改视图的缩放、平移和
 * 裁切框。TextureView 一改尺寸就会重设缓冲区大小（后视镜那边吃过这个亏），
 * 过渡里每帧改一次布局不行。</p>
 */
public final class PreviewExpandAnimator {

    /** 和四宫格里一格长大的过渡同一组数。 */
    private static final long DURATION_MS = 280L;

    private final Rect clip = new Rect();
    private ValueAnimator running;
    /** 每次开始或打断都加一：还没来得及开始的放大，被打断之后就不再开始。 */
    private int generation;

    /** 视图此刻在屏幕上的矩形 {left, top, width, height}。要在视图没有变换时量。 */
    public static float[] screenRect(View view) {
        int[] at = new int[2];
        view.getLocationOnScreen(at);
        return new float[]{at[0], at[1], view.getWidth(), view.getHeight()};
    }

    /**
     * 放大。调用时布局已经改好但还没画：等下一次绘制之前量出铺满之后的矩形，
     * 再从 {@code from} 长过去。第一帧就画在起点上，不会先闪一下铺满。
     */
    public void grow(View view, float[] from) {
        finishNow();
        if (view == null || from == null || !MotionPolicy.decorative(view.getContext())) {
            return;
        }
        final int mine = generation;
        final ViewTreeObserver observer = view.getViewTreeObserver();
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                if (observer.isAlive()) {
                    observer.removeOnPreDrawListener(this);
                } else {
                    view.getViewTreeObserver().removeOnPreDrawListener(this);
                }
                if (mine == generation) {
                    float[] to = screenRect(view);
                    start(view, to, from, to, null);
                }
                return true;
            }
        });
    }

    /**
     * 收回：从现在铺满的样子缩到 {@code to}，结束后跑 {@code onEnd}（由调用方还原布局）。
     * 不做动效时直接跑 {@code onEnd}。
     */
    public void shrink(View view, float[] to, Runnable onEnd) {
        finishNow();
        if (view == null || to == null || !MotionPolicy.decorative(view.getContext())) {
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }
        float[] from = screenRect(view);
        start(view, from, from, to, onEnd);
    }

    /** 立刻结束正在跑的过渡：视图回到没有变换的样子，收回的话布局也跟着还原。 */
    public void finishNow() {
        generation++;
        ValueAnimator animator = running;
        running = null;
        if (animator != null) {
            animator.end();
        }
    }

    /**
     * @param laidOut 视图排好之后的矩形：变换都按它算
     */
    private void start(View view, float[] laidOut, float[] from, float[] to, Runnable onEnd) {
        view.setPivotX(0f);
        view.setPivotY(0f);
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(DURATION_MS);
        animator.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        animator.addUpdateListener(animation -> apply(view, laidOut,
                ExpandGeometry.lerp(from, to, (float) animation.getAnimatedValue())));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (running == animation) {
                    running = null;
                }
                reset(view);
                if (onEnd != null) {
                    onEnd.run();
                }
            }
        });
        apply(view, laidOut, from);
        running = animator;
        animator.start();
    }

    private void apply(View view, float[] laidOut, float[] shown) {
        ExpandGeometry frame = ExpandGeometry.frame(laidOut, shown);
        view.setScaleX(frame.scale);
        view.setScaleY(frame.scale);
        view.setTranslationX(frame.translationX);
        view.setTranslationY(frame.translationY);
        clip.set(Math.round(frame.clipLeft), Math.round(frame.clipTop),
                Math.round(frame.clipRight), Math.round(frame.clipBottom));
        view.setClipBounds(clip);
    }

    private static void reset(View view) {
        view.setScaleX(1f);
        view.setScaleY(1f);
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setClipBounds(null);
    }
}
