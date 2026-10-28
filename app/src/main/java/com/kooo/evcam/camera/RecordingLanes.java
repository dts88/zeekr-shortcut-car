package com.kooo.evcam.camera;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 录像每一路各自进出（channel-logic §三，项目所有者 2026-10-10 确认，2.11）：每一路在录像表上的状态，和跟着它走的几条规矩。
 *
 * <h3>为什么</h3>
 *
 * <p>2.10 的录像是一整块：开录要等环视、等各路按次序开完，再一起建会话、一起启动；最后一路在录的被别的程序拿走就整次停，
 * 再等环视「接回」，接回还有次数。项目所有者 2026-10-10 定：开录只是打开「录像意图」、定下这次录哪几路；
 * 每一路的相机带着录像输出出了画面，这一路就加入 —— 第一次开录和中途接回是同一件事。一路的相机被拿走、断开或卡住，
 * 这一路自己收尾（文件封好）退出，别的路不动；回来了再加入。所有路都暂时录不了时录像不算停，显示「等待摄像头」。</p>
 *
 * <h3>状态（主线程，只有 MultiCameraManager 的录像宿主改）</h3>
 *
 * <ul>
 *   <li>{@link State#WAITING} 不在录：等它的相机带着录像输出出画面、录制器停放着或备好了（{@link #joins}）；</li>
 *   <li>{@link State#STARTING} 在加入：录制器在重新备好、或者开了录还没写进第一笔；</li>
 *   <li>{@link State#LIVE} 在录：写进过第一笔。</li>
 * </ul>
 *
 * <p>每一路带一个计数（{@link #token}）：退出一次、加入一次都 +1。排着的任务（重新备好、录制器的回调）都带着它，
 * 回来对不上就作废 —— 同一个录像意图里一路可能退出、加入、又退出、又加入，录像的代数一直不变。</p>
 *
 * <h3>整体的样子（{@link #summarize}）</h3>
 *
 * <p>录制键、通知、悬浮按钮照 {@link View} 画：有一路在录是在录；一路都没在录、但有一路在起来，这个意图里还没录起来过是准备中，
 * 录起来过是等待；一路都没在录、也没有一路在起来（都被拿着、在救、停手、现在用不了相机、熄屏放开了、关满 60 秒没返回，
 * 或者录制器建不起来）是等待，开录时也一样。一个意图里出了准备中，就不再回到准备中。</p>
 *
 * <p>纯 Java，时间由调用方传入，见 {@code RecordingLanesTest}。</p>
 */
public final class RecordingLanes {

    /**
     * 判「写不进是不是相机的事」时，相机最近多久以内出过画面算还在出（{@link #stallIsCamera} 的 freshFrames）。
     * 和以前「环视正常出画面」用的是同一个数。
     */
    public static final long FRESH_FRAMES_MS = 2_000L;

    /** 一路此刻在不在录。 */
    public enum State {
        /** 不在录：等它的相机带着录像输出出画面、录制器停放着或备好了就加入。开录时每一路都从这里起。 */
        WAITING,
        /** 在加入：录制器在重新备好、或者开了录，还没写进第一笔。 */
        STARTING,
        /** 在录：写进过第一笔。 */
        LIVE
    }

    /** 一路的录制器此刻怎样（宿主报的）。 */
    public enum Recorder {
        /**
         * 还没有：这一路的画面尺寸还没算出来（它不在相机列表里），等它回来再建；会话刚带不上它的录像输出，隔一会儿再声明；
         * MediaRecorder 模式下相机被拿着、在救、停手、现在用不了相机、熄屏放开时也不备（{@link #keepsNamedRecorder}）；
         * 编码录制熄屏放开的座舱也不建（亮屏再建，MultiCameraManager.ensureRecorder）。
         */
        NONE,
        /** 在建（编码器、EGL、输入 SurfaceTexture；MediaRecorder 在配、在 prepare）。 */
        BUILDING,
        /** 停放着、或者备好了：能加入。 */
        READY,
        /** 在忙：在录、在开录、在重新备好、在停、在停放。 */
        BUSY,
        /**
         * 录不了这一路：录制器建不起来（这一个录像意图里不再录它），或者会话连着几次都带不上它的录像输出
         * （等它的相机走了回来、登记表变了或者亮屏时再试）。
         */
        FAILED
    }

    /** 录像整体此刻的样子：录制键、通知、悬浮按钮照它画。 */
    public enum View {
        /** 准备中：这个录像意图里还没有一路录起来过，而有一路在起来。 */
        PREPARING,
        /** 在录：至少一路在录。 */
        RECORDING,
        /** 等待摄像头：一路都没在录（录像不算停），恢复后自动继续。 */
        WAITING
    }

    /** 一路此刻的事实：录像表上的状态、调度说的相机怎样、录制器怎样。 */
    public static final class Facts {
        public final String key;
        public final State state;
        public final ChannelRules.Condition condition;
        /**
         * 这一路关着、调度现在用不了相机（主界面不在前、前台服务是后台起的 —— 安卓 12 起这样的服务开不了相机）：
         * 主界面回到前台之前调度不会开它；或者它在等一次满 60 秒还没返回的关（{@link #heldByClose}）。
         * 调度说它「在起来」（{@link ChannelRules.Condition#BRINGING_UP}），其实一时半会儿起不来。
         */
        public final boolean cannotOpen;
        /**
         * 这一路的一次关满 60 秒还没返回（ChannelScheduler.heldByClose，项目所有者 2026-10-28）：那一次关返回之前调度不开它，
         * 不设时限。别的路在录时，状态条照现成的那一句说它无法录制（{@link Hint.Kind#LANE_UNAVAILABLE}）。
         * 它也算在 {@link #cannotOpen} 里。
         */
        public final boolean heldByClose;
        /**
         * 这一路被熄屏放开了（ChannelScheduler.releasedByDark：熄屏时座舱谁都不要，项目所有者 2026-10-12 D2）：亮屏之前调度
         * 不会开它。调度照旧说它「在起来」或者「被拿着」（车机熄屏约 98 秒后拿走后座舱），其实是熄屏让它走的 ——
         * 不算在起来（不然熄屏时开的录落定不了），不为它备文件名定死了的录制器，状态条不说它被占用。
         */
        public final boolean releasedByDark;
        public final Recorder recorder;

        public Facts(String key, State state, ChannelRules.Condition condition, boolean cannotOpen,
                     boolean heldByClose, boolean releasedByDark, Recorder recorder) {
            this.key = key;
            this.state = state;
            this.condition = condition;
            this.cannotOpen = cannotOpen;
            this.heldByClose = heldByClose;
            this.releasedByDark = releasedByDark;
            this.recorder = recorder;
        }
    }

    /** 主界面状态条上该说哪一句（{@link #hint}）。 */
    public static final class Hint {
        public enum Kind {
            /** 不说。 */
            NONE,
            /** 一路被占用，别的照录，释放后这一路自动恢复：{@link Hint#key} 是那一路。 */
            LANE_TAKEN,
            /** 两路以上被占用。 */
            LANES_TAKEN,
            /**
             * 一路无法录制（救了 3 轮停手、录不了：{@link Recorder#FAILED}，或者它的一次关满 60 秒还没返回：{@link Facts#heldByClose}），
             * 别的照录：{@link Hint#key} 是那一路。
             */
            LANE_UNAVAILABLE,
            /**
             * 一路都没在录，而每一路都停手了、或者录不了（{@link Recorder#FAILED}）：没有一路会自己按节奏重试。
             * 项目所有者 2026-10-11（§7.4）：停手的一路，相机服务报它空闲、或者亮屏时从头救，不加新文字 ——
             * 界面照「等待摄像头」说，这一种只进黑匣子（MultiCameraManager 在样子变了时写那一行）。
             */
            ALL_GAVE_UP
        }

        static final Hint NONE = new Hint(Kind.NONE, null);

        public final Kind kind;
        /** 说的是哪一路；只有 {@link Hint.Kind#LANE_TAKEN}、{@link Hint.Kind#LANE_UNAVAILABLE} 有。 */
        public final String key;

        Hint(Kind kind, String key) {
            this.kind = kind;
            this.key = key;
        }

        boolean sameAs(Hint other) {
            return other != null && kind == other.kind && (key == null ? other.key == null : key.equals(other.key));
        }
    }

    /** 这个录像意图此刻的样子（{@link #summarize}）：给协调器、录制键、通知、悬浮按钮读。不可变。 */
    public static final class Summary {
        /** 没在录（没有录像意图）。 */
        public static final Summary NONE = new Summary(View.PREPARING, Collections.<String>emptyList(),
                Collections.<String>emptyList(), false, Hint.NONE);

        public final View view;
        /** 这个录像意图计划录的那几路，按开的次序。 */
        public final List<String> planned;
        /** 此刻在录的那几路（{@link State#LIVE}），按开的次序。 */
        public final List<String> liveKeys;
        /**
         * 开录落定了：每一路都录起来了、或者暂时起不来（被拿着、在救、停手、现在用不了相机、熄屏放开了、录不了）。
         * 一个录像意图里只从假变真一次。
         */
        public final boolean settled;
        public final Hint hint;

        Summary(View view, List<String> planned, List<String> liveKeys, boolean settled, Hint hint) {
            this.view = view;
            this.planned = Collections.unmodifiableList(new ArrayList<>(planned));
            this.liveKeys = Collections.unmodifiableList(new ArrayList<>(liveKeys));
            this.settled = settled;
            this.hint = hint;
        }

        /** 和另一份说的是不是同一个样子（变了才告诉协调器）。 */
        public boolean sameAs(Summary other) {
            return other != null && view == other.view && settled == other.settled && planned.equals(other.planned)
                    && liveKeys.equals(other.liveKeys) && hint.sameAs(other.hint);
        }
    }

    private static final class Lane {
        State state = State.WAITING;
        long sinceMs;
        int token;
    }

    /** 这个录像意图的每一路，按开的次序。 */
    private final Map<String, Lane> lanes = new LinkedHashMap<>();
    /** 这个意图里出过「准备中」之外的样子：之后不再回到准备中。 */
    private boolean leftPreparing;
    /** 这个意图开录落定了。 */
    private boolean settled;

    // ================================================================= 规矩

    /**
     * 这一路现在加入录像：在等着、录制器停放着或备好了、相机在出画面、会话带着这一路的录像输出。
     * 「出画面」不等于「会话里有它」：会话配不上时会把录像输出丢掉，那样开的录一帧都收不到。
     */
    public static boolean joins(State state, Recorder recorder, boolean streaming, boolean carried) {
        return state == State.WAITING && recorder == Recorder.READY && streaming && carried;
    }

    /**
     * 一路的录制器报写不进（15 秒没写进新数据）：是不是相机的事。相机没在出画面、会话里没有它的录像输出、
     * 或者最近 {@link #FRESH_FRAMES_MS} 没出过画面，任何一项都算相机的事 —— 这一路自己退出，别的路照录；
     * 三样都好好的才是盘或编码器的事，整次停（存储一类，照旧自动恢复）。
     */
    public static boolean stallIsCamera(boolean streaming, boolean carried, boolean freshFrames) {
        return !(streaming && carried && freshFrames);
    }

    /**
     * 文件名在备好时就定了的录制器（MediaRecorder 模式，{@link VideoRecorder}）：这一路的相机此刻这个样子，备不备、
     * 备好了还没开录的留不留。只在相机在出画面、或者正在起来（排着要开、在开）时备、留着 —— 它多半马上就带着录像输出
     * 出了画面，文件名就是开录那一刻；被拿着、在救、停手、现在用不了相机（{@link Facts#cannotOpen}）、熄屏放开了
     * （{@link Facts#releasedByDark}：亮屏之前不会开，熄屏时备的那一个文件名早了几分钟到一整夜）的不备，
     * 备好还没开录的放掉，回来时按那一刻另备一个（channel-logic §三：新加入的那一路，文件名用它真实开始的时刻）。
     * 编码录制器第一个文件开录时才起名，不用这一条。
     */
    public static boolean keepsNamedRecorder(ChannelRules.Condition condition, boolean cannotOpen,
                                             boolean releasedByDark) {
        return !releasedByDark && (condition == ChannelRules.Condition.STREAMING
                || (condition == ChannelRules.Condition.BRINGING_UP && !cannotOpen));
    }

    /**
     * 共用的分段名字刚换了一个新的（有一路在切段）：这一路要不要现在就跟着切，拿同一个名字。
     *
     * <p>中途加入的一路，第一个文件按它真实开始的时刻起名，和别的路的分段错开；分段计时又各走各的，不跟着切的话
     * 它会一直错开 —— 名字本身没错，但同一时刻几路的文件不在一组。所以别的路一换新名字，它马上跟着切；
     * 它自己的分段计时照走，谁先到算谁。</p>
     *
     * @param offCycleName  这一路现在这个文件的名字不是共用的（加入时按那一刻起的）
     * @param recording     在录：分段切换、快速恢复的那一下不切，等下一次换名字
     * @param stopRequested 叫停了
     */
    static boolean alignsNow(boolean offCycleName, boolean recording, boolean stopRequested) {
        return offCycleName && recording && !stopRequested;
    }

    /**
     * 这一路在起来：在加入；或者在等着、录制器不是录不了，而相机在开的次序里排着、在开、或者已经出画面（录制器在备、
     * 会话还没带上它）。被拿着、在救、停手的不算 —— 它们一时半会儿回不来；关着而调度现在用不了相机的也不算
     * （{@link Facts#cannotOpen}：主界面回到前台之前它不会开，算它在起来的话「准备中」没有尽头）；熄屏放开的也不算
     * （{@link Facts#releasedByDark}：亮屏之前它不会开 —— 熄屏时开的录只等环视就落定）。
     */
    static boolean bringingUp(Facts lane) {
        if (lane.state == State.STARTING) {
            return true;
        }
        return lane.state == State.WAITING && lane.recorder != Recorder.FAILED && !lane.releasedByDark
                && ((lane.condition == ChannelRules.Condition.BRINGING_UP && !lane.cannotOpen)
                || lane.condition == ChannelRules.Condition.STREAMING);
    }

    /** 这一路不会自己按节奏重试了：停手了，或者录不了（{@link Recorder#FAILED}）。 */
    private static boolean unavailable(Facts lane) {
        return lane.state != State.LIVE
                && (lane.condition == ChannelRules.Condition.GAVE_UP || lane.recorder == Recorder.FAILED);
    }

    /**
     * 别的路在录时，这一路要说它无法录制（{@link Hint.Kind#LANE_UNAVAILABLE}）：不会自己按节奏重试了（{@link #unavailable}），
     * 或者它的一次关满 60 秒还没返回（{@link Facts#heldByClose}，项目所有者 2026-10-28：用现成的「无法录制，其他摄像头继续录制」）——
     * 那一次关返回之前调度不开它，不设时限。后一种不算「各路都停手」：关一返回它就照常开（{@link #allGaveUp}）。
     */
    private static boolean cannotRecord(Facts lane) {
        return unavailable(lane) || lane.state != State.LIVE && lane.heldByClose;
    }

    /**
     * 状态条上说哪一句。
     *
     * <ul>
     *   <li>至少一路在录：有一路停手了、录不了、或者它的一次关满 60 秒还没返回，说它无法录制（它不会因为别人放开就回来）；
     *       否则一路被拿着说它被占用、释放后自动恢复，两路以上说部分摄像头被占用；在救、在起来的不说，熄屏放开的不说
     *       （{@link Facts#releasedByDark}：车机熄屏后拿走后座舱，可它没在录是因为熄屏，亮屏它自己回来）。</li>
     *   <li>一路都没在录，而每一路都停手了或者录不了：{@link Hint.Kind#ALL_GAVE_UP}（只进黑匣子，见它的说明）。</li>
     * </ul>
     */
    public static Hint hint(List<Facts> lanes) {
        boolean anyLive = false;
        for (Facts lane : lanes) {
            anyLive |= lane.state == State.LIVE;
        }
        if (!anyLive) {
            return allGaveUp(lanes) ? new Hint(Hint.Kind.ALL_GAVE_UP, null) : Hint.NONE;
        }
        for (Facts lane : lanes) {
            if (cannotRecord(lane)) {
                return new Hint(Hint.Kind.LANE_UNAVAILABLE, lane.key);
            }
        }
        String taken = null;
        int takenCount = 0;
        for (Facts lane : lanes) {
            if (lane.state != State.LIVE && lane.condition == ChannelRules.Condition.TAKEN && !lane.releasedByDark) {
                takenCount++;
                if (taken == null) {
                    taken = lane.key;
                }
            }
        }
        if (takenCount >= 2) {
            return new Hint(Hint.Kind.LANES_TAKEN, null);
        }
        return takenCount == 1 ? new Hint(Hint.Kind.LANE_TAKEN, taken) : Hint.NONE;
    }

    /** 一路都没在录，而每一路都停手了、或者录不了：没有一路还会按节奏自己重试。没有路是假。 */
    public static boolean allGaveUp(List<Facts> lanes) {
        if (lanes.isEmpty()) {
            return false;
        }
        for (Facts lane : lanes) {
            if (!unavailable(lane)) {
                return false;
            }
        }
        return true;
    }

    // ================================================================= 表

    /** 开一个新的录像意图：计划里的每一路都从「等着」起，计数从 0 起；准备中、落定都从头算。 */
    void begin(Collection<String> planned, long nowMs) {
        lanes.clear();
        for (String key : planned) {
            Lane lane = new Lane();
            lane.sinceMs = nowMs;
            lanes.put(key, lane);
        }
        leftPreparing = false;
        settled = false;
    }

    /**
     * 这一路从这个录像意图的计划里拿掉（这次运行去掉的前座舱，暂时的规矩 FrontCabinOneShot）：表里删掉，带着它的任务回来时作废
     * （{@link #current} 为假）。别的路不动。
     */
    void drop(String key) {
        lanes.remove(key);
    }

    /** 录像意图关了（停录）：表清空。 */
    void clear() {
        lanes.clear();
        leftPreparing = false;
        settled = false;
    }

    /** 计划里的每一路，按开的次序（拷贝）。 */
    List<String> keys() {
        return new ArrayList<>(lanes.keySet());
    }

    /** @return 这一路的状态；null = 不在这个录像意图的计划里 */
    State state(String key) {
        Lane lane = lanes.get(key);
        return lane == null ? null : lane.state;
    }

    /** 这一路从什么时候起是眼下这个状态（调用方给的钟）；不在计划里是 0。 */
    long sinceMs(String key) {
        Lane lane = lanes.get(key);
        return lane == null ? 0 : lane.sinceMs;
    }

    /** 这一路眼下的计数：带着它的任务回来时对一下（{@link #current}）。不在计划里是 0。 */
    int token(String key) {
        Lane lane = lanes.get(key);
        return lane == null ? 0 : lane.token;
    }

    /** 带着 {@code token} 的任务还算不算数：这一路还在计划里，期间没退出过、没再加入过。 */
    boolean current(String key, int token) {
        Lane lane = lanes.get(key);
        return lane != null && lane.token == token;
    }

    /** 开始加入（{@link #joins} 为真时）。不在计划里的什么都不做，返回 0。 */
    int starting(String key, long nowMs) {
        return move(key, State.STARTING, nowMs);
    }

    /** 写进了第一笔：在录。计数不变 —— 加入的这一次接着就是它。 */
    void live(String key, long nowMs) {
        Lane lane = lanes.get(key);
        if (lane != null) {
            lane.state = State.LIVE;
            lane.sinceMs = nowMs;
        }
    }

    /** 退出（相机走了、写不进而且是相机的事、加入没成）：回到等着，之前排着的这一路的任务都作废。 */
    int waiting(String key, long nowMs) {
        return move(key, State.WAITING, nowMs);
    }

    private int move(String key, State state, long nowMs) {
        Lane lane = lanes.get(key);
        if (lane == null) {
            return 0;
        }
        lane.state = state;
        lane.sinceMs = nowMs;
        return ++lane.token;
    }

    /**
     * 此刻的样子：{@link View}、计划、在录的几路、落定没有、状态条那一句。准备中只在这个意图里还没出过别的样子时才有；
     * 落定只从假变真一次（之后有一路又在起来也还是落定的）。
     *
     * @param facts 计划里每一路的事实，按开的次序
     */
    Summary summarize(List<Facts> facts) {
        List<String> live = new ArrayList<>();
        boolean anyBringingUp = false;
        for (Facts lane : facts) {
            if (lane.state == State.LIVE) {
                live.add(lane.key);
            } else if (bringingUp(lane)) {
                anyBringingUp = true;
            }
        }
        View view;
        if (!live.isEmpty()) {
            view = View.RECORDING;
        } else if (anyBringingUp && !leftPreparing) {
            view = View.PREPARING;
        } else {
            view = View.WAITING;
        }
        if (view != View.PREPARING) {
            leftPreparing = true;
        }
        if (!settled && !facts.isEmpty() && !anyBringingUp) {
            settled = true;
        }
        return new Summary(view, keys(), live, settled, hint(facts));
    }
}
