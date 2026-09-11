package com.kooo.evcam.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.kooo.evcam.R;

import java.util.Locale;

/**
 * 录制键：左边一个会变形的点（圆 ⇄ 圆角方块）套一圈分段进度环，右边两行字。
 *
 * <h3>为什么不再是一整块红</h3>
 *
 * <p>红色的面积大，标的就成了「这个按钮」；红色只留在点和环上，标的才是
 * 「正在录」这件事。按钮本身的分量靠尺寸（它比旁边的方块大一档）撑着。</p>
 *
 * <h3>四个状态，一个动作讲清楚</h3>
 *
 * <ul>
 *   <li><b>待机</b>：红色圆点，环只剩轨道，「开始录制」。</li>
 *   <li><b>不可用</b>：灰色圆点 —— 没插 U 盘，按下去也不会录。和拒录用的是同一个判断
 *       （{@code StorageHelper.isRecordingStorageAvailable}），按钮不会说一套、做一套。</li>
 *   <li><b>准备中</b>：点已经收成方块、一明一暗地呼吸，环在转 —— 录制器还没起来。</li>
 *   <li><b>录制中</b>：方块，环按本段已录的比例走，走满一圈换下一段；
 *       换段的那一下，点淡一下再回来。</li>
 * </ul>
 *
 * <h3>动效只用属性动画</h3>
 *
 * <p>圆 ⇄ 方是同一个 {@link GradientDrawable} 的圆角半径和视图缩放一起插值 ——
 * 缩放不触发重新布局，圆角半径只重画这一块。底色切换是颜色插值。都是平台自带的
 * {@link ValueAnimator}，跟随系统的「动画时长缩放」设置，关了动画就直接跳到终态。
 * 这些都是状态本身，不算装饰，录制时也照常动（见 {@link MotionPolicy}）。</p>
 *
 * <p>自定义车型那几份布局里录制键还是一个普通按钮（没有点和环），这时退化成只改颜色。</p>
 */
public final class RecordButtonUi {

    public enum State { IDLE, UNAVAILABLE, PREPARING, RECORDING }

    private static final long MORPH_MS = 220;
    private static final long PULSE_MS = 600;
    private static final long FLASH_MS = 300;

    private final View root;
    private final Context context;
    private final MaterialCardView card;
    private final CircularProgressIndicator ring;
    private final View dot;
    private final TextView title;
    private final TextView detail;
    private final GradientDrawable dotShape;
    private final float density;

    private State state;
    /** 0 = 圆，1 = 方。 */
    private float morph;
    private int segmentMinutes = 1;

    private ValueAnimator morphAnimator;
    private ValueAnimator pulseAnimator;
    private ValueAnimator backgroundAnimator;
    private ValueAnimator flashAnimator;

    public RecordButtonUi(View root) {
        this.root = root;
        this.context = root.getContext();
        this.density = context.getResources().getDisplayMetrics().density;
        this.card = root instanceof MaterialCardView ? (MaterialCardView) root : null;
        this.ring = root.findViewById(R.id.record_ring);
        this.dot = root.findViewById(R.id.record_dot);
        this.title = root.findViewById(R.id.record_title);
        this.detail = root.findViewById(R.id.record_detail);
        if (dot != null) {
            dotShape = new GradientDrawable();
            dotShape.setColor(color(R.color.recording));
            dot.setBackground(dotShape);
            applyMorph(0f);
        } else {
            dotShape = null;
        }
        render(State.IDLE, false);
    }

    /** 分段时长（分钟），用于「每段 N 分钟」和「本段 mm:ss / N 分钟」。 */
    public void setSegmentMinutes(int minutes) {
        segmentMinutes = Math.max(1, minutes);
        if (state == State.IDLE) {
            showIdleDetail();
        }
    }

    public State getState() {
        return state;
    }

    public void setState(State next) {
        if (next != null && next != state) {
            render(next, true);
        }
    }

    /**
     * 本段已录多久。只在录制中生效。
     *
     * @param elapsedMs 本段开始到现在
     * @param segmentMs 一段有多长
     */
    public void setSegmentProgress(long elapsedMs, long segmentMs) {
        if (state != State.RECORDING || segmentMs <= 0) {
            return;
        }
        long clamped = Math.max(0, Math.min(elapsedMs, segmentMs));
        if (ring != null && !ring.isIndeterminate()) {
            ring.setProgressCompat((int) (clamped * 1000 / segmentMs), true);
        }
        if (detail != null) {
            detail.setText(context.getString(R.string.record_segment_detail,
                    mmss(clamped), segmentMinutes));
        }
    }

    /** 换段的一瞬：点淡下去再回来，环从零开始。 */
    public void flashSegment() {
        if (state != State.RECORDING || dot == null) {
            return;
        }
        if (ring != null && !ring.isIndeterminate()) {
            ring.setProgressCompat(0, false);
        }
        if (flashAnimator != null) {
            flashAnimator.cancel();
        }
        flashAnimator = ValueAnimator.ofFloat(1f, 0.45f, 1f);
        flashAnimator.setDuration(FLASH_MS);
        flashAnimator.addUpdateListener(a -> dot.setAlpha((float) a.getAnimatedValue()));
        flashAnimator.start();
    }

    // ------------------------------------------------------------------ 状态

    private void render(State next, boolean animate) {
        state = next;
        int description = next == State.IDLE ? R.string.record_start
                : next == State.UNAVAILABLE ? R.string.record_unavailable
                : R.string.record_stop;
        root.setContentDescription(context.getString(description));
        if (card == null) {
            renderPlainButton(next);
            return;
        }
        if (dotShape != null) {
            dotShape.setColor(color(next == State.UNAVAILABLE ? R.color.text_tertiary : R.color.recording));
        }
        switch (next) {
            case PREPARING:
                setIndeterminate(true);
                morphTo(1f, animate);
                startPulse();
                backgroundTo(R.color.recording_quiet, animate);
                setTitle(R.string.record_preparing);
                setDetail("");
                break;
            case RECORDING:
                stopPulse();
                setIndeterminate(false);
                if (ring != null) {
                    ring.setProgressCompat(0, false);
                }
                morphTo(1f, animate);
                backgroundTo(R.color.recording_quiet, animate);
                setTitle(R.string.record_stop);
                setDetail(context.getString(R.string.record_segment_detail, mmss(0), segmentMinutes));
                break;
            case UNAVAILABLE:
                stopPulse();
                setIndeterminate(false);
                if (ring != null) {
                    ring.setProgressCompat(0, animate);
                }
                morphTo(0f, animate);
                backgroundTo(R.color.sunken, animate);
                setTitle(R.string.record_unavailable);
                setDetail(context.getString(R.string.record_unavailable_detail));
                break;
            case IDLE:
            default:
                stopPulse();
                setIndeterminate(false);
                if (ring != null) {
                    ring.setProgressCompat(0, animate);
                }
                morphTo(0f, animate);
                backgroundTo(R.color.sunken, animate);
                setTitle(R.string.record_start);
                showIdleDetail();
                break;
        }
    }

    /**
     * 自定义车型那几份布局里的普通按钮：字不动（那几份用的是一个图标字符，
     * 换成「开始录制」四个字在 88dp 的方块里放不下），只改颜色。
     */
    private void renderPlainButton(State next) {
        if (!(root instanceof TextView)) {
            return;
        }
        int res;
        switch (next) {
            case IDLE:
                res = R.color.recording;
                break;
            case RECORDING:
                res = R.color.energy;
                break;
            default:
                res = R.color.text_tertiary;
                break;
        }
        ((TextView) root).setTextColor(color(res));
    }

    private void showIdleDetail() {
        setDetail(context.getString(R.string.record_idle_detail, segmentMinutes));
    }

    private void setTitle(int res) {
        if (title != null) {
            title.setText(res);
        }
    }

    private void setDetail(CharSequence text) {
        if (detail != null) {
            detail.setText(text);
        }
    }

    // ------------------------------------------------------------------ 动效

    /**
     * 环在「转圈」和「按比例走」之间切换。
     *
     * <p>先藏再换再露：有的版本在指示器可见时切模式会直接抛异常，
     * 与其赌版本，不如让它在切的那一瞬不可见。</p>
     */
    private void setIndeterminate(boolean on) {
        if (ring == null || ring.isIndeterminate() == on) {
            return;
        }
        int visibility = ring.getVisibility();
        ring.setVisibility(View.INVISIBLE);
        ring.setIndeterminate(on);
        ring.setVisibility(visibility);
    }

    private void morphTo(float target, boolean animate) {
        if (dotShape == null) {
            return;
        }
        if (morphAnimator != null) {
            morphAnimator.cancel();
        }
        if (!animate) {
            applyMorph(target);
            return;
        }
        morphAnimator = ValueAnimator.ofFloat(morph, target);
        morphAnimator.setDuration(MORPH_MS);
        // Material 的标准缓动（快出慢入），系统自带，不另引依赖
        morphAnimator.setInterpolator(new PathInterpolator(0.4f, 0f, 0.2f, 1f));
        morphAnimator.addUpdateListener(a -> applyMorph((float) a.getAnimatedValue()));
        morphAnimator.start();
    }

    /**
     * 0 = 26dp 的圆，1 = 20dp、圆角约 5dp 的方块。
     *
     * <p>尺寸用缩放而不是改宽高：缩放不触发重新布局。圆角半径写在未缩放的坐标里，
     * 所以终点是 6.5dp（乘上 0.77 的缩放正好约 5dp）。</p>
     */
    private void applyMorph(float t) {
        morph = t;
        float scale = 1f - 0.23f * t;
        dotShape.setCornerRadius((13f - 6.5f * t) * density);
        dot.setScaleX(scale);
        dot.setScaleY(scale);
    }

    private void startPulse() {
        if (dot == null || pulseAnimator != null) {
            return;
        }
        pulseAnimator = ValueAnimator.ofFloat(1f, 0.35f);
        pulseAnimator.setDuration(PULSE_MS);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.addUpdateListener(a -> dot.setAlpha((float) a.getAnimatedValue()));
        pulseAnimator.start();
    }

    private void stopPulse() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
        if (dot != null) {
            dot.setAlpha(1f);
        }
    }

    private void backgroundTo(int colorRes, boolean animate) {
        if (card == null) {
            return;
        }
        int to = color(colorRes);
        int from = card.getCardBackgroundColor().getDefaultColor();
        if (backgroundAnimator != null) {
            backgroundAnimator.cancel();
        }
        if (!animate || from == to) {
            card.setCardBackgroundColor(to);
            return;
        }
        backgroundAnimator = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        backgroundAnimator.setDuration(MORPH_MS);
        backgroundAnimator.addUpdateListener(
                a -> card.setCardBackgroundColor((int) a.getAnimatedValue()));
        backgroundAnimator.start();
    }

    // ------------------------------------------------------------------ 小工具

    private int color(int res) {
        return ContextCompat.getColor(context, res);
    }

    private static String mmss(long ms) {
        long seconds = ms / 1000;
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }
}
