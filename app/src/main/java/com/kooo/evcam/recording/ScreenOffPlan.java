package com.kooo.evcam.recording;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.telemetry.VehicleState;

/**
 * 熄屏时这段录像会怎样。一张表（{@link #atDark}），两处照它：{@link RecordingCoordinator} 照它停不停、什么时候停、
 * 亮屏接不接、黑着能不能开录、写不写「车机要睡」（熄屏的唯一裁判，在不在录都判）；录制键上那行小字照它说（{@link #of}，
 * 输入和协调器熄屏那一刻的一样）—— 界面上写的就是实际会发生的。
 *
 * <h3>熄屏 v3（项目所有者 2026-10-12 D1–D3，设计 v3 §3）</h3>
 *
 * <p>「熄屏」是屏幕真黑了（{@code screen.ScreenState}：安卓熄屏，或者车辆说黑）。这台车上屏幕一黑车机还醒着几分钟，
 * 安卓的熄屏就是车机下电：哨兵模式没开时离车约 4–6 分钟后，先 U 盘写不进，不到 1 秒熄屏，3–5 秒后进程被结束或冻住
 * （2026-10-10、10-12 实测）；哨兵模式开着时车机一直醒着、安卓一直不熄屏。所以表里分两件事：</p>
 *
 * <ul>
 *   <li><b>座舱</b>不归这张表：屏幕一黑，前后座舱谁都不要（录像、预览、拍照……，{@code ChannelRules.releasedByDark}，D2），
 *       亮屏回来、各自接回录像；</li>
 *   <li><b>环视</b>照表：熄屏持续录制开着（或开发者的唤醒锁拉着车机）、计划里有环视 → 接着录；否则熄屏
 *       {@link RecordingCoordinator#SCREEN_OFF_STOP_MS} 后停；</li>
 *   <li><b>车机要睡</b>（{@link #headUnitSleeps}：安卓熄屏了，唤醒锁没拉着，哨兵模式关或读不到，也没在开车）→ 现在停，
 *       相机按次序关（这时座舱早关了，只剩环视）—— 车机下电前那最后约 15 秒在内存里来不及写，项目所有者接受（D3）。
 *       安卓还亮着时（车机醒着）不会判成现在停；哨兵模式、挡位只在安卓熄屏时才改得了结果。</li>
 * </ul>
 *
 * <p>熄屏那一刻判一次（E1），车机要睡（E2）再判一次；熄屏期间开发者的唤醒锁到点、10 秒到点、车辆信号变了
 * （{@link #rejudgesOnVehicleChange}）也再判（{@link RecordingCoordinator}）。车机黑着醒了（E2'）不判：撤掉「车机要睡」，
 * 照 {@link #startsWhileDark} 接不接。计划里没有环视的，黑着时和熄屏持续录制关着一样（不另开分支，公式本身就是）。</p>
 */
public enum ScreenOffPlan {

    /** 熄屏后继续录制环视（座舱亮屏后接回）。 */
    CONTINUE(R.string.record_screen_off_continue),
    /**
     * 熄屏后停，亮屏后恢复：熄屏持续录制关着（或计划里没有环视）、启动自动录制开着；或者车辆信号判不出屏幕（降级），
     * 熄屏就是车机下电 —— 现在停，熄屏持续录制、启动自动录制开着一个就亮屏接。
     */
    PAUSE(R.string.record_screen_off_pause),
    /** 熄屏后停止录制，亮屏也不接。 */
    STOP(R.string.record_screen_off_stop);

    /** 录制键上那行小字。 */
    public final int text;

    ScreenOffPlan(int text) {
        this.text = text;
    }

    /** 熄屏时（和熄屏期间重判时）怎么做。 */
    public enum Action {
        /** 接着录（环视；座舱黑着时本来就放开了）：熄屏持续录制开着、或唤醒锁拉着车机，而且计划里有环视。 */
        CONTINUE,
        /**
         * 熄屏 {@link RecordingCoordinator#SCREEN_OFF_STOP_MS} 后停：熄屏持续录制关着（或计划里没有环视），而车机醒着 ——
         * 车机要睡还早着（几分钟后），这 10 秒真的会走完。
         */
        STOP_LATER,
        /**
         * 现在就停，相机按次序关：车机要睡了（安卓熄屏，哨兵模式没开或读不到，没在开车，唤醒锁没拉着）——
         * 几秒后就断电（{@link ScreenOffPlan#headUnitSleeps}）。
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
     * 一次判定（或者安卓亮了）之后，「车机要睡」这项事实怎么动（{@link ScreenOffPlan#sleepFact}）。事实本身记在相机的登记表里
     * （{@code CameraNeeds}），通道调度读它：写着的时候不再保持、不开、不救，手上那一步最多再等 1 秒，然后按次序关
     * （channel-logic §二、§四，项目所有者 2026-10-10）。
     */
    public enum SleepFact {
        /** 写下，从此刻起算：调度「最多再等 1 秒」从写下的那一刻算。 */
        WRITE,
        /** 清掉：安卓亮着，车机醒着。 */
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
     * 等红灯时熄屏（哨兵模式在 D 挡读成 0）就判成「车机马上要睡」，当场停录、按次序关相机，车开走了也判不回来。
     * 都读不到就按没在开车算，相机赶在断电之前关（2026-10-10）。
     */
    static boolean driving(String gear, Float speedKmh) {
        if (gear != null) {
            return !"P".equals(gear);
        }
        return speedKmh != null && speedKmh > 0f;
    }

    /**
     * 车机要睡了：安卓熄屏了（这台车上就是车机下电），唤醒锁没拉着车机，哨兵模式关（0）或读不到，也没在开车 ——
     * 表里「现在停」那一行，只有这一处（channel-logic §四，项目所有者 2026-10-10；熄屏 v3 加上「安卓熄屏了」，2026-10-12 D3）。
     * 屏幕黑了而安卓还亮着，车机醒着，几分钟后才下电：不在这里。
     *
     * <p>和在不在录、熄屏持续录制开没开都无关：这是车的事实，车机几秒后就断电，相机要赶在那之前按次序关。
     * 没在录的时候唤醒锁不拿（{@link ScreenOffRecording#holdsCarAwake}），所以开发者的「熄屏录制」开着、没在录，
     * 照样算车机要睡（大纲 §7 第 5 问，2026-10-11：照登记表关相机）。</p>
     *
     * @param androidOff 安卓熄屏了（{@code ScreenState.androidOff}）
     * @param lockHeld   开发者的唤醒锁拉着车机（{@link ScreenOffRecording#holdsCarAwake}）
     * @param sentry     哨兵模式（{@code VehicleState.sentry}：0 关、1 开、2 布防，null 读不到）
     * @param driving    在开车（{@link #driving}）
     */
    static boolean headUnitSleeps(boolean androidOff, boolean lockHeld, Integer sentry, boolean driving) {
        return androidOff && !lockHeld && (sentry == null || sentry == 0) && !driving;
    }

    /**
     * 此刻黑着的话怎么做（输入都在这里取齐）：协调器熄屏那一刻、车机要睡、10 秒到点、唤醒锁到点、车辆信号变了，都调它。
     *
     * @param lockHeld        开发者的唤醒锁拉着车机（{@link ScreenOffRecording#holdsCarAwake}）
     * @param vehicle         车辆信号此刻的快照（{@code Telemetry.latest()}）
     * @param androidOff      安卓熄屏了（{@code ScreenState.androidOff}）
     * @param surroundPlanned 这一次录像的计划里有环视（{@link RecordingCoordinator#surroundPlanned}）
     */
    public static Decision atDark(AppConfig config, boolean lockHeld, VehicleState vehicle, boolean androidOff,
                                  boolean surroundPlanned) {
        return atDark(keepsRecording(config), lockHeld, config.isAutoStartRecording(), vehicle.sentry,
                driving(vehicle.gear, vehicle.speedKmh), androidOff, surroundPlanned);
    }

    /**
     * 那张表（项目所有者 2026-10-10，熄屏 v3 2026-10-12）。纯函数，测试直接调。
     *
     * <ul>
     *   <li>车机要睡（{@link #headUnitSleeps}）→ 现在停，相机按次序关；</li>
     *   <li>熄屏后不停录（{@link #keepsRecording}）或唤醒锁拉着车机，而且计划里有环视 → 接着录环视（D2：只有环视在黑着时录）。
     *       哨兵模式没开的话录到车机要睡（这一张表再判成现在停），开着就一直录；</li>
     *   <li>其余 → 熄屏 10 秒后停（车机醒着，这 10 秒真的会走完）；</li>
     *   <li>停了之后亮屏接不接：熄屏后不停录、启动自动录制开着一个就接。</li>
     * </ul>
     *
     * @param keeps           熄屏后不停录（{@link #keepsRecording}）
     * @param lockHeld        开发者的唤醒锁拉着车机
     * @param autoRecord      「启动自动录制」开着
     * @param sentry          哨兵模式（{@code VehicleState.sentry}：0 关、1 开、2 布防，null 读不到）
     * @param driving         在开车（{@link #driving}）
     * @param androidOff      安卓熄屏了
     * @param surroundPlanned 计划里有环视
     */
    static Decision atDark(boolean keeps, boolean lockHeld, boolean autoRecord, Integer sentry, boolean driving,
                           boolean androidOff, boolean surroundPlanned) {
        Action action;
        if (headUnitSleeps(androidOff, lockHeld, sentry, driving)) {
            action = Action.STOP_NOW;
        } else if ((lockHeld || keeps) && surroundPlanned) {
            action = Action.CONTINUE;
        } else {
            action = Action.STOP_LATER;
        }
        return new Decision(action, action != Action.CONTINUE && (keeps || autoRecord));
    }

    /**
     * 熄屏期间哨兵模式、挡位变了，要不要照表再判一次：「接着录」（不是唤醒锁拉着的那种）和「10 秒后停」都要
     * （channel-logic §四「熄屏期间情况变了，重判一次」，项目所有者 2026-10-10）。熄屏时在开车、后来挂 P 下车，
     * 或者哨兵模式关了，都不会再有熄屏事件。熄屏 v3 起只有安卓熄屏着时它才改得了结果（车机醒着时车辆信号判不出现在停），
     * 代码照留：车机要睡时在开车、哨兵模式开着的那一种，之后挂 P、关哨兵模式就靠它。
     *
     * <p>设置不变时，这种重判只会停在原处或者判成现在停：表里能让它往「接着录」走的只有开关和唤醒锁，都不是车辆信号。
     * 10 秒后停的重判下来还是 10 秒后停，计时照原来的走，不重新开始（{@link RecordingCoordinator}）。
     * 已经现在停了的不重判，不会因为信号变了自己录起来；锁拉着的等锁到点再判。</p>
     *
     * @param darkAction 这一次熄屏定下的做法（null = 亮着）
     * @param byLock     那个「接着录」是唤醒锁拉着车机才定的
     */
    static boolean rejudgesOnVehicleChange(Action darkAction, boolean byLock) {
        return (darkAction == Action.CONTINUE || darkAction == Action.STOP_LATER) && !byLock;
    }

    /**
     * 一次判定（或者安卓亮了）之后，「车机要睡」该写、该清，还是不动。事实说的是车机，不是屏幕：
     *
     * <ul>
     *   <li>安卓亮着（车机醒着：屏幕黑着也一样，E2'，或者亮屏）→ 写着就清，调度照常保持、开相机；</li>
     *   <li>安卓熄屏、判成现在停（{@link #headUnitSleeps}），还没写 → 写。车机要睡那一刻判的、10 秒后停重判成现在停的、
     *       唤醒锁到点判的，都是这一条；已经写着 → 不动，起算时刻不挪（调度「最多再等 1 秒」从写下那一刻算）。</li>
     *   <li>安卓熄屏、判成接着录或 10 秒后停 → 不动：没写的不写；写了的也不清 —— 相机已经在按次序关，停了的录像黑着不接。</li>
     * </ul>
     *
     * <p>只在真要变的时候给写或清：调用方照它写一行黑匣子，就是「一件事一行」。</p>
     *
     * @param androidOff 安卓熄屏着
     * @param judged     这一次照表判下来的做法（{@link #atDark}；安卓亮了、亮屏时 null）
     * @param written    「车机要睡」现在写着
     */
    static SleepFact sleepFact(boolean androidOff, Action judged, boolean written) {
        if (!androidOff) {
            return written ? SleepFact.CLEAR : SleepFact.KEEP;
        }
        if (judged == Action.STOP_NOW && !written) {
            return SleepFact.WRITE;
        }
        return SleepFact.KEEP;
    }

    /**
     * 屏幕黑着、车机醒着时，录像能不能（再）开起来：熄屏后不停录、计划里有环视 —— 只录环视（座舱黑着时谁都不要）。
     * 车机黑着醒了（E2'）的接回、存储一类停了之外的黑着时来的「要录」、恢复的例行接回，都照它（设计 v3.1 C1：熄屏持续录制关着
     * 就是「熄屏后停止录制」，黑着时一律不录，车机夜里自己醒了也不录）。唤醒锁拉着车机时熄屏持续录制必定开着，不另算。
     */
    static boolean startsWhileDark(boolean keeps, boolean surroundPlanned) {
        return keeps && surroundPlanned;
    }

    /**
     * 录制键上那行小字：现在熄屏的话会怎样 —— 就是下一次熄屏那一刻（E1）协调器照这张表判出来的（{@link #atDark}，同样的输入）。
     * 接着录 →「熄屏后继续录制环视」；停了亮屏接 →「熄屏暂停，亮屏后恢复」；不接 →「熄屏后停止录制」。
     *
     * <p>熄屏那一刻安卓熄没熄屏（{@code androidOff}）由叫的人按屏幕此刻怎么判给（{@code screen.ScreenState#vehicleJudges}）：
     * 车辆信号判着屏幕时是车辆先说黑、车机还醒着（false）—— 判不出现在停，哨兵模式、挡位改不了它；判不出屏幕时（降级：
     * 车辆接口没连着、两项读不到）熄屏只能是安卓熄屏，也就是车机下电（true）—— 和协调器一样照「车机要睡」那一行算上唤醒锁、
     * 哨兵模式、挡位：熄屏持续录制开着、哨兵模式没开的是「熄屏暂停，亮屏后恢复」（审查 rev3 B1；以前一律按车机醒着判，
     * 降级时写「继续录制」，熄屏那一下却现在停）。</p>
     *
     * @param lockHeld        熄屏那一刻开发者的唤醒锁拉不拉得住车机（{@link ScreenOffRecording#holdsCarAwake}：亮着时问的就是这个）
     * @param vehicle         车辆信号此刻的快照（{@code Telemetry.latest()}）
     * @param androidOff      熄屏那一刻安卓也熄屏了（车辆信号判不出屏幕，降级）
     * @param surroundPlanned 这一次录像（没在录时：配置里启用的那几路）里有环视（{@link RecordingCoordinator#surroundPlanned}）
     */
    public static ScreenOffPlan of(AppConfig config, boolean lockHeld, VehicleState vehicle, boolean androidOff,
                                   boolean surroundPlanned) {
        return of(atDark(config, lockHeld, vehicle, androidOff, surroundPlanned));
    }

    /** 判下来的做法写成哪一句。纯函数，测试直接调。 */
    static ScreenOffPlan of(Decision decision) {
        if (decision.action == Action.CONTINUE) {
            return CONTINUE;
        }
        return decision.resumesOnWake ? PAUSE : STOP;
    }
}
