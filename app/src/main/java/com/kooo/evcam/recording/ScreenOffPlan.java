package com.kooo.evcam.recording;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.VehicleState;

/**
 * 正在录的时候熄屏，这段录像会怎样。一张表（{@link #atScreenOff}），几处照它：{@link RecordingCoordinator} 照它停不停、
 * 什么时候停、亮屏接不接；主界面照它退不退后台；录制键上那行小字照它说（{@link #of}）—— 界面上写的就是实际会发生的。
 *
 * <p>车机睡不睡由车辆的哨兵模式决定：开着时车机一直醒着、只是黑屏，录像不断；没开的话车机熄屏后几秒就断电
 * （2026-10-10 实测：U 盘先写不进，不到 1 秒熄屏，3–5 秒后进程被结束或冻住）。所以哨兵模式没开、也没在开车，
 * 熄屏那一刻就停录，让相机按次序关（环视先单独关）—— 不等车机来收拾（项目所有者 2026-10-10）；亮屏后接不接看开关。
 * 表里这一行就是「车机要睡」（{@link #headUnitSleeps}），给通道调度的那项事实什么时候写、什么时候清，也照它
 * （{@link #sleepFact}）。哨兵模式在 D 挡读成 0（{@code Signal.SENTRY_MODE}），所以「没开」还要看在不在开车
 * （{@link #driving}：挡位不是 P 就算，项目所有者 2026-10-11）：在开车照以前的做法。
 * 开发者的「熄屏录制（阻止休眠）」拿唤醒锁拉住车机，拉着的时候哨兵模式开没开都接着录。</p>
 *
 * <p>熄屏那一刻判一次；熄屏期间开发者的唤醒锁到点、车辆信号变了（接着录、10 秒后停的那两种，
 * {@link #rejudgesOnVehicleChange}）再判（{@link RecordingCoordinator}）。</p>
 */
public enum ScreenOffPlan {

    /** 熄屏后继续录制。 */
    CONTINUE(R.string.record_screen_off_continue),
    /** 熄屏后停，唤醒后恢复：哨兵模式没开时现在停、亮屏接；或熄屏持续录制关着、启动自动录制开着 —— 用户看到的是同一件事。 */
    PAUSE(R.string.record_screen_off_pause),
    /** 熄屏后停止录制，亮屏也不接。 */
    STOP(R.string.record_screen_off_stop),
    /** 熄屏持续录制开着，但读不到哨兵模式：按没开算（现在停、亮屏接），续录要看哨兵模式开没开。 */
    NEEDS_SENTRY(R.string.record_screen_off_needs_sentry);

    /** 录制键上那行小字。 */
    public final int text;

    ScreenOffPlan(int text) {
        this.text = text;
    }

    /** 熄屏时（和熄屏期间重判时）怎么做。 */
    public enum Action {
        /** 接着录：唤醒锁拉着车机、哨兵模式开着（车机醒着），或者在开车 —— 熄屏持续录制开着时。 */
        CONTINUE,
        /**
         * 熄屏 {@link RecordingCoordinator#SCREEN_OFF_STOP_MS} 后停：熄屏持续录制关着，而车机醒着
         * （哨兵模式开着、或者在开车），这 10 秒真的会走完。
         */
        STOP_LATER,
        /**
         * 现在就停，相机按次序关：哨兵模式没开（或读不到）、也没在开车 —— 车机几秒后就断电
         * （「车机要睡」，{@link ScreenOffPlan#headUnitSleeps}）。
         */
        STOP_NOW
    }

    /** 判下来的结果：怎么做，停了之后亮屏接不接。 */
    public static final class Decision {
        public final Action action;
        /** 停了（{@link Action#STOP_LATER} / {@link Action#STOP_NOW}）之后，亮屏接着录；接着录的那种是 false。 */
        public final boolean resumesOnWake;

        Decision(Action action, boolean resumesOnWake) {
            this.action = action;
            this.resumesOnWake = resumesOnWake;
        }
    }

    /**
     * 一次判定（或者亮屏）之后，「车机要睡」这项事实怎么动（{@link ScreenOffPlan#sleepFact}）。事实本身记在相机的登记表里
     * （{@code CameraNeeds}），通道调度读它：写着的时候不再保持，手上那一步最多再等 1 秒，然后按次序关
     * （channel-logic §二、§四，项目所有者 2026-10-10）。
     */
    public enum SleepFact {
        /** 写下，从此刻起算：调度「最多再等 1 秒」从写下的那一刻算。 */
        WRITE,
        /** 清掉：亮屏了，车机醒着。 */
        CLEAR,
        /** 不动。 */
        KEEP
    }

    /** 熄屏后不停录：开发者「熄屏录制（阻止休眠）」或「熄屏持续录制」开着。 */
    static boolean keepsRecording(AppConfig config) {
        return config.isScreenOffRecordingEnabled() || config.isScreenOffKeepRecording();
    }

    /**
     * 在开车：挡位读得到、不是 P 就算 —— D 挡等红灯、走走停停都算；读不到挡位时才看车速：读得到、大于 0。
     * 车机只在 P 挡断电，所以挡位说了算（项目所有者 2026-10-11，大纲 §7 第 2 问）。以前还要车速大于 0：
     * 等红灯时熄屏（哨兵模式在 D 挡读成 0）就判成「车机马上要睡」，当场停录、按次序关相机，车开走了也判不回来
     * （熄屏中只往下判），录制键上的小字也跟着走走停停来回跳。
     * 都读不到就按没在开车算，相机赶在断电之前关（2026-10-10）。
     */
    static boolean driving(String gear, Float speedKmh) {
        if (gear != null) {
            return !"P".equals(gear);
        }
        return speedKmh != null && speedKmh > 0f;
    }

    /**
     * 车机要睡了：唤醒锁没拉着车机，哨兵模式关（0）或读不到，也没在开车 —— 表里「现在停」那一行
     * （channel-logic §四，项目所有者 2026-10-10）。表（{@link #atScreenOff}）的「现在停」就是照它判的，只有这一处。
     *
     * <p>和在不在录、熄屏持续录制开没开都无关：这是车的事实，车机几秒后就断电，相机要赶在那之前按次序关。
     * 没在录的时候唤醒锁不拿（{@link ScreenOffRecording#holdsCarAwake}），所以开发者的「熄屏录制」开着、熄屏时没在录，
     * 照样算车机要睡（大纲 §7 第 5 问，2026-10-11：照登记表关相机）。</p>
     *
     * @param lockHeld 开发者的唤醒锁拉着车机（{@link ScreenOffRecording#holdsCarAwake}）
     * @param sentry   哨兵模式（{@code VehicleState.sentry}：0 关、1 开、2 布防，null 读不到）
     * @param driving  在开车（{@link #driving}）
     */
    static boolean headUnitSleeps(boolean lockHeld, Integer sentry, boolean driving) {
        return !lockHeld && (sentry == null || sentry == 0) && !driving;
    }

    /**
     * 此刻熄屏的话怎么做（输入都在这里取齐）：协调器熄屏那一刻、唤醒锁到点、车辆信号变了，主界面熄屏那一刻，都调它。
     *
     * @param lockHeld 开发者的唤醒锁拉着车机（{@link ScreenOffRecording#holdsCarAwake}）
     * @param vehicle  车辆信号此刻的快照（{@code Telemetry.latest()}）
     */
    public static Decision atScreenOff(AppConfig config, boolean lockHeld, VehicleState vehicle) {
        return atScreenOff(keepsRecording(config), lockHeld, config.isAutoStartRecording(), vehicle.sentry,
                driving(vehicle.gear, vehicle.speedKmh));
    }

    /**
     * 那张表（项目所有者 2026-10-10）。纯函数，测试直接调。
     *
     * <ul>
     *   <li>车机要睡（{@link #headUnitSleeps}：唤醒锁没拉着、哨兵模式关（0）或读不到、没在开车）→ 现在停，相机按次序关；</li>
     *   <li>唤醒锁拉着车机 → 接着录；</li>
     *   <li>其余是车机醒着（哨兵模式开（1）/ 布防（2），或者在开车）：熄屏持续录制开着接着录，
     *       关着 10 秒后停（和以前一样）；</li>
     *   <li>停了之后亮屏接不接：熄屏持续录制、启动自动录制开着一个就接。</li>
     * </ul>
     *
     * @param keeps      熄屏后不停录（{@link #keepsRecording}）
     * @param lockHeld   开发者的唤醒锁拉着车机
     * @param autoRecord 「启动自动录制」开着
     * @param sentry     哨兵模式（{@code VehicleState.sentry}：0 关、1 开、2 布防，null 读不到）
     * @param driving    在开车（{@link #driving}）
     */
    static Decision atScreenOff(boolean keeps, boolean lockHeld, boolean autoRecord, Integer sentry, boolean driving) {
        Action action;
        if (headUnitSleeps(lockHeld, sentry, driving)) {
            action = Action.STOP_NOW;
        } else if (lockHeld || keeps) {
            action = Action.CONTINUE;
        } else {
            action = Action.STOP_LATER;
        }
        return new Decision(action, action != Action.CONTINUE && (keeps || autoRecord));
    }

    /**
     * 熄屏期间哨兵模式、挡位变了，要不要照表再判一次：「接着录」（不是唤醒锁拉着的那种）和「10 秒后停」都要
     * （channel-logic §四「熄屏期间情况变了，重判一次」，项目所有者 2026-10-10）。熄屏时在开车、后来挂 P 下车，
     * 或者哨兵模式关了，都不会再有熄屏事件：接着录的不重判，就一直录到车机断电；10 秒后停的不重判，车机在这 10 秒里
     * 就睡了，相机来不及按次序关。
     *
     * <p>设置不变时，这种重判只会停在原处或者判成现在停：表里能让它往「接着录」走的只有开关和唤醒锁，都不是车辆信号。
     * 10 秒后停的重判下来还是 10 秒后停，计时照原来的走，不重新开始（{@link RecordingCoordinator}）。
     * 已经现在停了的不重判，不会因为信号变了自己录起来；锁拉着的等锁到点再判。</p>
     *
     * @param darkAction 这一次熄屏定下的做法（null = 亮着，或者熄屏时没在录）
     * @param byLock     那个「接着录」是唤醒锁拉着车机才定的
     */
    static boolean rejudgesOnVehicleChange(Action darkAction, boolean byLock) {
        return (darkAction == Action.CONTINUE || darkAction == Action.STOP_LATER) && !byLock;
    }

    /**
     * 一次判定之后，「车机要睡」该写、该清，还是不动（协调器熄屏判定、重判之后，以及亮屏时调）：
     *
     * <ul>
     *   <li>黑着、判成现在停（{@link #headUnitSleeps}），还没写 → 写。熄屏那一刻判的、10 秒后停重判成现在停的、
     *       唤醒锁到点判的，都是这一条；已经写着 → 不动，起算时刻不挪（调度「最多再等 1 秒」从写下那一刻算）。</li>
     *   <li>黑着、判成接着录或 10 秒后停 → 不动：没写的不写；写了的也不清 —— 熄屏中只往下判，相机已经在按次序关，
     *       停了的录像黑着也不接（亮屏再照表接）。</li>
     *   <li>亮屏 → 写着就清：车机醒着了，调度照常保持、开相机。</li>
     * </ul>
     *
     * <p>只在真要变的时候给写或清：调用方照它写一行黑匣子，就是「一件事一行」。</p>
     *
     * @param dark    屏幕黑着
     * @param judged  这一次照表判下来的做法（{@link #atScreenOff}；亮屏时 null）
     * @param written 「车机要睡」现在写着
     */
    static SleepFact sleepFact(boolean dark, Action judged, boolean written) {
        if (!dark) {
            return written ? SleepFact.CLEAR : SleepFact.KEEP;
        }
        if (judged == Action.STOP_NOW && !written) {
            return SleepFact.WRITE;
        }
        return SleepFact.KEEP;
    }

    /**
     * 录制键上那行小字：现在熄屏的话会怎样。和 {@link #atScreenOff} 是同一张表：接着录 →「熄屏后继续录制」；
     * 停了亮屏接 →「熄屏暂停，唤醒后恢复」；不接 →「熄屏后停止录制」；熄屏持续录制开着却读不到哨兵模式（按没开算，
     * 现在停、亮屏接）→「熄屏续录需开启哨兵模式」。
     */
    public static ScreenOffPlan of(AppConfig config, boolean lockHeld, VehicleState vehicle) {
        return of(keepsRecording(config), lockHeld, config.isAutoStartRecording(), vehicle.sentry,
                driving(vehicle.gear, vehicle.speedKmh));
    }

    /** 纯函数，测试直接调。 */
    static ScreenOffPlan of(boolean keeps, boolean lockHeld, boolean autoRecord, Integer sentry, boolean driving) {
        Decision decision = atScreenOff(keeps, lockHeld, autoRecord, sentry, driving);
        if (decision.action == Action.CONTINUE) {
            return CONTINUE;
        }
        if (keeps && sentry == null) {
            return NEEDS_SENTRY;
        }
        return decision.resumesOnWake ? PAUSE : STOP;
    }
}
