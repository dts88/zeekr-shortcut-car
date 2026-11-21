/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;

/**
 * 回到前台的提示（docs/ui-spec.md §十九）：主界面是应用自己带到前面的（要用相机，lifecycle-spec §2.5），不是人打开的 ——
 * 画面顶边一条横幅说明，带倒数；没人碰屏幕，数完退回后台；有人碰了屏幕就留在前面。
 *
 * <p>项目所有者 2026-11-21：原来主界面起来约一秒就退回去，「用户不知道发生了什么，还以为 App 出现了错误」。</p>
 *
 * <h3>三种样子</h3>
 * <ul>
 *   <li>没有；</li>
 *   <li>在数：横幅在，窗口就绪（{@link #start}）之后每秒减一；</li>
 *   <li>走了：数完、或者还在数时界面被盖住 / 离开了 —— 已经请主界面退回后台，到它下一次回到前台为止。</li>
 * </ul>
 *
 * <p>后两种都是「这一趟在前面不是人要的」（{@link #visiting}）：这期间熄屏，主界面不记「因熄屏退下去」，
 * 不然下次亮屏它被接回来留在前面。</p>
 *
 * <p>主界面只把触摸、新来的意图、暂停、恢复、窗口就绪告诉这里；谁把它带到前面、带不带，不在这里
 * （{@code Recovery.bringUpMainForRecording}）。只在主线程上用。</p>
 */
public final class ReturnNotice {

    /** 数几秒（项目所有者 2026-11-21）。 */
    public static final int SECONDS = 3;
    private static final long TICK_MS = 1_000L;

    /** 主界面。 */
    public interface Host {
        /**
         * 退回后台。
         *
         * @return 退了。没退成的（界面正在关）这一趟到此为止，不再算「不是人要的」
         */
        boolean leave();
    }

    private enum State { OFF, COUNTING, LEFT }

    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::tick;
    private final Runnable leaveIfStillAway = this::leaveIfStillAway;
    private State state = State.OFF;
    private int left;
    private boolean ticking;
    /** 留下的那一下触摸：从按下到抬起整个吞掉。 */
    private boolean swallowing;
    private View banner;
    private TextView text;

    public ReturnNotice(Host host) {
        this.host = host;
    }

    /**
     * 开始一次：横幅放到 {@code stage} 的顶边，写上还剩几秒。窗口就绪（{@link #start}）之后才开始数。
     * 上一次的还在就换掉。
     *
     * @param stage   放在哪：画面区，或者摆着设置这类页面时那一页的容器
     * @param seconds 数几秒：新的一次是 {@link #SECONDS}，界面重建后接着数的是剩下的
     */
    public void show(ViewGroup stage, int seconds) {
        stopTicking();
        handler.removeCallbacks(leaveIfStillAway);
        detach(false);
        View view = LayoutInflater.from(stage.getContext()).inflate(R.layout.layout_return_notice, stage, false);
        // 主界面不镜像（ui-spec §十七），这一条是文字：里面照界面的语言排
        view.setLayoutDirection(view.getResources().getConfiguration().getLayoutDirection());
        stage.addView(view);
        banner = view;
        text = view.findViewById(R.id.return_notice_text);
        left = Math.max(1, Math.min(seconds, SECONDS));
        state = State.COUNTING;
        render();
        BlackBox.noteImportant("回到前台的提示：主界面是应用带到前面的，" + left + " 秒没人碰屏幕就退回后台");
    }

    /** 窗口就绪：开始数。没有横幅、已经在数的不动。 */
    public void start() {
        if (state == State.COUNTING && !ticking) {
            ticking = true;
            handler.postDelayed(tick, TICK_MS);
        }
    }

    /**
     * 有人碰了屏幕，或者人自己要它到前面（点通知、悬浮按钮和灵动贴的动作）：不退了，横幅收起，主界面留在前面。
     * 按键不算（方向盘上的键、系统的返回键不是在操作这个界面；提示写的也是「点击屏幕」）。
     *
     * @param how 怎么留下的（ASCII，进黑匣子）
     * @return 这一下算数（原来在数）
     */
    public boolean stay(String how) {
        if (state != State.COUNTING) {
            return false;
        }
        stopTicking();
        state = State.OFF;
        detach(true);
        BlackBox.noteImportant("回到前台的提示：有人要它留下（" + how + "），主界面留在前面");
        return true;
    }

    /**
     * 触摸事件先经过这里（主界面的 dispatchTouchEvent）。在数的时候按下的那一下算「留下」，从按下到抬起整个吞掉，不往下传：
     * 提示请人点屏幕，点在画面上不该顺手放大那一格，点在录制键上不该顺手停录。
     *
     * <p>「走了」以后主界面还被人碰到（请它退回后台而它没退成、还在前面）：这一趟到此为止，这一下照常往下传。</p>
     *
     * @return 这一下吞掉了
     */
    public boolean takes(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (state == State.LEFT) {
                state = State.OFF;
            }
            swallowing = stay("touch");
            return swallowing;
        }
        boolean was = swallowing;
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            swallowing = false;
        }
        return was;
    }

    /**
     * 主界面暂停了，或者停下了（没到过前台就被盖住的没有暂停那一下，主界面在 onStop 里也叫）。还在数的：界面在重建
     * （日夜切换、换语言、照存下的配置重摆）就只停表，新界面照剩下的秒数接着数（{@link #secondsLeft}）；别的都是界面被盖住或
     * 离开了 —— 这一次算数完，退回后台，不然盖住它的界面一走主界面露出来、留在前面。安卓送来新意图时先暂停再恢复，
     * 那不算离开：等这一轮生命周期走完再看（{@link #resumed}）。
     */
    public void paused(boolean rebuilding) {
        if (state != State.COUNTING) {
            return;
        }
        stopTicking();
        if (rebuilding) {
            return;
        }
        detach(false);
        state = State.LEFT;
        handler.post(leaveIfStillAway);
    }

    /** 主界面回到前台了：上一趟「走了」到此为止，从这一下起它在前面就和平常一样。 */
    public void resumed() {
        if (state == State.LEFT) {
            state = State.OFF;
        }
    }

    /** 这一趟在前面不是人要的（在数，或者数完、被盖住后已经请它退回后台而还没再回到前台）。 */
    public boolean visiting() {
        return state != State.OFF;
    }

    /** 还在数的话还剩几秒，否则 0。界面重建时带给新界面。 */
    public int secondsLeft() {
        return state == State.COUNTING ? left : 0;
    }

    /** 主界面销毁：表停掉，横幅拿掉。 */
    public void drop() {
        handler.removeCallbacksAndMessages(null);
        ticking = false;
        swallowing = false;
        detach(false);
        state = State.OFF;
    }

    private void tick() {
        ticking = false;
        if (state != State.COUNTING) {
            return;
        }
        left--;
        if (left > 0) {
            render();
            start();
            return;
        }
        detach(false);
        state = State.LEFT;
        BlackBox.noteImportant("回到前台的提示：" + SECONDS + " 秒没人碰屏幕，主界面退回后台");
        if (!host.leave()) {
            state = State.OFF;
        }
    }

    private void leaveIfStillAway() {
        if (state != State.LEFT) {
            return;
        }
        BlackBox.noteImportant("回到前台的提示：还在数时界面被盖住或离开了，主界面退回后台");
        if (!host.leave()) {
            state = State.OFF;
        }
    }

    private void stopTicking() {
        handler.removeCallbacks(tick);
        ticking = false;
    }

    private void render() {
        if (text != null) {
            text.setText(text.getResources().getQuantityString(R.plurals.return_notice_text, left, left));
        }
    }

    /** 拿掉横幅；{@code fade}：淡出（「悬浮窗出现、收起」那一档），不放装饰动效时直接拿掉。 */
    private void detach(boolean fade) {
        View view = banner;
        banner = null;
        text = null;
        if (view == null) {
            return;
        }
        view.animate().cancel();
        if (fade && MotionPolicy.decorative(view.getContext())) {
            view.animate().alpha(0f).setDuration(Motion.FAST).setInterpolator(Motion.exit())
                    .withEndAction(() -> remove(view)).start();
        } else {
            remove(view);
        }
    }

    private static void remove(View view) {
        if (view.getParent() instanceof ViewGroup) {
            ((ViewGroup) view.getParent()).removeView(view);
        }
    }
}
