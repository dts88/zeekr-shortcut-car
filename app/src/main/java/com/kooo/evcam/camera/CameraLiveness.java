package com.kooo.evcam.camera;

/**
 * 一路相机「还活着吗」的判定：多久没有动静算死了、什么时候动手救、救几次不管用就停手。
 *
 * <h3>为什么还要一层</h3>
 *
 * <p>{@link SingleCamera} 以前自己有一套按帧判定的自愈（2.5 秒没帧就重建会话，再不行就重开相机，
 * 用的还是墙钟 —— 深睡醒来会把一夜算成卡住）。那一套<b>全靠相机的回调驱动</b>，
 * 而它自己也只在会话配置成功的那一刻启动：</p>
 *
 * <ul>
 *   <li>会话建到一半 HAL 不回调了 → {@code isConfiguring} 一直是 true，自愈每次检查都直接跳过；</li>
 *   <li>会话没了又没建起来 → {@code captureSession == null}，自愈<b>把自己停掉</b>，
 *       而它唯一的启动点正是那个永远不会来的 {@code onConfigured}；</li>
 *   <li>{@code openCamera} 发出去没回音 → {@code isOpening} 卡在 true，之后每一次打开请求
 *       都会被「已经在打开了」挡掉。</li>
 * </ul>
 *
 * <p>三条都是<b>回调没来</b>，而回调没来正是车机相机挂住时的典型表现 ——
 * 于是「靠回调驱动的自愈」恰好在最需要它的时候是哑的。表现就是：进程还活着、
 * 悬浮按钮照常响应，但后视镜冻在最后一帧、预览全白、录制起不来，只能重启应用。</p>
 *
 * <p>所以这一层<b>不看相机的任何状态标志，只看有没有帧</b>。该出帧而长时间没有，
 * 就动手，不问为什么 —— 卡在哪个标志上都一样救。<b>1.62.0 起它是唯一的一个</b>：
 * SingleCamera 那套 2.5 秒检测、前台服务每 10 秒一次的修复循环都删了；第一次先重建会话（便宜），
 * 再不行才重开相机，两级都在 MultiCameraManager.checkLiveness 里。</p>
 *
 * <p><b>2.10.10 起连报错重连也归这里</b>（项目所有者 2026-10-09：「获取打开，关闭退出，仅此而已」）：
 * SingleCamera 收到设备报错 / 被断开只把设备关掉、标成「没开」，不再自己按错误码退避重连 ——
 * 那一套和这里互相顶（2026-10-08 自己顶自己 7 次）。设备报错算「已经卡住」，不用再等 {@link #STUCK_MS}；
 * 这一路被别的程序拿走时（{@link CameraTaken#benched}）每 {@link CameraTaken#RETRY_WHILE_HELD_MS} 试一次、不计次数，
 * 它放开、通道安静了由 MultiCameraManager.checkLiveness 单独重开（{@link CameraTaken#gate}）—— 被拿走的相机只从这里重开。</p>
 *
 * <h3>为什么要停手</h3>
 *
 * <p>相机真被别的应用占着的时候，重开是救不回来的。一直重开只会不停地打扰相机服务，
 * 还把日志刷满。所以连着救 {@link #MAX_ATTEMPTS} 次没用就<b>停手一分钟</b>，
 * 一分钟后再来一轮 —— 人回到车上、别的应用退出，都在这个尺度上。</p>
 *
 * <h3>2.11 起</h3>
 *
 * <p>救的是通道调度（项目所有者 2026-10-10 确认），梯子上的数字不变。被别的程序拿着的那一路
 * 根本不上梯子：调度判它被拿着（{@link CameraTaken.Verdict#TAKEN} / {@link CameraTaken.Verdict#BLOCKED}）就不救、不试开，
 * 只问相机服务它空没空，所以新的 {@link #step(State, boolean, long, long)} 没有「被别人拿着」那个参数。
 * 只在「保持 30 秒」里的路不算有人要，不救。</p>
 *
 * <p>彻底停手之后再试的时机（{@link State#liftStop()}）：登记表变了（项目所有者 2026-10-10 确认）；
 * 项目所有者 2026-10-11 又加上相机服务报这一路「空闲」、亮屏 —— 录像开着时 RECORDING 一直登记着，登记表不会自己变，
 * 只等它的话，各路都停手之后录像会一直「等待」下去。</p>
 *
 * <p>纯逻辑，时间由调用方传入，见 {@code CameraLivenessTest}。</p>
 */
public final class CameraLiveness {

    /**
     * 该出帧而这么久没出，判定这一路卡住了。
     *
     * <p>正常的会话重建、切换录制都会让帧停上一两秒，不该被这里抢着动手。</p>
     */
    public static final long STUCK_MS = 8000L;

    /** 两次重开之间至少隔这么久 —— 重开本身要花几百毫秒，还要等会话重建。 */
    public static final long RETRY_GAP_MS = 12000L;

    /** 连着救这么多次都没用就停手。 */
    public static final int MAX_ATTEMPTS = 3;

    /** 停手之后过这么久允许再来一轮。 */
    public static final long COOL_OFF_MS = 60000L;

    /**
     * 这么多轮都没救回来就<b>彻底停手</b>，不再周期性地试。
     *
     * <p>没有这一条的话「歇一分钟再来一轮」是无限的：相机服务真卡死时，
     * 我们每分钟去捶一次，一夜下来几百次，既救不回来，又让已经卡住的
     * 相机服务更难缓过来，日志也被刷满。</p>
     *
     * <p>彻底停手之后只有两件事能让它重新开始：真的出了一帧，
     * 或者这一路不再需要画面（收起后视镜、停止录制）之后重新需要。</p>
     */
    public static final int MAX_CYCLES = 3;

    private CameraLiveness() {
    }

    public enum Action {
        /** 什么都不做。 */
        NONE,
        /** 重开这一路相机。 */
        RESET,
        /** 这一轮救不动了，歇一会儿再来（只记一次日志）。 */
        GIVE_UP,
        /** 几轮都没用，彻底停手，不再周期性地试（只记一次日志）。 */
        STOP
    }

    /** 一路相机的救援状态，只在监测线程上读写。 */
    public static final class State {
        private int attempts;
        private long lastResetMs;
        private boolean gaveUp;
        private long gaveUpAtMs;
        private int cycles;
        private boolean stopped;

        public int attempts() {
            return attempts;
        }

        public boolean gaveUp() {
            return gaveUp;
        }

        public boolean stopped() {
            return stopped;
        }

        public int cycles() {
            return cycles;
        }

        /** 帧回来了、或者这一路不再需要画面 —— 一切归零，包括彻底停手的状态。 */
        private void clear() {
            attempts = 0;
            lastResetMs = 0;
            gaveUp = false;
            gaveUpAtMs = 0;
            cycles = 0;
            stopped = false;
        }

        /**
         * 别的程序放开了相机、或者访问优先级变了（{@link CameraTaken}）：被拿走的这一路下一次检查就可以试，
         * 不等 {@link CameraTaken#RETRY_WHILE_HELD_MS} 的节奏。2026-10-10 起放开时不再当场重开，
         * 由下一次检查按闸门来（{@link CameraTaken#gate}）。
         */
        public void due() {
            lastResetMs = 0;
        }

        /**
         * 被拿走的这一路放开了、通道也安静了，看门狗要单独重开它：从头算 —— 被别的程序占着的那段时间不是
         * 「救不动」，之前攒的次数、歇着、彻底停手都作废（以前放开时 retryTaken 不看这些、直接重开）。
         */
        public void released() {
            clear();
        }

        /**
         * 解除停手、从头救：彻底停手（{@link #stopped()}）的这一路，攒的次数、轮数都作废，
         * 下一次 {@link CameraLiveness#step(State, boolean, long, long)} 当场就救。
         *
         * <p>三件事调它，是同一个判断：登记表变了（{@link #registerChanged()}，项目所有者 2026-10-10 确认）、
         * 相机服务报这一路「空闲」、亮屏（项目所有者 2026-10-11 定）。代价是 HAL 真坏了时会多试几轮；不加新的界面文字。</p>
         *
         * <p>只管彻底停手的：歇着的（{@link #gaveUp()}）、梯子上的照原来的节奏走 —— 后视镜收起又放出、登记表来回变，
         * 不该让救援比 {@link CameraLiveness#RETRY_GAP_MS} 一次更密。</p>
         *
         * @return true：原来停着手、这一下解除了（调用方据此写一行「停手的 … 重新救」）；false：本来就没停手，什么都没动
         */
        public boolean liftStop() {
            if (!stopped) {
                return false;
            }
            clear();
            return true;
        }

        /** 登记表变了（谁要哪几路变了）：停手的这一路重新救，同 {@link #liftStop()}。 */
        public boolean registerChanged() {
            return liftStop();
        }
    }

    /**
     * 往前走一步（2.10 的入口，看门狗用）。没被别人拿着时走的就是下面那个不带 {@code heldByOthers} 的；
     * 「被拿着每 30 秒试一次」这一支连同这个入口，等看门狗换成通道调度之后一起删。
     *
     * @param wantsFrames 这一路此刻该不该出帧：有人在用它的画面、不是被主动暂停的，
     *                    <b>而且这一趟成功打开过</b> —— 压根没打开过的不归这里管
     * @param frameAgeMs  距上一次「有动静」多久 —— 出了一帧、开了相机、建好会话，都算动静
     * @param now         单调时钟，不含深度睡眠（车停着睡一夜，醒来不该算卡了一整夜）
     * @param heldByOthers 这一路被别的程序拿走了、还没放开（{@link CameraTaken#benched}，看的是这一路自己，
     *                    不是「别的程序占着哪一路」—— 2026-10-10 之前看后者，一路被占着，卡住的环视也跟着每 30 秒
     *                    整个重开、不计次数）：重开多半失败，只每 {@link CameraTaken#RETRY_WHILE_HELD_MS} 试一次（保险），
     *                    不计次数、不停手；放开、通道安静了由看门狗的闸门单独重开（{@link CameraTaken#gate}）
     */
    public static Action step(State state, boolean wantsFrames, long frameAgeMs, long now, boolean heldByOthers) {
        if (heldByOthers && wantsFrames && frameAgeMs >= STUCK_MS) {
            if (state.lastResetMs != 0 && now - state.lastResetMs < CameraTaken.RETRY_WHILE_HELD_MS) {
                return Action.NONE;
            }
            state.lastResetMs = now;
            return Action.RESET;
        }
        return step(state, wantsFrames, frameAgeMs, now);
    }

    /**
     * 往前走一步（2.11 起通道调度用）：梯子和上面那个一样，只是没有「被别人拿着」—— 被拿着的路调度根本不救。
     *
     * @param wantsFrames 这一路此刻有人要画面（登记表里有人要它 —— 只在「保持 30 秒」里的不算），
     *                    而且没被拿着（不是 TAKEN / BLOCKED）、现在能用相机
     * @param frameAgeMs  距上一次「有动静」多久 —— 出了一帧、开了相机、建好会话，都算动静；丢了算无穷大
     * @param now         单调时钟，不含深度睡眠
     */
    public static Action step(State state, boolean wantsFrames, long frameAgeMs, long now) {
        if (!wantsFrames || frameAgeMs < STUCK_MS) {
            // 没人用，或者帧回来了 —— 前面攒的次数一笔勾销
            state.clear();
            return Action.NONE;
        }
        if (state.stopped) {
            return Action.NONE;
        }
        if (state.gaveUp) {
            if (now - state.gaveUpAtMs < COOL_OFF_MS) {
                return Action.NONE;
            }
            // 歇够了，再来一轮 —— 但轮数要留着，不能跟着一起清零
            int cycles = state.cycles;
            state.clear();
            state.cycles = cycles;
        }
        if (state.lastResetMs != 0 && now - state.lastResetMs < RETRY_GAP_MS) {
            return Action.NONE;
        }
        if (state.attempts >= MAX_ATTEMPTS) {
            state.gaveUp = true;
            state.gaveUpAtMs = now;
            state.cycles++;
            if (state.cycles >= MAX_CYCLES) {
                state.stopped = true;
                return Action.STOP;
            }
            return Action.GIVE_UP;
        }
        state.attempts++;
        state.lastResetMs = now;
        return Action.RESET;
    }
}
