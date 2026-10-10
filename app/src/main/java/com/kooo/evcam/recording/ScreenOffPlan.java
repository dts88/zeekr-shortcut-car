package com.kooo.evcam.recording;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.VehicleState;

/**
 * 正在录的时候熄屏，这段录像会怎样。一张表（{@link #atScreenOff}），几处照它：{@link RecordingCoordinator} 照它停不停、
 * 什么时候停、亮屏接不接；主界面照它退不退后台；录制键上那行小字照它说（{@link #of}）—— 界面上写的就是实际会发生的。
 *
 * <p>车机睡不睡由车辆的哨兵模式决定：开着时车机一直醒着、只是黑屏，录像不断；没开的话车机熄屏后几秒就断电
 * （2026-10-10 实测：U 盘先写不进，不到 1 秒熄屏，3–5 秒后进程被结束或冻住）。所以哨兵模式没开、车也没在走，
 * 熄屏那一刻就停录，让相机按次序关（环视先单独关）—— 不等车机来收拾（项目所有者 2026-10-10）；亮屏后接不接看开关。
 * 哨兵模式在 D 挡读成 0（{@code Signal.SENTRY_MODE}），所以「没开」还要看车在不在走：明确在走（{@link #driving}）
 * 照以前的做法。开发者的「熄屏录制（阻止休眠）」拿唤醒锁拉住车机，拉着的时候哨兵模式开没开都接着录。</p>
 *
 * <p>熄屏那一刻判一次；熄屏期间开发者的唤醒锁到点、车辆信号变了（接着录的那种）再判（{@link RecordingCoordinator}）。</p>
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
        /** 接着录：唤醒锁拉着车机、哨兵模式开着（车机醒着），或者车在走 —— 熄屏持续录制开着时。 */
        CONTINUE,
        /**
         * 熄屏 {@link RecordingCoordinator#SCREEN_OFF_STOP_MS} 后停：熄屏持续录制关着，而车机醒着
         * （哨兵模式开着、或者车在走），这 10 秒真的会走完。
         */
        STOP_LATER,
        /** 现在就停，相机按次序关：哨兵模式没开（或读不到）、车也没在走 —— 车机几秒后就断电。 */
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

    /** 熄屏后不停录：开发者「熄屏录制（阻止休眠）」或「熄屏持续录制」开着。 */
    static boolean keepsRecording(AppConfig config) {
        return config.isScreenOffRecordingEnabled() || config.isScreenOffKeepRecording();
    }

    /**
     * 车明确在走：挡位读得到、不是 P，车速读得到、大于 0。读不到的都不算在走 ——
     * 只有确知在走（熄屏多半是行驶中关了屏幕）才照以前的做法；不知道就按停着算，相机赶在断电之前关（2026-10-10）。
     */
    static boolean driving(String gear, Float speedKmh) {
        return gear != null && !"P".equals(gear) && speedKmh != null && speedKmh > 0f;
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
     *   <li>唤醒锁拉着车机 → 接着录；</li>
     *   <li>哨兵模式开（1）/ 布防（2）：车机醒着 —— 熄屏持续录制开着接着录，关着 10 秒后停（和以前一样）；</li>
     *   <li>哨兵模式关（0）或读不到：车明确在走 → 和以前一样（开着接着录，关着 10 秒后停）；
     *       没在走 → 现在停，相机按次序关；</li>
     *   <li>停了之后亮屏接不接：熄屏持续录制、启动自动录制开着一个就接。</li>
     * </ul>
     *
     * @param keeps      熄屏后不停录（{@link #keepsRecording}）
     * @param lockHeld   开发者的唤醒锁拉着车机
     * @param autoRecord 「启动自动录制」开着
     * @param sentry     哨兵模式（{@code VehicleState.sentry}：0 关、1 开、2 布防，null 读不到）
     * @param driving    车明确在走（{@link #driving}）
     */
    static Decision atScreenOff(boolean keeps, boolean lockHeld, boolean autoRecord, Integer sentry, boolean driving) {
        Action action;
        if (lockHeld) {
            action = Action.CONTINUE;
        } else if ((sentry != null && sentry != 0) || driving) {
            action = keeps ? Action.CONTINUE : Action.STOP_LATER;
        } else {
            action = Action.STOP_NOW;
        }
        return new Decision(action, action != Action.CONTINUE && (keeps || autoRecord));
    }

    /**
     * 熄屏期间哨兵模式、挡位变了，要不要照表再判一次：只有「接着录」、而且不是唤醒锁拉着的那种。
     * 熄屏时车在走、后来挂 P 下车，或者哨兵模式关了，都不会再有熄屏事件，不重判就一直录到车机断电（2026-10-10）；
     * 已经停了的不会因为信号变了自己录起来，10 秒后停的照样到点停，锁拉着的等锁到点再判。
     *
     * @param darkAction 这一次熄屏定下的做法（null = 亮着，或者熄屏时没在录）
     * @param byLock     那个「接着录」是唤醒锁拉着车机才定的
     */
    static boolean rejudgesOnVehicleChange(Action darkAction, boolean byLock) {
        return darkAction == Action.CONTINUE && !byLock;
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
