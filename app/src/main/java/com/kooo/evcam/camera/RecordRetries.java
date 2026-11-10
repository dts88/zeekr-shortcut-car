/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.camera;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * 录像让了之后什么时候再试（相机管家 3c 第二步 2b，项目所有者 2026-11-10，时机照旧）：一份管线一本，相机管家开
 * （{@link CameraKeeper#recordRetries}），隔够了由管家通知那一份管线（{@link Due#retry}）。
 *
 * <p>相机层配不上会话、丢了一路的录像输出（{@link SingleCamera.Drop#CONFIG_FAILED}）是相机那一侧的事（倒车时原厂 360 刚放开环视、
 * HAL 还在拆它的流，就会这样），不算录不了、不整次停：这一路退出，记一笔（{@link #refuse}）。隔多久再声明照
 * {@link ChannelRules#recordRetryAfter}：连着不到 {@link CameraLiveness#MAX_ATTEMPTS} 次隔 {@link CameraLiveness#RETRY_GAP_MS}，
 * 到点通知；到了次数就等 —— 它的相机走了回来（{@link #cameraGone}）、要它的登记方变了、亮屏（{@link #lift}，这几件由那一份管线报来）——
 * 和救援梯子一个节奏，不会一直重配会话。会话带上了就清（{@link #joined}）；开录、停录整本清（{@link #clear}）。</p>
 *
 * <p>只在主线程上用。</p>
 */
final class RecordRetries {

    /** 隔够了：这一路重新声明一个录像输出（主线程；那一份管线还在录才做）。 */
    interface Due {
        void retry(String lane);
    }

    /** 过一会儿做一件事（管家主线程上的 Handler）。 */
    interface Timer {
        void after(long delayMs, Runnable task);
    }

    /** 一路的录像输出连着被会话带不上的账。 */
    private static final class Refusal {
        /** 连着几次。 */
        int count;
        /** 这个时刻（uptime）之前不再声明。 */
        long retryAt;
    }

    private final Due due;
    private final Timer timer;
    private final LongSupplier clock;
    private final Map<String, Refusal> refusals = new HashMap<>();
    /** 整本清过几次（{@link #clear}）：清之前排着的到点作废。 */
    private int generation;

    /**
     * @param due   隔够了通知谁
     * @param timer 到点用哪个（主线程）
     * @param clock 此刻（uptime，毫秒）
     */
    RecordRetries(Due due, Timer timer, LongSupplier clock) {
        this.due = due;
        this.timer = timer;
        this.clock = clock;
    }

    /**
     * 相机层丢了这一路的录像输出（会话配不上；这一路已经退出、录制器换下来了）：记一次。还没到次数，隔够了通知
     * （{@link Due#retry}）；到了次数就等（{@link #refusedUntilGone}）。
     *
     * @return 连着第几次
     */
    int refuse(String lane) {
        Refusal refusal = refusals.get(lane);
        if (refusal == null) {
            refusal = new Refusal();
            refusals.put(lane, refusal);
        }
        refusal.count++;
        long after = ChannelRules.recordRetryAfter(refusal.count);
        if (after > 0) {
            refusal.retryAt = clock.getAsLong() + after;
            final int gen = generation;
            timer.after(after, () -> {
                if (gen == generation) {
                    due.retry(lane);
                }
            });
        }
        return refusal.count;
    }

    /** 眼下不声明这一路的录像输出：还没到再试的时候，或者连着几次都带不上了。 */
    boolean refused(String lane) {
        Refusal refusal = refusals.get(lane);
        return refusal != null && (untilGone(refusal) || clock.getAsLong() < refusal.retryAt);
    }

    /** 连着几次都带不上了：在等它的相机走了回来（{@link #cameraGone}）、登记表变了或者亮屏（{@link #lift}）。 */
    boolean refusedUntilGone(String lane) {
        Refusal refusal = refusals.get(lane);
        return refusal != null && untilGone(refusal);
    }

    private static boolean untilGone(Refusal refusal) {
        return ChannelRules.recordRetryAfter(refusal.count) == 0;
    }

    /** 会话带上了这一路的录像输出：账清掉。 */
    void joined(String lane) {
        refusals.remove(lane);
    }

    /**
     * 这一路的相机走了（丢了、关了、在救）：账随它走 —— 回来时是一份新会话，照常声明。
     *
     * @return true：有账、清了（那一份管线这时重新声明这一路）
     */
    boolean cameraGone(String lane) {
        return refusals.remove(lane) != null;
    }

    /**
     * 在等的那几路（{@link #refusedUntilGone}）不再等：要它的登记方变了（只那一路）、亮屏（各路）。还在隔着的不动。
     *
     * @param lanes 只看这几路；null 是各路
     * @return 不再等的那几路（账清了，那一份管线重新声明它们）
     */
    List<String> lift(Collection<String> lanes) {
        List<String> lifted = new ArrayList<>();
        for (String lane : new ArrayList<>(refusals.keySet())) {
            if ((lanes == null || lanes.contains(lane)) && refusedUntilGone(lane)) {
                refusals.remove(lane);
                lifted.add(lane);
            }
        }
        return lifted;
    }

    /** 这个录像意图开始、结束（开录、停录）：整本清，排着的到点作废。 */
    void clear() {
        refusals.clear();
        generation++;
    }
}
