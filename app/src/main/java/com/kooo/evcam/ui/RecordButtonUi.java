/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.widget.ImageViewCompat;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.kooo.evcam.R;
import com.kooo.evcam.button.SentrySwitch;
import com.kooo.evcam.camera.CameraKeeper;

import java.util.Locale;

/**
 * 录制键：左边一个会变形的点（圆 ⇄ 圆角方块）套一圈分段进度环，右边两行字；
 * 录制中、等待摄像头时多一行小字，说现在熄屏的话这段录像会怎样（{@link ScreenOffHintView}，这里只管显隐）。
 *
 * <h3>为什么不再是一整块红</h3>
 *
 * <p>红色的面积大，标的就成了「这个按钮」；红色只留在点和环上，标的才是
 * 「正在录」这件事。按钮本身的分量靠尺寸（它比旁边的方块大一档）撑着。</p>
 *
 * <h3>五个状态，一个动作讲清楚</h3>
 *
 * <ul>
 *   <li><b>待机</b>：红色圆点，环只剩轨道，「开始录制」。</li>
 *   <li><b>不可用</b>：灰色圆点 —— 没插 U 盘，按下去也不会录。和拒录用的是同一个判断
 *       （{@code StorageHelper.isRecordingStorageAvailable}），按钮不会说一套、做一套。</li>
 *   <li><b>准备中</b>：点已经收成方块、一明一暗地呼吸，环在转 —— 录制器还没起来。</li>
 *   <li><b>录制中</b>：方块，环按本段已录的比例走，走满一圈换下一段；
 *       换段的那一下，点淡一下再回来。</li>
 *   <li><b>等待摄像头</b>：录像开着、一路都没在录（都被占用、在救，2.11 确认文档 §三）—— 录像不算停。
 *       灰色方块慢慢地一明一暗，环不转、不走，不显示本段时长；按下去仍然是停止录制。红色只标「正在录」。</li>
 * </ul>
 *
 * <h3>动效只用属性动画</h3>
 *
 * <p>圆 ⇄ 方是同一个 {@link GradientDrawable} 的圆角半径和视图缩放一起插值 ——
 * 缩放不触发重新布局，圆角半径只重画这一块。底色切换是颜色插值。都是平台自带的
 * {@link ValueAnimator}，跟随系统的「动画时长缩放」设置，关了动画就直接跳到终态。
 * 这些都是状态本身，不算装饰，录制时也照常动（见 {@link MotionPolicy}）。</p>
 *
 * <h3>右边四分之一有时是另一个键：「开启哨兵模式」（项目所有者 2026-11-13）</h3>
 *
 * <p>录着（写着「停止录制」）、别的条件都满足、只差车的哨兵模式没开就能熄屏接着录的时候，录制键的右边四分之一切出来
 * 一个键，点了打开哨兵模式；开上之后它变成对勾、滑出去，录制键回到原来的宽。什么时候算「只差哨兵模式」由相机管家说
 * （{@link CameraKeeper#onlySentryMissing}），和第三行小字同一刻、照同一份事实判（{@link ScreenOffHintView#setOnJudged}）：
 * 键在的时候小字写的一定是「熄屏暂停」或「熄屏后停止」，哨兵模式一开两样一起变。怎么分、怎么滑是 {@link RecordKeyRow} 的事，
 * 打开哨兵模式走灵动贴那条写车的路（{@link SentrySwitch}）。</p>
 *
 * <p>分出去之后字那一块只剩一半多宽：留白收一点，第二行去掉「本段」两个字，第一行放不下的语言把字缩小。</p>
 */
public final class RecordButtonUi {

    public enum State { IDLE, UNAVAILABLE, PREPARING, RECORDING, WAITING }

    private static final long MORPH_MS = Motion.MEDIUM;
    private static final long PULSE_MS = 600;
    /** 等待摄像头时的呼吸：比准备中慢得多 —— 不是马上就好，是在等。 */
    private static final long WAITING_PULSE_MS = 1_600;
    private static final long FLASH_MS = Motion.SLOW;

    /** 「开启哨兵模式」那个键的样子：没有、可以点、点了在等车回话、读回是开着的（对勾）。 */
    private enum SentryKey { HIDDEN, OFFERED, BUSY, DONE }

    /**
     * 读回是开着的之后，车辆信号那一份（哨兵模式要稳住 2 秒才算，{@code VehicleStateMapper}）最多等这么久跟上、键收起；
     * 过了还没跟上（车又把它关了）键回到可以点。
     */
    private static final long SENTRY_CONFIRM_MS = 5_000;
    /** 分出去之后录制键里的留白各收多少 dp：左边、点和字之间、右边。 */
    private static final float SPLIT_PAD_START_DP = 4f;
    private static final float SPLIT_TEXT_GAP_DP = 4f;
    private static final float SPLIT_PAD_END_DP = 8f;
    /** 分出去之后第一行最小缩到多大（sp）；平时是 {@code text_title}。 */
    private static final int COMPACT_TITLE_MIN_SP = 18;

    private final View root;
    private final Context context;
    private final MaterialCardView card;
    private final CircularProgressIndicator ring;
    private final View dot;
    private final TextView title;
    private final TextView detail;
    private final View hint;
    private final GradientDrawable dotShape;
    private final float density;

    /** 录制键那一行（不在那种布局里时是 null，就没有「开启哨兵模式」）。 */
    private final RecordKeyRow row;
    private final View sentryKey;
    private final ImageView sentryIcon;
    private final View sentryLabel;
    /** 录制键里面那一排（左右留白在它身上）和右边那几行字（和点之间的距离在它身上）。 */
    private final View content;
    private final View texts;
    private final int padStart;
    private final int padEnd;
    private final int textGap;
    private final float titlePx;
    private final TextPaint fitPaint = new TextPaint();
    private SentryKey sentry = SentryKey.HIDDEN;
    /** 右边分出去了（哪怕只分了一点）：第二行用短的写法、第一行可以缩小。 */
    private boolean compact;
    /** 第二行此刻写的本段已录多久。 */
    private long shownElapsedMs;
    private final Runnable sentryUnconfirmed = () -> {
        if (sentry == SentryKey.DONE) {
            showSentry(SentryKey.OFFERED);
        }
    };

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
        this.hint = root.findViewById(R.id.record_hint);
        this.content = root.findViewById(R.id.record_content);
        this.texts = root.findViewById(R.id.record_texts);
        this.padStart = content == null ? 0 : content.getPaddingStart();
        this.padEnd = content == null ? 0 : content.getPaddingEnd();
        this.textGap = texts != null && texts.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) texts.getLayoutParams()).getMarginStart() : 0;
        this.titlePx = title == null ? 0f : title.getTextSize();
        this.row = root.getParent() instanceof RecordKeyRow ? (RecordKeyRow) root.getParent() : null;
        this.sentryKey = row == null ? null : row.findViewById(R.id.btn_sentry_on);
        this.sentryIcon = sentryKey == null ? null : sentryKey.findViewById(R.id.sentry_key_icon);
        this.sentryLabel = sentryKey == null ? null : sentryKey.findViewById(R.id.sentry_key_label);
        if (sentryKey != null) {
            // 第一行的宽变了（右边分出去、收回来）：等这一次排完再看字放不放得下
            title.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol) {
                    title.post(this::fitTitle);
                }
            });
            row.setOnSplitListener(this::onSplit);
            sentryKey.setOnClickListener(v -> turnSentryOn());
            if (hint instanceof ScreenOffHintView) {
                ((ScreenOffHintView) hint).setOnJudged(this::judgeSentry);
            }
        }
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
        shownElapsedMs = clamped;
        showSegmentDetail();
    }

    /** 第二行「本段 mm:ss / N 分钟」；右边分出去时放不下，去掉「本段」。 */
    private void showSegmentDetail() {
        setDetail(context.getString(compact ? R.string.record_segment_detail_short : R.string.record_segment_detail,
                mmss(shownElapsedMs), segmentMinutes));
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
        if (hint != null) {
            // 等待摄像头时录像也开着：熄屏的话这段录像会怎样，照常说
            hint.setVisibility(next == State.RECORDING || next == State.WAITING ? View.VISIBLE : View.GONE);
        }
        int description = next == State.IDLE ? R.string.record_start
                : next == State.UNAVAILABLE ? R.string.record_unavailable
                : R.string.record_stop;
        root.setContentDescription(context.getString(description));
        if (dotShape != null) {
            dotShape.setColor(color(next == State.UNAVAILABLE || next == State.WAITING
                    ? R.color.text_tertiary : R.color.recording));
        }
        switch (next) {
            case PREPARING:
                setIndeterminate(true);
                morphTo(1f, animate);
                startPulse(PULSE_MS);
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
                shownElapsedMs = 0;
                showSegmentDetail();
                break;
            case WAITING:
                setIndeterminate(false);
                if (ring != null) {
                    ring.setProgressCompat(0, animate);
                }
                morphTo(1f, animate);
                startPulse(WAITING_PULSE_MS);
                backgroundTo(R.color.sunken, animate);
                setTitle(R.string.record_waiting_cameras);
                setDetail(context.getString(R.string.record_waiting_detail));
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
        // 「开启哨兵模式」只在写着「停止录制」时有：别的状态收起；到了录制中，小字判的那一下会来叫（judgeSentry），
        // 小字本来就看得见、不会再判的时候这里自己判一次
        if (next != State.RECORDING) {
            setSentryOffered(false);
        } else if (hint != null && hint.isShown()) {
            judgeSentry();
        }
    }

    // ------------------------------------------------------------------ 「开启哨兵模式」

    /** 小字每判一次就来一下：照同一刻的车和设置，看那个键该不该在。 */
    private void judgeSentry() {
        if (sentryKey == null) {
            return;
        }
        setSentryOffered(state == State.RECORDING && CameraKeeper.get(context).onlySentryMissing());
    }

    /**
     * 那个键该不该在。该在：还没有就从右边滑进来（主界面刚打开时这一行是收着的，也滑一次）；不该在：留着此刻的样子
     * （点过的是对勾）滑出去。平时由 {@link #judgeSentry} 叫；截图测试直接叫。
     */
    public void setSentryOffered(boolean offered) {
        if (sentryKey == null) {
            return;
        }
        if (offered) {
            if (sentry == SentryKey.HIDDEN) {
                showSentry(SentryKey.OFFERED);
            }
            row.splitTo(true, true);
            return;
        }
        if (sentry != SentryKey.HIDDEN) {
            sentry = SentryKey.HIDDEN;
            sentryKey.removeCallbacks(sentryUnconfirmed);
            sentryKey.setClickable(false);
        }
        row.splitTo(false, true);
    }

    /** 点了那个键：写车、读回（{@link SentrySwitch}）。没做成它自己提示一句，键回到可以点。 */
    private void turnSentryOn() {
        if (sentry != SentryKey.OFFERED) {
            return;
        }
        showSentry(SentryKey.BUSY);
        SentrySwitch.turnOn(context, on -> {
            if (sentry != SentryKey.BUSY) {
                return;  // 等车回话的工夫键已经收起了（开走了、停录了）
            }
            if (on) {
                showSentry(SentryKey.DONE);
                sentryKey.postDelayed(sentryUnconfirmed, SENTRY_CONFIRM_MS);
            } else {
                showSentry(SentryKey.OFFERED);
            }
        });
    }

    private void showSentry(SentryKey next) {
        sentry = next;
        sentryKey.removeCallbacks(sentryUnconfirmed);
        sentryKey.setClickable(next == SentryKey.OFFERED);
        boolean done = next == SentryKey.DONE;
        float dim = next == SentryKey.BUSY ? 0.5f : 1f;
        if (sentryIcon != null) {
            sentryIcon.setImageResource(done ? R.drawable.ic_check : R.drawable.ic_sentry);
            // 两个图标自带的线色不一样（盾是正文色、勾是能量色），在能量色的底上都着成底上的字色
            ImageViewCompat.setImageTintList(sentryIcon,
                    android.content.res.ColorStateList.valueOf(color(R.color.on_energy)));
            sentryIcon.setAlpha(dim);
        }
        if (sentryLabel != null) {
            // 开上了：只留一个对勾在正中
            sentryLabel.setVisibility(done ? View.GONE : View.VISIBLE);
            sentryLabel.setAlpha(dim);
        }
    }

    /** 右边分出去多少变了（{@link RecordKeyRow}，0–1）：留白跟着收，一分出去第二行就换短的写法、第一行可以缩小（{@link #fitTitle}）。 */
    private void onSplit(float split) {
        if (content != null) {
            content.setPaddingRelative(padStart - Math.round(SPLIT_PAD_START_DP * density * split),
                    content.getPaddingTop(), padEnd - Math.round(SPLIT_PAD_END_DP * density * split),
                    content.getPaddingBottom());
        }
        if (texts != null && texts.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) texts.getLayoutParams();
            int gap = textGap - Math.round(SPLIT_TEXT_GAP_DP * density * split);
            if (params.getMarginStart() != gap) {
                params.setMarginStart(gap);
                texts.setLayoutParams(params);
            }
        }
        boolean narrow = split > 0f;
        if (narrow == compact) {
            return;
        }
        compact = narrow;
        fitTitle();
        if (state == State.RECORDING) {
            showSegmentDetail();
        }
    }

    private void showIdleDetail() {
        setDetail(context.getString(R.string.record_idle_detail, segmentMinutes));
    }

    private void setTitle(int res) {
        if (title != null) {
            title.setText(res);
            fitTitle();
        }
    }

    /**
     * 第一行的字多大：平时是原来的大小；右边分出去之后字那一块窄了，放不下的语言（英文、马来文）缩到刚好放下，
     * 最小 {@link #COMPACT_TITLE_MIN_SP}。中文四个字放得下，不变。
     */
    private void fitTitle() {
        if (title == null || titlePx <= 0f) {
            return;
        }
        float size = titlePx;
        int room = title.getWidth() - title.getPaddingStart() - title.getPaddingEnd();
        if (compact && room > 0 && title.length() > 0) {
            fitPaint.set(title.getPaint());
            fitPaint.setTextSize(titlePx);
            float need = fitPaint.measureText(title.getText(), 0, title.length());
            if (need > room) {
                float smallest = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, COMPACT_TITLE_MIN_SP,
                        context.getResources().getDisplayMetrics());
                size = Math.max(smallest, titlePx * room / need);
            }
        }
        if (Math.abs(title.getTextSize() - size) > 0.5f) {
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, size);
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

    /** 点一明一暗；已经在按这个节奏呼吸就不动，换了节奏（准备中 ⇄ 等待摄像头）从头起。 */
    private void startPulse(long periodMs) {
        if (dot == null || (pulseAnimator != null && pulseAnimator.getDuration() == periodMs)) {
            return;
        }
        stopPulse();
        pulseAnimator = ValueAnimator.ofFloat(1f, 0.35f);
        pulseAnimator.setDuration(periodMs);
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
