package com.kooo.evcam.recording;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.R;
import com.kooo.evcam.camera.ChannelRules;
import com.kooo.evcam.telemetry.VehicleState;

/**
 * 熄屏时这段录像会怎样。一张表（{@link #atDark}，输入里有通道对环视的熄屏决定），两处照它：{@link RecordingCoordinator} 照它停不停、
 * 什么时候停、亮屏接不接、写不写「车机要睡」（熄屏的唯一裁判，在不在录都判），黑着能不能开录照 {@link #startsWhileDark}；
 * 录制键上那行小字照它说（{@link #of}：熄屏那一刻、车机要睡那一刻各照它判一次，输入和协调器的一样）—— 界面上写的就是实际会发生的。
 *
 * <h3>熄屏 v3（项目所有者 2026-10-12 D1–D3，设计 v3 §3）</h3>
 *
 * <p>「熄屏」是屏幕真黑了（{@code screen.ScreenState}：安卓熄屏，或者车辆说黑）。这台车上屏幕一黑车机还醒着几分钟，
 * 安卓的熄屏就是车机下电：哨兵模式没开时离车约 4–6 分钟后，先 U 盘写不进，不到 1 秒熄屏，3–5 秒后进程被结束或冻住
 * （2026-10-10、10-12 实测）；哨兵模式开着时车机一直醒着、安卓一直不熄屏。所以表里分两件事：</p>
 *
 * <ul>
 *   <li><b>座舱</b>不归这张表：屏幕黑满 30 秒（或者车机要睡），前后座舱谁都不要（录像、预览、拍照……，
 *       {@code ChannelRules.releasedByDark}、{@code darkForChannel}，D2、项目所有者 2026-10-13「黑满 30 秒再放」），
 *       亮屏回来、各自接回录像；</li>
 *   <li><b>环视</b>照表：熄屏持续录制开着（或开发者的唤醒锁拉着车机）、计划里有环视 → 接着录；否则熄屏
 *       {@link RecordingCoordinator#SCREEN_OFF_STOP_MS} 后停；</li>
 *   <li><b>车机要睡</b>（{@link #headUnitSleeps}：安卓熄屏了，唤醒锁没拉着，哨兵模式关或读不到，也没在开车）→ 现在停，
 *       相机按次序关（这时座舱早关了，只剩环视）—— 车机下电前那最后约 15 秒在内存里来不及写，项目所有者接受（D3）。
 *       安卓还亮着时（车机醒着）不会判成现在停；哨兵模式、挡位只在安卓熄屏时才改得了结果。</li>
 * </ul>
 *
 * <p>熄屏那一刻判一次（E1），车机要睡（E2）再判一次；熄屏期间开发者的唤醒锁到点、10 秒到点、车辆信号变了
 * （{@link #rejudgesOnVehicleChange}，只在安卓熄屏着时）也再判（{@link RecordingCoordinator}）。车机黑着醒了（E2'）不判、不开录：
 * 撤掉「车机要睡」。计划里没有环视的，黑着时和熄屏持续录制关着一样（不另开分支，公式本身就是）。</p>
 *
 * <h3>黑满 30 秒以后照通道的决定（项目所有者 2026-10-27）</h3>
 *
 * <p>通道调度对环视有一个熄屏决定（{@code ChannelScheduler.surroundVerdict}：LIT 不归熄屏管 —— 亮着、黑了不到 30 秒、在开车；
 * KEPT 留着；RELEASED 放开）。照亮着算（LIT）时这张表、黑着开不开录都和以前一样；之后只看这一个决定：通道放开了环视就现在停
 * （{@link Action#STOP_RELEASED}，「这一趟要录」照表留）；熄屏中哨兵模式变成开（布防）、通道留下了环视，「这一趟要录」立着才接着录
 * （{@link #onChannel}、{@link #startsWhileDark}）—— 别的黑着时来的要录不开（不为中间不明原因的启动去试着录）。</p>
 */
public enum ScreenOffPlan {

    /**
     * 熄屏后继续录制环视（座舱亮屏后接回），车机要睡那一刻照表也还是接着录：车机睡不了 —— 哨兵模式开着、在开车，
     * 或者开发者的唤醒锁拉着车机。
     */
    CONTINUE(R.string.record_screen_off_continue),
    /**
     * 熄屏后停，亮屏后恢复：熄屏持续录制关着（或计划里没有环视）、启动自动录制开着；或者车辆信号判不出屏幕（降级），
     * 熄屏就是车机下电 —— 现在停，熄屏持续录制、启动自动录制开着一个就亮屏接。
     * 还有熄屏那一刻接着录环视、车机要睡那一刻照表停（哨兵模式关或读不到）的那一种：环视录到车机休眠，暂停，醒来、亮屏照表接 ——
     * 项目所有者 2026-10-13 定这种也写这一句，不另加句子（行为不变）。
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
        STOP_NOW,
        /**
         * 通道放开了环视（熄屏持续录制关着、或者黑满 30 秒时哨兵模式没开、熄屏中哨兵关了、车机要睡，项目所有者 2026-10-27）：
         * 现在停，不写「车机要睡」—— 通道已经放开了环视，相机由它关。
         */
        STOP_RELEASED
    }

    /** 判下来的结果：怎么做，停了之后亮屏接不接。 */
    public static final class Decision {
        public final Action action;
        /**
         * 停了（{@link Action#STOP_LATER} / {@link Action#STOP_NOW} / {@link Action#STOP_RELEASED}）之后，亮屏（或熄屏中哨兵模式变成开，
         * {@link ScreenOffPlan#onChannel}）接着录；接着录的那种是 false。
         */
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
     * 车辆信号里这张表只看它和哨兵模式：录制键上的小字只在这两样变了时重判（{@code ui.ScreenOffHintView}）。
     */
    public static boolean driving(String gear, Float speedKmh) {
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
     * 照亮着算（通道的决定是 {@link ChannelRules.SurroundInDark#LIT}）时此刻黑着的话怎么做：录制键上的小字照它说（{@link #of}）——
     * 熄屏那一刻、车机要睡那一刻，不看通道之后的决定（熄屏持续录制开着、哨兵模式开着的「熄屏后继续录制环视」，就是黑满 30 秒时
     * 通道留下环视的那一种；哨兵模式没开的「熄屏暂停，亮屏后恢复」，就是那时通道放开、录像停的那一种）。
     *
     * @param lockHeld        开发者的唤醒锁拉着车机（{@link ScreenOffRecording#holdsCarAwake}）
     * @param vehicle         车辆信号此刻的快照（{@code Telemetry.latest()}）
     * @param androidOff      安卓熄屏了（{@code ScreenState.androidOff}）
     * @param surroundPlanned 这一次录像的计划里有环视（{@link RecordingCoordinator#surroundPlanned}）
     */
    public static Decision atDark(AppConfig config, boolean lockHeld, VehicleState vehicle, boolean androidOff,
                                  boolean surroundPlanned) {
        return atDark(config, lockHeld, vehicle, androidOff, surroundPlanned, ChannelRules.SurroundInDark.LIT);
    }

    /**
     * 此刻黑着的话怎么做（输入都在这里取齐）：协调器熄屏那一刻、车机要睡、10 秒到点、唤醒锁到点、车辆信号变了、通道的决定变了，都调它。
     *
     * @param lockHeld        开发者的唤醒锁拉着车机（{@link ScreenOffRecording#holdsCarAwake}）
     * @param vehicle         车辆信号此刻的快照（{@code Telemetry.latest()}）
     * @param androidOff      安卓熄屏了（{@code ScreenState.androidOff}）
     * @param surroundPlanned 这一次录像的计划里有环视（{@link RecordingCoordinator#surroundPlanned}）
     * @param channel         通道对环视的熄屏决定（协调器读的那一个，{@link #channelVerdict}）
     */
    public static Decision atDark(AppConfig config, boolean lockHeld, VehicleState vehicle, boolean androidOff,
                                  boolean surroundPlanned, ChannelRules.SurroundInDark channel) {
        return atDark(keepsRecording(config), lockHeld, config.isAutoStartRecording(), vehicle.sentry,
                driving(vehicle.gear, vehicle.speedKmh), androidOff, surroundPlanned, channel);
    }

    /** 照亮着算（通道的决定是 LIT）时的那张表：熄屏那一刻、头 30 秒、在开车，和录制键上的小字（{@link #of}）。纯函数，测试直接调。 */
    static Decision atDark(boolean keeps, boolean lockHeld, boolean autoRecord, Integer sentry, boolean driving,
                           boolean androidOff, boolean surroundPlanned) {
        return atDark(keeps, lockHeld, autoRecord, sentry, driving, androidOff, surroundPlanned,
                ChannelRules.SurroundInDark.LIT);
    }

    /**
     * 那张表（项目所有者 2026-10-10，熄屏 v3 2026-10-12，通道的决定 2026-10-27）。纯函数，测试直接调。
     *
     * <ul>
     *   <li>车机要睡（{@link #headUnitSleeps}）→ 现在停，相机按次序关；</li>
     *   <li>通道放开了环视（{@code channel} 是 RELEASED：黑满 30 秒以后）→ 现在停（{@link Action#STOP_RELEASED}），不写「车机要睡」；</li>
     *   <li>熄屏后不停录（{@link #keepsRecording}）或唤醒锁拉着车机，而且计划里有环视 → 接着录环视（D2：只有环视在黑着时录）。
     *       照亮着算时（黑了不到 30 秒、在开车）这就是以前的那一行；黑满 30 秒时通道照哨兵模式定留不留，留着的接着录；</li>
     *   <li>其余 → 熄屏 10 秒后停（车机醒着，这 10 秒真的会走完）；</li>
     *   <li>停了之后亮屏接不接：熄屏后不停录、启动自动录制开着一个就接（「这一趟要录」留着）。</li>
     * </ul>
     *
     * @param keeps           熄屏后不停录（{@link #keepsRecording}）
     * @param lockHeld        开发者的唤醒锁拉着车机
     * @param autoRecord      「启动自动录制」开着
     * @param sentry          哨兵模式（{@code VehicleState.sentry}：0 关、1 开、2 布防，null 读不到）
     * @param driving         在开车（{@link #driving}）
     * @param androidOff      安卓熄屏了
     * @param surroundPlanned 计划里有环视
     * @param channel         通道对环视的熄屏决定（{@link #channelVerdict}）
     */
    static Decision atDark(boolean keeps, boolean lockHeld, boolean autoRecord, Integer sentry, boolean driving,
                           boolean androidOff, boolean surroundPlanned, ChannelRules.SurroundInDark channel) {
        Action action;
        if (headUnitSleeps(androidOff, lockHeld, sentry, driving)) {
            action = Action.STOP_NOW;
        } else if (channel == ChannelRules.SurroundInDark.RELEASED) {
            action = Action.STOP_RELEASED;
        } else if ((lockHeld || keeps) && surroundPlanned) {
            action = Action.CONTINUE;
        } else {
            action = Action.STOP_LATER;
        }
        return new Decision(action, action != Action.CONTINUE && (keeps || autoRecord));
    }

    /**
     * 协调器读的通道决定：通道还没把屏幕算黑（亮着、黑了不到 30 秒，{@code MultiCameraManager.channelCountsDark}，和调度同一句、
     * 同一组事实）时一律照亮着算 —— 熄屏那一刻、头 30 秒照以前的规矩，不靠调度跑没跑过一轮；算黑了读调度发布的那一个
     * （{@code ChannelScheduler.surroundVerdict}：进程在黑着时起来的是放开）。
     *
     * @param channelDark 通道此刻把屏幕算作黑了
     * @param published   调度发布的决定
     */
    static ChannelRules.SurroundInDark channelVerdict(boolean channelDark, ChannelRules.SurroundInDark published) {
        return channelDark ? published : ChannelRules.SurroundInDark.LIT;
    }

    /**
     * 熄屏期间哨兵模式、挡位变了，要不要照表再判一次：「接着录」（不是唤醒锁拉着的那种）和「10 秒后停」都要
     * （channel-logic §四「熄屏期间情况变了，重判一次」，项目所有者 2026-10-10）。熄屏时在开车、后来挂 P 下车，
     * 或者哨兵模式关了，都不会再有熄屏事件。只有安卓熄屏着时它才改得了结果（车机醒着时车辆信号判不出现在停）：
     * 车机要睡时在开车、哨兵模式开着的那一种，之后挂 P、关哨兵模式就靠它。安卓亮着时不重判（2026-10-27）——
     * 车机醒着时哨兵模式变了归通道管（{@link #onChannel}），这里重判只会写一行过时的「接着录」。
     *
     * <p>设置不变时，这种重判只会停在原处或者判成现在停：表里能让它往「接着录」走的只有开关和唤醒锁，都不是车辆信号。
     * 10 秒后停的重判下来还是 10 秒后停，计时照原来的走，不重新开始（{@link RecordingCoordinator}）。
     * 已经停了的（现在停、通道放开了）不重判，不会因为信号变了自己录起来；锁拉着的等锁到点再判。</p>
     *
     * @param darkAction 这一次熄屏定下的做法（null = 亮着）
     * @param byLock     那个「接着录」是唤醒锁拉着车机才定的
     * @param androidOff 安卓熄屏着（车机要睡了 / 睡着）
     */
    static boolean rejudgesOnVehicleChange(Action darkAction, boolean byLock, boolean androidOff) {
        return androidOff && (darkAction == Action.CONTINUE || darkAction == Action.STOP_LATER) && !byLock;
    }

    /**
     * 一次判定（或者安卓亮了）之后，「车机要睡」该写、该清，还是不动。事实说的是车机，不是屏幕：
     *
     * <ul>
     *   <li>安卓亮着（车机醒着：屏幕黑着也一样，E2'，或者亮屏）→ 写着就清，调度照常保持、开相机；</li>
     *   <li>安卓熄屏、判成现在停（{@link #headUnitSleeps}），还没写 → 写。车机要睡那一刻判的、10 秒后停重判成现在停的、
     *       唤醒锁到点判的，都是这一条；已经写着 → 不动，起算时刻不挪（调度「最多再等 1 秒」从写下那一刻算）。</li>
     *   <li>安卓熄屏、判成接着录、10 秒后停或者通道放开了（{@link Action#STOP_RELEASED}）→ 不动：没写的不写；写了的也不清 ——
     *       相机已经在按次序关，停了的录像黑着不接。</li>
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

    /** 屏幕黑着时录像开不开得起来，开不起来是因为什么（{@link #startsWhileDark}；协调器拦下时那一行照它写）。 */
    enum DarkStart {
        /** 开：只录环视。 */
        STARTS,
        /** 车机要睡（安卓熄屏）。 */
        HEAD_UNIT_SLEEPS,
        /** 计划里没有环视（座舱黑着时谁都不要）。 */
        NO_SURROUND,
        /** 熄屏持续录制关着：熄屏后不录。 */
        SETTING_OFF,
        /** 通道没留环视（黑满 30 秒时哨兵模式没开、熄屏中哨兵关了、车机要睡、进程在黑着时起来）。 */
        CHANNEL_RELEASED,
        /** 通道留着环视，可这一下不是熄屏中哨兵模式变成开（别的时候来的要录不开）。 */
        NO_SENTRY_RISE,
        /** 熄屏中哨兵模式变成开，可现在用不了相机（前台服务是后台起的）：不开，亮屏再接。 */
        CAMERA_UNUSABLE
    }

    /**
     * 屏幕黑着时录像能不能（再）开起来（只录环视，座舱黑着时谁都不要）。黑着时来的「要录」（存储一类的自动接回除外）、恢复的例行接回、
     * 熄屏中哨兵模式变成开的接回，都照它（设计 v3.1 C1；项目所有者 2026-10-27）：
     *
     * <ul>
     *   <li>车机要睡（安卓熄屏）→ 不开；计划里没有环视 → 不开；</li>
     *   <li>照亮着算（通道的决定是 LIT：黑了不到 30 秒、在开车）→ 以前的规矩：熄屏后不停录（{@link #keepsRecording}）就开；</li>
     *   <li>黑满 30 秒以后：熄屏持续录制关着 → 不开；通道放开了环视 → 不开；通道留着环视 —— 只有熄屏中哨兵模式变成开（布防）
     *       那一下的接回（{@code bySentryRise}，{@link #onChannel}）开，能用相机才开（App 自己起不来就没有后续）；别的时候来的不开
     *       （不为中间不明原因的启动去试着录）。</li>
     * </ul>
     *
     * @param channel         通道对环视的熄屏决定（{@link #channelVerdict}）
     * @param bySentryRise    这一下是熄屏中哨兵模式变成开、通道留下环视之后的接回
     * @param androidOff      安卓熄屏着
     * @param keeps           熄屏后不停录（{@link #keepsRecording}：含开发者的熄屏录制）
     * @param keepSetting     「熄屏持续录制」开着（通道留不留环视看的那一个）
     * @param surroundPlanned 计划里有环视
     * @param cameraUsable    现在能用相机（{@code MultiCameraManager.cameraUsableNow}）
     */
    static DarkStart startsWhileDark(ChannelRules.SurroundInDark channel, boolean bySentryRise, boolean androidOff,
                                     boolean keeps, boolean keepSetting, boolean surroundPlanned, boolean cameraUsable) {
        if (androidOff) {
            return DarkStart.HEAD_UNIT_SLEEPS;
        }
        if (!surroundPlanned) {
            return DarkStart.NO_SURROUND;
        }
        if (channel == ChannelRules.SurroundInDark.LIT) {
            return keeps ? DarkStart.STARTS : DarkStart.SETTING_OFF;
        }
        if (!keepSetting) {
            return DarkStart.SETTING_OFF;
        }
        if (channel == ChannelRules.SurroundInDark.RELEASED) {
            return DarkStart.CHANNEL_RELEASED;
        }
        if (!bySentryRise) {
            return DarkStart.NO_SENTRY_RISE;
        }
        return cameraUsable ? DarkStart.STARTS : DarkStart.CAMERA_UNUSABLE;
    }

    /** 通道的决定变了，协调器做什么（{@link #onChannel}）。 */
    enum ChannelStep {
        NOTHING,
        /** 熄屏停（通道放开了环视）：在录的停、在等开录的不等、在等自动接回的不等，「这一趟要录」照表留。 */
        STOP,
        /** 熄屏中哨兵模式变成开、通道留下了环视：「这一趟要录」立着就接着录（只录环视）。 */
        START
    }

    /**
     * 通道对环视的熄屏决定变了（{@code ChannelScheduler} 发的，协调器在主线程上照此刻的决定做）：
     *
     * <ul>
     *   <li>亮着 → 什么都不做（亮屏那一下自己接）；</li>
     *   <li>在录（含等开录、等自动接回）→ 通道放开了环视就停，别的不动（留着的接着录，照亮着算的照那张表）；</li>
     *   <li>没在录 → 只有熄屏中哨兵模式变成开（醒来读到睡着时被打开的也算）让通道留下了环视才接着录 —— 通道刚算黑那一下留着、
     *       没在录的不开（R1：黑满 30 秒那一下在录才留着录）。</li>
     * </ul>
     *
     * @param verdict 通道此刻的决定（{@link #channelVerdict}）
     * @param cause   那个决定为什么变成这样（{@code ChannelScheduler.surroundVerdictCause}；null = 不是环视留不留变出来的）
     * @param dark    屏幕黑着（{@code ScreenState.dark}）
     * @param busy    在录、在等开录或者在等自动接回
     */
    static ChannelStep onChannel(ChannelRules.SurroundInDark verdict, ChannelRules.Event.Cause cause, boolean dark,
                                 boolean busy) {
        if (!dark) {
            return ChannelStep.NOTHING;
        }
        if (busy) {
            return verdict == ChannelRules.SurroundInDark.RELEASED ? ChannelStep.STOP : ChannelStep.NOTHING;
        }
        return verdict == ChannelRules.SurroundInDark.KEPT && cause == ChannelRules.Event.Cause.SENTRY_ON
                ? ChannelStep.START : ChannelStep.NOTHING;
    }

    /**
     * 录制键上那行小字：现在熄屏的话会怎样 —— 协调器照这张表判的那两下，同样的输入（{@link #atDark}）：
     * 下一次熄屏那一刻（E1），和之后车机要睡那一刻（E2：同样的输入，只是安卓熄屏了）。不另立规矩，见 {@link #of(Decision, Decision)}。
     *
     * <p>熄屏那一刻安卓熄没熄屏（{@code androidOff}）由叫的人按屏幕此刻怎么判给（{@code screen.ScreenState#vehicleJudges}）：
     * 车辆信号判着屏幕时是车辆先说黑、车机还醒着（false）—— 熄屏那一刻判不出现在停，哨兵模式、挡位要到车机要睡那一刻才改得了结果；
     * 判不出屏幕时（降级：车辆接口没连着、两项读不到）熄屏只能是安卓熄屏，也就是车机下电（true）—— 两下是同一下，
     * 和协调器一样照「车机要睡」那一行算上唤醒锁、哨兵模式、挡位（审查 rev3 B1）。</p>
     *
     * @param lockHeld        熄屏那一刻开发者的唤醒锁拉不拉得住车机（{@link ScreenOffRecording#holdsCarAwake}：亮着时问的就是这个）
     * @param vehicle         车辆信号此刻的快照（{@code Telemetry.latest()}：哨兵模式、挡位，核心一直在读）
     * @param androidOff      熄屏那一刻安卓也熄屏了（车辆信号判不出屏幕，降级）
     * @param surroundPlanned 这一次录像（没在录时：配置里启用的那几路）里有环视（{@link RecordingCoordinator#surroundPlanned}）
     */
    public static ScreenOffPlan of(AppConfig config, boolean lockHeld, VehicleState vehicle, boolean androidOff,
                                   boolean surroundPlanned) {
        return of(atDark(config, lockHeld, vehicle, androidOff, surroundPlanned),
                atDark(config, lockHeld, vehicle, true, surroundPlanned));
    }

    /**
     * 两下判下来的做法写成哪一句（项目所有者 2026-10-13：小字要说清车机休眠时还录不录）。纯函数，测试直接调。
     *
     * <ul>
     *   <li>熄屏那一刻接着录、车机要睡那一刻也接着录（哨兵模式开着、在开车、唤醒锁拉着）→「熄屏后继续录制环视」；</li>
     *   <li>熄屏那一刻接着录、车机要睡那一刻现在停 →「熄屏暂停，亮屏后恢复」（项目所有者 2026-10-13 定：不另加句子；环视照样录到车机休眠）；</li>
     *   <li>熄屏那一刻就停（10 秒后停、现在停）→ 亮屏接的「熄屏暂停，亮屏后恢复」，不接的「熄屏后停止录制」。</li>
     * </ul>
     *
     * <p>熄屏那一刻接着录时，车机要睡那一刻只会是接着录或现在停（表里能让它往「10 秒后停」走的只有开关和唤醒锁，两下一样）。</p>
     *
     * @param atDark  熄屏那一刻（E1）的判定
     * @param atSleep 同样的输入、安卓熄屏了（车机要睡，E2）的判定
     */
    static ScreenOffPlan of(Decision atDark, Decision atSleep) {
        if (atDark.action == Action.CONTINUE) {
            return atSleep.action == Action.STOP_NOW ? PAUSE : CONTINUE;
        }
        return atDark.resumesOnWake ? PAUSE : STOP;
    }
}
