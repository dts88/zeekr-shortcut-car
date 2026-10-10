package com.kooo.evcam.camera;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次录像里每一路自己的状态，和跟着它走的几条规矩（2026-10-10）。
 *
 * <h3>为什么</h3>
 *
 * <p>项目所有者 2026-10-10 定：一路摄像头被别的程序拿走（车主离车时车机拿走后座舱相机 1），只停这一路，
 * 别的几路照录、会话不重建；它放开了、通道安静了，单独重开、单独接回录像。以前录着的一路一被断开就整次停录、
 * 马上又整次开，环视和前座舱在相机服务交接的中途被重建两次；后座舱的录制器准备好了却再也没启动，
 * 这一次录像剩下的时间都没录它，它每被拿走一次，整次录像又停一次。</p>
 *
 * <h3>状态（主线程，只有 MultiCameraManager 改）</h3>
 *
 * <ul>
 *   <li>{@link State#LIVE} 在录；</li>
 *   <li>{@link State#LEAVING} 离开中：录制器在收拾，相机在关（被断开的那一次关在相机服务里要 5–8 秒）、
 *       在判是不是被别的程序拿走的；</li>
 *   <li>{@link State#OUT} 不在录，带原因（{@link Reason}）和从什么时候起；</li>
 *   <li>{@link State#JOINING} 接回中，带走到哪一步（{@link Step}）。</li>
 * </ul>
 *
 * <p>「这一次录像计划里的一路、录像开着、它不在录」就等着接回 —— 录着录着离开的、开录时没录起来的、
 * 开录时就被占着的，一条规矩（{@link #waitsToJoin}）。接回就是看门狗对这一路的那一下重开：{@link CameraLiveness}
 * 照常计次数、歇着、停手，没有另一套计时、另一份额度。</p>
 *
 * <p>每一路带一个计数（{@link #token}）：离开一次、开始接回一次都 +1。排着的任务（建录制器、开相机、录制器的回调）
 * 都带着它，回来对不上就作废 —— 同一次录像里一路可能离开、接回、又离开、又接回，录像的代数一直不变。</p>
 *
 * <p>纯 Java，时间由调用方传入，见 {@code RecordingLanesTest}。</p>
 */
public final class RecordingLanes {

    /** 一路此刻在不在录。 */
    public enum State {
        /** 在录。 */
        LIVE,
        /** 离开中：录制器在收拾、相机在关、在判是不是被别的程序拿走的。 */
        LEAVING,
        /** 不在录，等着接回（{@link Reason} 说为什么）。 */
        OUT,
        /** 接回中（{@link Step} 说走到哪一步）。 */
        JOINING
    }

    /** 为什么不在录。 */
    public enum Reason {
        /** 摄像头被别的程序拿走了（或者被它占着的另一路挡着），等它放开。 */
        TAKEN,
        /** 别的失败（相机出错、接回没成、开录时没录起来）：看门狗按次数接回，试够了停手。 */
        FAILED,
        /** 开录那一刻它就被别的程序拿着：这一路先不录，不建录制器、不等它的会话。 */
        START_SKIPPED
    }

    /** 接回走到哪一步。 */
    public enum Step {
        /** 在后台建录制器（编码器、EGL、SurfaceTexture；不开文件）。 */
        PREPARING,
        /** 录制器建好了，等通道安静了连着录像输出开相机。 */
        PREPARED,
        /** 相机在开、在配会话：等配好的会话里真有这一路的录像输出、出了画面。 */
        OPENING,
        /** 录制器启动了：写进第一笔数据才算接回。 */
        STARTED
    }

    /** 接回等会话、等画面这一步的一次检查（{@link #opening}）。 */
    enum Opening {
        /** 接着等。 */
        WAIT,
        /** 会话里有这一路的录像输出、出了画面：启动录制器。 */
        START,
        /** 相机不在途了还等不到：这一次接回没成。 */
        FAILED
    }

    /** 不在录的一路此刻的样子（给主界面、拍照、诊断）。 */
    public static final class Out {
        public final String key;
        public final State state;
        /**
         * 为什么不在录。从在录离开的、开录时没录起来的，离开中还没判出来，是 null；
         * 接回没成又离开的，留着它出来时的那个原因（判完再换成新的）。
         */
        public final Reason reason;
        /** 从什么时候起是这个样子（{@code SystemClock.elapsedRealtime}）。 */
        public final long sinceMs;

        Out(String key, State state, Reason reason, long sinceMs) {
            this.key = key;
            this.state = state;
            this.reason = reason;
            this.sinceMs = sinceMs;
        }

        /** 不在录是因为摄像头被别的程序占用（开录那一刻就被占着的也算）。 */
        public boolean taken() {
            return reason == Reason.TAKEN || reason == Reason.START_SKIPPED;
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
            /** 一路无法打开（出错、接回没成、试够了停手）：{@link Hint#key} 是那一路。 */
            LANE_UNAVAILABLE
        }

        static final Hint NONE = new Hint(Kind.NONE, null);

        public final Kind kind;
        /** 说的是哪一路；{@link Kind#NONE}、{@link Kind#LANES_TAKEN} 时是 null。 */
        public final String key;

        Hint(Kind kind, String key) {
            this.kind = kind;
            this.key = key;
        }
    }

    private static final class Lane {
        State state;
        Reason reason;
        Step step;
        long sinceMs;
        int token;
    }

    /** 这一次录像的每一路，按加进来的先后。 */
    private final Map<String, Lane> lanes = new LinkedHashMap<>();

    // ================================================================= 规矩

    /**
     * 这一路离开之后，录像里还有没有在录的路。没有了就是一次停录 —— 整次停、等环视恢复再接回，和以前一样：
     * 「一路都不在录的录像」就是停了的录像，不是另一条规矩。
     *
     * @param leaving 要离开的那一路
     * @param live    这一次录像的每一路此刻在不在录：状态是 {@link State#LIVE}，而且录制器开过录、没叫停
     *                （{@code CodecVideoRecorder.isLive}：分段切换、快速恢复的那一下也算在录，不能因此整次停）
     * @return true：它是最后一路在录的
     */
    static boolean lastLiveLane(String leaving, Map<String, Boolean> live) {
        for (Map.Entry<String, Boolean> lane : live.entrySet()) {
            if (!lane.getKey().equals(leaving) && Boolean.TRUE.equals(lane.getValue())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 这一路等不等着接回：录像开着、编码录制（MediaRecorder 模式没有单独接回，离开就整次停）、
     * 它在这一次录像的计划里、此刻不在录。离开中的等它收拾完；接回中的等这一次有结果。
     *
     * @param recording 录像开着（至少一路录制器启动了、还没停）
     * @param codec     编码录制
     * @param state     这一路在表上的状态；null = 不在这一次录像的计划里
     */
    static boolean waitsToJoin(boolean recording, boolean codec, State state) {
        return recording && codec && state == State.OUT;
    }

    /**
     * 接回等会话、等画面那一步（{@link Step#OPENING}）的一次检查。
     *
     * <p>录制器要等配好的会话里真有这一路的录像输出、而且出了画面才启动：「挂上了」不等于「会话里有」——
     * 会话配不上时会把录像输出丢掉重建，那样启动的录制器一帧都收不到，15 秒后被当成写不进。
     * 相机在开、在配会话时接着等；不在途了还等了 {@link CameraLiveness#STUCK_MS}（会话里没有它、或者有它却没画面），
     * 这一次接回就算没成 —— 和看门狗判「卡住」同一个数，下一次什么时候再试、试几次，还是看门狗定。</p>
     *
     * @param cameraBusy  相机此刻有动作在途（{@link SingleCamera#isBusy}）
     * @param carries     配好的会话里有这一路的录像输出（{@link SingleCamera#sessionCarries}）
     * @param freshFrames 刚出过画面
     * @param quietForMs  相机不在途有多久了（从挂上录像输出、或者最后一次看到它在途算起）
     */
    static Opening opening(boolean cameraBusy, boolean carries, boolean freshFrames, long quietForMs) {
        if (cameraBusy) {
            return Opening.WAIT;
        }
        if (carries && freshFrames) {
            return Opening.START;
        }
        return quietForMs >= CameraLiveness.STUCK_MS ? Opening.FAILED : Opening.WAIT;
    }

    /**
     * 共用的分段时间戳刚换了一个新的（有一路在切段）：这一路要不要现在就跟着切，拿同一个名字。
     *
     * <p>单独接回的一路，第一个文件按开录那一刻命名，和别的路的分段错开；分段计时又各走各的，不跟着切的话
     * 它会一直错开 —— 名字本身没错（就是它真开始的时刻），但同一时刻几路的文件不在一组。所以别的路一换新名字，
     * 它马上跟着切；它自己的分段计时照走，谁先到算谁。环视单独接回也一样。</p>
     *
     * @param offCycleName  这一路现在这个文件的名字不是共用的（单独接回时按那一刻起的）
     * @param recording     在录：分段切换、快速恢复的那一下不切，等下一次换名字
     * @param stopRequested 叫停了
     */
    static boolean alignsNow(boolean offCycleName, boolean recording, boolean stopRequested) {
        return offCycleName && recording && !stopRequested;
    }

    /**
     * 录着的时候主界面状态条上说哪一句。
     *
     * <p>从在录离开、还没判出来的不说：相机还在关，是不是被别的程序拿走还没判出来，先说「无法打开」再改口「被占用」
     * 不如晚几秒说对。被占用的（开录那一刻就被占着的也算）说「被占用，释放后该路自动恢复录制」；接回中的、
     * 接回没成又离开的，照它出来时的原因说（被占着时每 30 秒试的那一下不至于让这一句一闪一闪）。
     * 有一路不是被占用（出错、接回没成、试够了停手）就说那一路「无法打开」—— 那一路不会因为别人放开就回来。
     * 两路以上都被占用说「部分摄像头被占用」。</p>
     */
    public static Hint hint(List<Out> out) {
        String taken = null;
        int takenCount = 0;
        for (Out lane : out) {
            if (lane.reason == null) {
                continue;
            }
            if (!lane.taken()) {
                return new Hint(Hint.Kind.LANE_UNAVAILABLE, lane.key);
            }
            takenCount++;
            if (taken == null) {
                taken = lane.key;
            }
        }
        if (takenCount >= 2) {
            return new Hint(Hint.Kind.LANES_TAKEN, null);
        }
        return takenCount == 1 ? new Hint(Hint.Kind.LANE_TAKEN, taken) : Hint.NONE;
    }

    // ================================================================= 表

    /** 新的一次录像、停录：表清空。 */
    void clear() {
        lanes.clear();
    }

    /** 表上的每一路（拷贝：走的时候可以改表）。 */
    List<String> keys() {
        return new ArrayList<>(lanes.keySet());
    }

    /** @return 这一路的状态；null = 不在表上（不在这一次录像的计划里，或者开录还没走到这一路） */
    State state(String key) {
        Lane lane = lanes.get(key);
        return lane == null ? null : lane.state;
    }

    /** @return 接回走到哪一步；不在接回中是 null */
    Step step(String key) {
        Lane lane = lanes.get(key);
        return lane == null || lane.state != State.JOINING ? null : lane.step;
    }

    /** 这一路从什么时候起是眼下这个状态（调用方给的钟）；不在表上是 0。 */
    long sinceMs(String key) {
        Lane lane = lanes.get(key);
        return lane == null ? 0 : lane.sinceMs;
    }

    /** 这一路眼下的计数：带着它的任务回来时对一下（{@link #current}）。不在表上的是 0（开录建的录制器带的就是它）。 */
    int token(String key) {
        Lane lane = lanes.get(key);
        return lane == null ? 0 : lane.token;
    }

    /** 带着 {@code token} 的任务还算不算数：这一路还在表上，期间没离开过、没开始过接回。 */
    boolean current(String key, int token) {
        Lane lane = lanes.get(key);
        return lane != null && lane.token == token;
    }

    /** 在录：开录时启动了的一路，或者接回写进了第一笔数据的一路。计数不变 —— 接回那一个录制器接着就是它的。 */
    void live(String key, long nowMs) {
        Lane lane = laneOf(key);
        lane.state = State.LIVE;
        lane.reason = null;
        lane.step = null;
        lane.sinceMs = nowMs;
    }

    /** 不在录，带原因。 */
    void out(String key, Reason reason, long nowMs) {
        Lane lane = laneOf(key);
        lane.state = State.OUT;
        lane.reason = reason;
        lane.step = null;
        lane.sinceMs = nowMs;
    }

    /**
     * 离开：在录的、接回中的、开录时没录起来的。录制器去收拾，相机去关；之前排着的这一路的任务都作废。
     * 接回中离开的（这一次接回没成）留着它出来时的原因，别的离开时还不知道原因（{@link Out#reason}）。
     *
     * @return 这一次离开的计数
     */
    int leave(String key, long nowMs) {
        Lane lane = laneOf(key);
        if (lane.state != State.JOINING) {
            lane.reason = null;
        }
        lane.state = State.LEAVING;
        lane.step = null;
        lane.sinceMs = nowMs;
        return ++lane.token;
    }

    /**
     * 开始接回（看门狗对这一路的那一下重开）。原因留着：接回中的照它出来的原因说（{@link #hint}）。
     *
     * @return 这一次接回的计数
     */
    int join(String key, long nowMs) {
        Lane lane = laneOf(key);
        lane.state = State.JOINING;
        lane.step = Step.PREPARING;
        lane.sinceMs = nowMs;
        return ++lane.token;
    }

    /** 接回走到下一步（还是这一次接回，计数不变）。 */
    void step(String key, Step step) {
        Lane lane = lanes.get(key);
        if (lane != null && lane.state == State.JOINING) {
            lane.step = step;
        }
    }

    /**
     * 正在变的那一路：离开中，或者接回中还没启动录制器（建录制器、等通道安静、开相机、等画面）。
     * 一次只动一路 —— 它这一步走完之前，看门狗别的路都不动。没有是 null。
     */
    String changing() {
        for (Map.Entry<String, Lane> entry : lanes.entrySet()) {
            Lane lane = entry.getValue();
            if (lane.state == State.LEAVING || (lane.state == State.JOINING && lane.step != Step.STARTED)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 这一路不在录的样子；在录、不在表上是 null。 */
    Out outOf(String key) {
        Lane lane = lanes.get(key);
        if (lane == null || lane.state == State.LIVE) {
            return null;
        }
        return new Out(key, lane.state, lane.reason, lane.sinceMs);
    }

    private Lane laneOf(String key) {
        Lane lane = lanes.get(key);
        if (lane == null) {
            lane = new Lane();
            lanes.put(key, lane);
        }
        return lane;
    }
}
