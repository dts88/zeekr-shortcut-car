package com.kooo.evcam.recording;

/**
 * 一次录像的生命周期：空闲 → 开录中 → 在录 → 停录中 → 空闲。「在不在录」只有这一份。
 *
 * <h3>为什么要有它（2026-10-05 项目所有者批准的问题）</h3>
 *
 * <p>以前「在录」有两个定义：协调器在开录指令发出去时就说「在录」（通知、悬浮按钮、主界面都跟着变），
 * 相机层要等会话建好、录制器真的启动了才把自己标成在录；而停录、熄屏录制拿唤醒锁、恢复、心跳问的都是相机层。
 * 每一路都没起来时，协调器去停，问相机层「在录吗」—— 没有 —— 于是什么都不收拾、也不接回：
 * 通知和悬浮按钮一直是「录制中」，一个文件都没写。屏幕黑着时开始的录像，熄屏录制（阻止休眠）
 * 也因为问的是相机层而拿不到唤醒锁。</p>
 *
 * <h3>规矩</h3>
 *
 * <ul>
 *   <li><b>开录中、在录都算「在录」</b>（{@link #isRecording}）：从开录指令发出去起，到停录为止。
 *       谁要问「在不在录」都问协调器，协调器问这里；</li>
 *   <li><b>只有空闲时能开</b>（{@link #begin}）：上一次还在停录中（编码器、录像输出还没收拾完）就先等它，
 *       以前开 → 停 → 开两秒内，上一次的收拾会把这一次的编码器一起放掉；</li>
 *   <li><b>停只有一条路</b>（{@link #stop}）：人停的、被打断、开录失败，都从开录中 / 在录进停录中，
 *       管线收拾完了报上来才回到空闲；收拾太久（{@link #STOP_DEADLINE_MS}）就不等了；</li>
 *   <li><b>管线的报告</b>（{@link #on}）只在对得上的阶段算数，过时的、重复的一律不理。</li>
 * </ul>
 *
 * <p>「在录」说的是录像意图开着（2.11，确认文档 §三）：每一路带着录像输出出了画面就加入，一路都录不了时
 * 录像也不停（显示等待摄像头）—— 那是各路的样子（{@code RecordingCoordinator#view}），不是这里的阶段。</p>
 *
 * <p>纯逻辑，见 {@code RecordingLifecycleTest}。</p>
 */
public final class RecordingLifecycle {

    /** 停录中最多等管线收拾多久。编码器一路最坏要五六秒（排空、收文件、等写线程），几路一起叫停、共用一个期限。 */
    public static final long STOP_DEADLINE_MS = 20_000L;

    public enum Phase {
        /** 没在录，也没在收拾。 */
        IDLE,
        /**
         * 开录指令发出去了，还没有一路加入（每一路的相机带着录像输出出了画面才加入，两种录制器一样）。
         * 一路都在等摄像头时一直是它，照样算在录。
         */
        PREPARING,
        /** 至少一路加入过。之后一路都不在录（等待摄像头）也还是它。 */
        RECORDING,
        /** 停了，管线还在收拾（各路收尾封好文件、录制器停放、排着的任务）。 */
        STOPPING
    }

    /** 管线（相机层）报上来的事。 */
    public enum Report {
        /** 第一路加入录像。 */
        STARTED,
        /**
         * 计划里每一路的录制器都建不起来（编码录制建不起来；MediaRecorder 备不起来、或者开不了录）。
         * 会话带不上录像输出不是这个（相机那一侧的事，那一路退出、隔一会儿再声明）；相机没出画面也不是：那几路等着加入。
         * 停成什么原因看此刻的阶段（{@link RecordingLifecycle#endReason}）。
         */
        START_FAILED,
        /** 停录收拾完了。 */
        STOPPED
    }

    /** 收到一份报告后协调器该做什么。 */
    public enum Action {
        /** 不是这一次的（过时的、重复的）：不理。 */
        IGNORE,
        /** 记一笔就行：开录中 → 在录，或者在录时重建后又起来了。 */
        NOTE,
        /** 这一次录像到头了：走停录那一条路（收拾、提示、判接不接）。 */
        END,
        /** 等的收拾做完了：停录中 → 空闲，等着开录的可以开了。 */
        SETTLED
    }

    private Phase phase = Phase.IDLE;
    private long stoppingSinceMs;

    public Phase phase() {
        return phase;
    }

    /** 开录中、在录：从开录指令发出去起，到停录为止。 */
    public boolean isRecording() {
        return phase == Phase.PREPARING || phase == Phase.RECORDING;
    }

    public boolean isIdle() {
        return phase == Phase.IDLE;
    }

    /**
     * 要开录了（发开录指令之前调）。
     *
     * @return false：不能开 —— 已经在录，或者上一次还没收拾完
     */
    public boolean begin() {
        if (phase != Phase.IDLE) {
            return false;
        }
        phase = Phase.PREPARING;
        return true;
    }

    /**
     * 要停（人停的、被打断、开录失败、管线没了）：开录中 / 在录 → 停录中。
     *
     * @return false：不在录，没什么可停
     */
    public boolean stop(long nowMs) {
        if (!isRecording()) {
            return false;
        }
        phase = Phase.STOPPING;
        stoppingSinceMs = nowMs;
        return true;
    }

    /** 管线报上来一件事：该做什么。 */
    public Action on(Report report) {
        switch (report) {
            case STARTED:
                if (phase == Phase.PREPARING) {
                    phase = Phase.RECORDING;
                    return Action.NOTE;
                }
                return phase == Phase.RECORDING ? Action.NOTE : Action.IGNORE;
            case START_FAILED:
                return isRecording() ? Action.END : Action.IGNORE;
            case STOPPED:
                if (phase == Phase.STOPPING) {
                    phase = Phase.IDLE;
                    return Action.SETTLED;
                }
                // 没人叫它停、它自己停了（管线被释放）：这一次录像同样到头了
                return isRecording() ? Action.END : Action.IGNORE;
            default:
                return Action.IGNORE;
        }
    }

    /**
     * 一份报告让这一次录像到头了（{@link Action#END}）：按什么原因停。停之前调（看的是现在的阶段）。
     *
     * <p>开录中录制器都建不起来是开录失败 —— 录像根本没开始，提示不说「中断」。
     * 已经录了一阵之后才每一路都录不了的，录像是开始过的：和没人叫它停、它自己停了一样，是原因不明。</p>
     */
    public RecordingStops.Reason endReason(Report report) {
        return report == Report.START_FAILED && phase == Phase.PREPARING
                ? RecordingStops.Reason.START_FAILED : RecordingStops.Reason.UNKNOWN;
    }

    /**
     * 停录中等太久了：不等了，当作收拾完。
     *
     * @return true：确实在等，而且到点了（停录中 → 空闲）
     */
    public boolean stopOverdue(long nowMs) {
        if (phase != Phase.STOPPING || nowMs - stoppingSinceMs < STOP_DEADLINE_MS) {
            return false;
        }
        phase = Phase.IDLE;
        return true;
    }
}
