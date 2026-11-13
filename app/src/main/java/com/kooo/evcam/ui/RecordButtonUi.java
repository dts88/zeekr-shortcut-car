/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.text.TextPaint;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
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
import com.kooo.evcam.telemetry.Telemetry;

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
 *       换段的那一下，点淡一下再回来。第二行写「录制中」和这一次已经录了多久（项目所有者 2026-11-13：原来是
 *       「本段 mm:ss / N 分钟」，字多，英文里连下面的小字都挤得放不下；本段走到哪了环上本来就有）。</li>
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
 * <h3>右边有时切出另一个键：「开启哨兵模式」（项目所有者 2026-11-13）</h3>
 *
 * <p>录着（写着「停止录制」）、别的条件都满足、只差车的哨兵模式没开就能熄屏接着录的时候，录制键的右边切出来
 * 一个键，点了打开哨兵模式；开上之后图标从没开变成开着（灯芯和光变红），然后滑出去，录制键回到原来的宽。
 * 亮着、还没滑出去一半时再点一下是反悔，把哨兵模式关掉（项目所有者 2026-11-14）。
 * 什么时候算「只差哨兵模式」由相机管家说
 * （{@link CameraKeeper#onlySentryMissing}），和第三行小字同一刻、照同一份事实判（{@link ScreenOffHintView#setOnJudged}）：
 * 键在的时候小字写的一定是「熄屏暂停」或「熄屏后停止」，哨兵模式一开两样一起变。怎么分、怎么滑是 {@link RecordKeyRow} 的事，
 * 打开哨兵模式走灵动贴那条写车的路（{@link SentrySwitch}）。</p>
 *
 * <p>分出去之后字那一块窄了：留白收一点，第一行放不下的语言把字缩小。</p>
 */
public final class RecordButtonUi {

    public enum State { IDLE, UNAVAILABLE, PREPARING, RECORDING, WAITING }

    private static final long MORPH_MS = Motion.MEDIUM;
    private static final long PULSE_MS = 600;
    /** 等待摄像头时的呼吸：比准备中慢得多 —— 不是马上就好，是在等。 */
    private static final long WAITING_PULSE_MS = 1_600;
    private static final long FLASH_MS = Motion.SLOW;

    /**
     * 「开启哨兵模式」那个键的样子：没有；可以点（没开）；点了、在等车回话；开上了（图标点亮，再点是反悔）；
     * 反悔了、在等车回话。没开、亮着两样只照车的读数定（{@link #setSentryKey}）。
     */
    private enum SentryKey { HIDDEN, OFFERED, TURNING_ON, LIT, TURNING_OFF }

    /**
     * 图标从没开到开着的过渡（项目所有者 2026-11-14）：不是换个颜色，是一段动画 —— 红的灯芯像警灯一样从左到右转过去，
     * 转 {@link #SENTRY_SWEEPS} 圈（红和本色交替），再转进来、定住，整颗是红的；头顶三道光从靠灯的一头往外长成红的；
     * 末了整个图标鼓一下再收回。一共 1.5 秒，比别处的动效（最长 300 ms）长得多 —— 项目所有者定的：
     * 「有一个充裕的时间展示图标的变化过程」。是状态本身在变，不算装饰（同录制键的圆变方），录制时也照常放；
     * 系统关了动画时直接到开着的样子。
     */
    private static final long SENTRY_LIGHT_MS = 5 * Motion.SLOW;
    /** 从开着变回没开（反悔关掉了）：三道光缩回去、红的灯芯退出去，不转圈，一档。 */
    private static final long SENTRY_UNLIGHT_MS = Motion.SLOW;
    /** 点亮之后至少让人看这么久，键才收起。 */
    private static final long SENTRY_LIT_HOLD_MS = 2 * Motion.SLOW;
    /** 过渡里各段占哪一截（0–1）。灯芯转圈：从头到这里，转这么多圈。 */
    static final float SENTRY_SWEEP_BY = 0.54f;
    static final int SENTRY_SWEEPS = 3;
    /** 转圈时露出红色的那个窗口有多宽（灯芯宽的几成）。 */
    static final float SENTRY_BAND = 0.5f;
    /** 灯芯定住：转完圈之后红的再从左边进来，到这里铺满整颗。 */
    static final float SENTRY_FILL_BY = 0.72f;
    /** 三道光往外长：从这里到这里（和灯芯定住搭着一点）。 */
    static final float SENTRY_RAYS_FROM = 0.66f;
    static final float SENTRY_RAYS_BY = 0.92f;
    /** 整个图标从这里开始鼓，到头收回。 */
    private static final float SENTRY_SWELL_FROM = 0.66f;
    /** 鼓到最大时比原来大多少。 */
    private static final float SENTRY_SWELL = 0.12f;
    /** 图标画布上的位置（{@code ic_sentry_*.xml}，画布宽 24 格）：灯芯左右两边。 */
    private static final float ICON_UNITS = 24f;
    private static final float LAMP_LEFT = 9.1f;
    private static final float LAMP_RIGHT = 14.9f;
    /** 红的三道光那个窗口：没露时左、上、右三边贴着灯罩（光都在外面）；露完到画布边。右边和左边对称。 */
    private static final float RAYS_LEFT_IN = 5.3f;
    private static final float RAYS_LEFT_OUT = 1.4f;
    private static final float RAYS_TOP_IN = 4.7f;
    private static final float RAYS_TOP_OUT = 0.6f;
    /** 键往外滑的时候，滑出去不到一半（分出去的还剩这么多以上）还点得到：反悔。 */
    static final float SENTRY_CANCEL_UNTIL = 0.5f;

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
    /**
     * 图标那一小块，里面五层叠着：三道光、灯罩和底座、灯芯是图标的本色，不变；灯芯、三道光各另有一份红的盖在上面，
     * 平时不露，点亮时按窗口露出来（{@link #applyLit}）。
     */
    private final View sentryIcon;
    private final ImageView sentryLampOn;
    private final ImageView sentryRaysOn;
    private final Rect sentryClip = new Rect();
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
    /** 右边分出去了（哪怕只分了一点）：第一行可以缩小。 */
    private boolean compact;
    /** 这一次录像已经录了多久（第二行写的）。 */
    private long recordedMs;
    private ValueAnimator lightAnimator;
    /** 过渡正往哪个样子去（{@link #lightAnimator} 在走时才有意义）。 */
    private float lightTarget;
    /** 图标此刻的样子：0 = 没开，1 = 开着。 */
    private float lit;
    /** 点亮的过渡走完的时刻（{@link SystemClock#uptimeMillis}）。 */
    private long litAt;
    /** 哨兵模式开上了、键在等点亮放完再收起。 */
    private boolean pendingLeave;
    private final Runnable sentryLeave = this::leaveNow;

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
        this.sentryLampOn = sentryKey == null ? null : sentryKey.findViewById(R.id.sentry_key_lamp_on);
        this.sentryRaysOn = sentryKey == null ? null : sentryKey.findViewById(R.id.sentry_key_rays_on);
        this.sentryLabel = sentryKey == null ? null : sentryKey.findViewById(R.id.sentry_key_label);
        if (sentryKey != null) {
            // 第一行的宽变了（右边分出去、收回来）：等这一次排完再看字放不放得下
            title.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
                if (r - l != or - ol) {
                    title.post(this::fitTitle);
                }
            });
            row.setOnSplitListener(this::onSplit);
            sentryKey.setOnClickListener(v -> onSentryKeyTapped());
            // 本色的三层是正文色（夜间接近白；白天是深色：灰底上白的看不见），红的两层用「正在录」那一种红
            // （项目所有者 2026-11-14 给的图样：白色的灯座、红色的灯和光）
            ColorStateList base = ColorStateList.valueOf(color(R.color.text_primary));
            ColorStateList red = ColorStateList.valueOf(color(R.color.recording));
            for (int id : new int[] {R.id.sentry_key_rays, R.id.sentry_key_body, R.id.sentry_key_lamp}) {
                ImageViewCompat.setImageTintList(sentryKey.findViewById(id), base);
            }
            ImageViewCompat.setImageTintList(sentryLampOn, red);
            ImageViewCompat.setImageTintList(sentryRaysOn, red);
            applyLit(0f);
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

    /** 分段时长（分钟），用于待机时的「每段 N 分钟」。 */
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
     * 本段已录多久：外圈的环照它走。只在录制中生效。
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
    }

    /**
     * 这一次录像已经录了多久（从开录算，跨分段）：第二行「录制中 mm:ss」，满一小时写成「h:mm:ss」。
     * 录制中才写；等待摄像头时记着，回到录制中接着写。
     */
    public void setRecordedTime(long totalMs) {
        recordedMs = Math.max(0, totalMs);
        if (state == State.RECORDING) {
            showRecorded();
        }
    }

    private void showRecorded() {
        setDetail(context.getString(R.string.record_recording_detail, clock(recordedMs)));
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
        if (next == State.IDLE || next == State.UNAVAILABLE || next == State.PREPARING) {
            recordedMs = 0;  // 下一次录像从头数
        }
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
                showRecorded();
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
            setSentryKey(false, false);
        } else if (hint != null && hint.isShown()) {
            judgeSentry();
        }
    }

    // ------------------------------------------------------------------ 「开启哨兵模式」

    /**
     * 小字每判一次就来一下，写车的那一下回话之后也来一下（{@link #settleSentry}）：照车此刻的读数和设置，看那个键该不该在；
     * 不该在了是不是因为哨兵模式开着。哨兵模式的读数和相机管家用的是同一份（车辆接口没连着算读不到）。
     */
    private void judgeSentry() {
        if (sentryKey == null) {
            return;
        }
        boolean recording = state == State.RECORDING;
        Telemetry telemetry = Telemetry.get();
        Integer reading = telemetry.carLinkConnected() ? telemetry.latest().sentry : null;
        setSentryKey(recording && CameraKeeper.get(context).onlySentryMissing(),
                recording && reading != null && (reading == 1 || reading == 2));
    }

    /**
     * 照这两样摆那个键。键的样子只跟着车的实际读数走，自己不猜、不计时 —— 2026-11-14 实车：原来点了之后自己记着
     * 「读回开着、5 秒里车辆信号没跟上就退回没开」「刚关掉的 5 秒不认开着的读数」，是照「哨兵模式的读数要稳住 2 秒」
     * 写的；实际读数一百毫秒内就到，连点几下，真的读数落进那 5 秒被丢掉，键就和车对不上了（车上开着、键是没开的样子）。
     *
     * <ul>
     *   <li>该在（只差哨兵模式，也就是读到它关着）：没开的样子、可以点。还没有就从右边滑进来（主界面刚打开时这一行是
     *       收着的，也滑一次）；亮着的变回没开，正往外滑的拉回来。</li>
     *   <li>不该在了、哨兵模式开着（点这个键开的、别处开的都算）：点亮，让人看清（也来得及反悔），再收起。</li>
     *   <li>不该在了、别的原因（开走了、停录了、设置改了、读不到了）：直接收起。</li>
     * </ul>
     *
     * <p>点了之后等车回话的那一小会儿（{@link SentryKey#TURNING_ON}、{@link SentryKey#TURNING_OFF}）键是淡的、不能点：
     * 这时来的读数已经是要的那个样子就照它定，还不是就接着等回话。</p>
     *
     * <p>平时由 {@link #judgeSentry} 叫；截图测试直接叫。</p>
     *
     * @param offered  只差哨兵模式（{@link CameraKeeper#onlySentryMissing}）
     * @param sentryOn 哨兵模式此刻读到是开着或布防
     */
    public void setSentryKey(boolean offered, boolean sentryOn) {
        if (sentryKey == null) {
            return;
        }
        if (offered) {
            if (sentry == SentryKey.TURNING_ON) {
                return;  // 写开的那一下还没回话，读数自然还是关着：等它
            }
            cancelPendingLeave();
            if (sentry != SentryKey.OFFERED) {
                showSentry(SentryKey.OFFERED);
            }
            row.splitTo(true, true);
            return;
        }
        if (sentryOn) {
            if (sentry == SentryKey.HIDDEN || sentry == SentryKey.TURNING_OFF) {
                return;  // 没有这个键；写关的那一下还没回话，读数自然还是开着：等它
            }
            if (sentry != SentryKey.LIT) {
                showSentry(SentryKey.LIT);
            }
            if (!pendingLeave) {
                leaveAfterLit();
            }
            return;
        }
        cancelPendingLeave();
        if (sentry != SentryKey.HIDDEN) {
            sentry = SentryKey.HIDDEN;
            sentryKey.setClickable(false);
        }
        row.splitTo(false, true);
    }

    /** 点亮放完、再让人看一会儿（{@link #SENTRY_LIT_HOLD_MS}）才收起。这期间键还亮着、还点得到（反悔）。 */
    private void leaveAfterLit() {
        pendingLeave = true;
        sentryKey.removeCallbacks(sentryLeave);
        if (lightAnimator != null) {
            return;  // 过渡还在走：走完那一下再排（lightTo 里的收尾）
        }
        long shown = SystemClock.uptimeMillis() - litAt;
        sentryKey.postDelayed(sentryLeave, Math.max(0, SENTRY_LIT_HOLD_MS - shown));
    }

    /** 往外滑。滑到一半之前键还点得到，过了一半才算真的收了（{@link #onSplit}）。 */
    private void leaveNow() {
        pendingLeave = false;
        row.splitTo(false, true);
    }

    private void cancelPendingLeave() {
        pendingLeave = false;
        sentryKey.removeCallbacks(sentryLeave);
    }

    /** 那个键此刻还点不点得到来反悔：往外滑了不到一半（{@link #SENTRY_CANCEL_UNTIL}）。没在往外滑时一直点得到。 */
    static boolean withinReach(boolean leaving, float split) {
        return !leaving || split >= SENTRY_CANCEL_UNTIL;
    }

    /** 点了那个键：没开的时候是打开；亮着的时候（开上了、还没收起）是反悔，关掉。等车回话时点了不算。 */
    private void onSentryKeyTapped() {
        if (sentry == SentryKey.OFFERED) {
            showSentry(SentryKey.TURNING_ON);
            SentrySwitch.turn(context, true, done -> settleSentry(SentryKey.TURNING_ON));
        } else if (sentry == SentryKey.LIT) {
            // 反悔（项目所有者 2026-11-14）：键亮着、还没滑出去一半时再点一下，把哨兵模式关掉。正在往外滑的拉回来
            cancelPendingLeave();
            row.splitTo(true, true);
            showSentry(SentryKey.TURNING_OFF);
            SentrySwitch.turn(context, false, done -> settleSentry(SentryKey.TURNING_OFF));
        }
    }

    /**
     * 写车的那一下回话了（{@link SentrySwitch}，没做成它自己提示一句）：键先回到写之前的样子，再照车此刻的读数定 ——
     * 当场对一次账（{@link Telemetry#reconcileNow}，读到的是新的），再判（{@link #judgeSentry}）。做成了，读数就是要的
     * 那个样子：开上了点亮、收起，关上了变回没开、键留着。没做成，读数没变，键就是原样、可以再点。
     * 回话之前车辆信号已经先到、键已经照它定了的，这里什么都不用做。
     */
    private void settleSentry(SentryKey waited) {
        if (sentry != waited) {
            return;
        }
        if (!row.isOpen()) {
            sentry = SentryKey.HIDDEN;  // 这工夫主界面离开过，这一行已经收回去了：键不在眼前，下次该在时从头来
            return;
        }
        showSentry(waited == SentryKey.TURNING_ON ? SentryKey.OFFERED : SentryKey.LIT);
        Telemetry.get().reconcileNow(false, this::judgeSentry);
    }

    private void showSentry(SentryKey next) {
        boolean fresh = sentry == SentryKey.HIDDEN;
        sentry = next;
        sentryKey.setClickable(next == SentryKey.OFFERED || next == SentryKey.LIT);
        float dim = next == SentryKey.TURNING_ON || next == SentryKey.TURNING_OFF ? 0.5f : 1f;
        sentryIcon.setAlpha(dim);
        sentryLabel.setAlpha(dim);
        if (next == SentryKey.LIT) {
            lightTo(1f);
        } else if (next == SentryKey.OFFERED) {
            if (fresh) {
                // 刚出现（上一次也许是亮着滑出去的）：直接是没开的样子
                if (lightAnimator != null) {
                    lightAnimator.cancel();
                }
                applyLit(0f);
            } else {
                // 眼前从亮着回到没开（反悔关掉了，或者别处把哨兵模式关了）：变回去
                lightTo(0f);
            }
        }
    }

    /**
     * 图标过渡到开着（1）或没开（0）的样子。到了开着：记下时刻，有人在等（{@link #leaveAfterLit}）就排收起。
     * 已经是那个样子、正往那个样子去就不重来；走到一半改主意的，时长照剩下的路程算。
     */
    private void lightTo(float target) {
        if (lightAnimator != null) {
            if (lightTarget == target) {
                return;
            }
            lightAnimator.cancel();
        }
        boolean up = target >= 1f;
        // 往回走只倒到转圈之前那一刻（那时红的已经都退干净了，和没开一个样子），不把转圈倒着再放一遍
        float end = up ? 1f : SENTRY_SWEEP_BY;
        if (lit == target || (!up && lit <= end)) {
            applyLit(target);
            return;
        }
        lightTarget = target;
        ValueAnimator animator = ValueAnimator.ofFloat(lit, end);
        animator.setDuration(Math.max(1, up ? Math.round(SENTRY_LIGHT_MS * (1f - lit))
                : Math.round(SENTRY_UNLIGHT_MS * (lit - end) / (1f - end))));
        // 匀速走：先后在 applyLit 里按段分
        animator.setInterpolator(new LinearInterpolator());
        animator.addUpdateListener(a -> applyLit((float) a.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;

            @Override
            public void onAnimationCancel(Animator animation) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (lightAnimator == animation) {
                    lightAnimator = null;
                }
                if (cancelled) {
                    return;
                }
                applyLit(target);
                if (target >= 1f) {
                    litAt = SystemClock.uptimeMillis();
                    if (pendingLeave) {
                        sentryKey.postDelayed(sentryLeave, SENTRY_LIT_HOLD_MS);
                    }
                }
            }
        });
        lightAnimator = animator;
        animator.start();
    }

    /**
     * 图标的样子，0 = 没开、1 = 开着。中间是一段动画（各段占哪一截见常量），靠两个窗口做出来 —— 红的灯芯、红的三道光
     * 各是盖在本色上面的一层，只在窗口里露出来（{@link View#setClipBounds}，不重画、不重新布局）：
     *
     * <ol>
     *   <li>灯芯转圈：一个竖着的窗口从灯芯左边扫到右边外面，一圈一圈（{@link #lampWindow}），看着是红的一面转过去、
     *       本色的一面转回来，像警灯；</li>
     *   <li>灯芯定住：最后一圈红的转进来不再出去，铺满整颗；</li>
     *   <li>三道光长出去：窗口从贴着灯罩往外放大到画布边，红的光从靠灯的一头露到另一头；</li>
     *   <li>整个图标鼓一下再收回。</li>
     * </ol>
     */
    private void applyLit(float value) {
        lit = Math.max(0f, Math.min(1f, value));
        int width = sentryIcon.getLayoutParams().width;
        int height = sentryIcon.getLayoutParams().height;
        float unit = width / ICON_UNITS;

        float lampLeft = LAMP_LEFT * unit;
        float lampWidth = (LAMP_RIGHT - LAMP_LEFT) * unit;
        lampWindow(lit, lampSpan);
        showThrough(sentryLampOn, lampLeft + lampSpan[0] * lampWidth, 0f, lampLeft + lampSpan[1] * lampWidth, height);

        float grow = part(lit, SENTRY_RAYS_FROM, SENTRY_RAYS_BY);
        float left = RAYS_LEFT_IN + (RAYS_LEFT_OUT - RAYS_LEFT_IN) * grow;
        float top = RAYS_TOP_IN + (RAYS_TOP_OUT - RAYS_TOP_IN) * grow;
        if (grow <= 0f) {
            sentryRaysOn.setVisibility(View.INVISIBLE);
        } else {
            showThrough(sentryRaysOn, left * unit, top * unit, (ICON_UNITS - left) * unit, height);
        }

        float swell = 1f + SENTRY_SWELL * (float) Math.sin(Math.PI * part(lit, SENTRY_SWELL_FROM, 1f));
        sentryIcon.setScaleX(swell);
        sentryIcon.setScaleY(swell);
    }

    private final float[] lampSpan = new float[2];

    /**
     * 红的灯芯此刻露出哪一段：{@code out[0]}、{@code out[1]} 是窗口的左右两边，灯芯宽的几成（0 = 灯芯左边，1 = 右边），
     * 两个一样就是不露。转圈时窗口宽 {@link #SENTRY_BAND}，每一圈从左边外面进来、到右边外面出去；转完之后从左边进来铺满。
     */
    static void lampWindow(float lit, float[] out) {
        float from;
        float to;
        if (lit <= 0f) {
            from = 0f;
            to = 0f;
        } else if (lit < SENTRY_SWEEP_BY) {
            float turns = lit / SENTRY_SWEEP_BY * SENTRY_SWEEPS;
            float within = turns - (float) Math.floor(turns);
            to = within * (1f + SENTRY_BAND);
            from = to - SENTRY_BAND;
        } else {
            from = 0f;
            to = part(lit, SENTRY_SWEEP_BY, SENTRY_FILL_BY);
        }
        out[0] = Math.max(0f, Math.min(1f, from));
        out[1] = Math.max(0f, Math.min(1f, to));
    }

    /** 这一层只在这个窗口里露出来（视图自己的坐标）；窗口是空的就整层不显示。 */
    private void showThrough(View layer, float left, float top, float right, float bottom) {
        sentryClip.set(Math.round(left), Math.round(top), Math.round(right), Math.round(bottom));
        if (sentryClip.isEmpty()) {
            layer.setVisibility(View.INVISIBLE);
            return;
        }
        layer.setClipBounds(sentryClip);
        layer.setVisibility(View.VISIBLE);
    }

    /** 整个过渡走到 {@code t} 时，占 {@code from}–{@code to} 那一截的这一段走了多少（0–1）。 */
    static float part(float t, float from, float to) {
        return Math.max(0f, Math.min(1f, (t - from) / (to - from)));
    }

    /** 右边分出去多少变了（{@link RecordKeyRow}，0–1）：留白跟着收，一分出去第一行就可以缩小（{@link #fitTitle}）。 */
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
        if (sentry == SentryKey.LIT && !withinReach(!row.isOpen(), split)) {
            // 亮着往外滑，过了一半：点不到了，这才算收了
            sentry = SentryKey.HIDDEN;
            sentryKey.setClickable(false);
        }
        boolean narrow = split > 0f;
        if (narrow == compact) {
            return;
        }
        compact = narrow;
        fitTitle();
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
                // 按比例缩只是个起点：字宽不严格跟着字号走，差一两个像素就折行，所以量到真的放得下为止
                size = Math.max(smallest, (float) Math.floor(titlePx * room / need));
                fitPaint.setTextSize(size);
                while (size > smallest && fitPaint.measureText(title.getText(), 0, title.length()) > room) {
                    size -= 1f;
                    fitPaint.setTextSize(size);
                }
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

    /** 「mm:ss」，满一小时「h:mm:ss」。 */
    static String clock(long ms) {
        long seconds = ms / 1000;
        long hours = seconds / 3600;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, seconds / 60 % 60, seconds % 60)
                : String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60);
    }
}
