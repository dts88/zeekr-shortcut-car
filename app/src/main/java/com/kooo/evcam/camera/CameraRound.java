package com.kooo.evcam.camera;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮：一份相机对象（{@link SingleCamera}）为一台相机走的一次设备生命周期（第 1 步「轮次 + 账本」，项目所有者 2026-10-29 批）。
 * 从调度下达开起，到它唯一的一次<b>结束</b>为止；一轮里只有那一次开。救援的重开是两轮：调度先下达关（这一轮结束），再下达开（新的一轮，
 * 第 2b 步）。进程里还没结束的每一轮记在账本上（{@link CameraRounds}）：一台相机有一轮还没结束，谁都不开它。纯 Java，见 {@code CameraRoundTest}。
 *
 * <h3>什么时候结束</h3>
 *
 * <p>下面三样都成立的那一下（{@link #takeEnd}；在这一轮的相机线程上判，判和改都在 SingleCamera 的那把锁里，只结束一次）：</p>
 * <ul>
 *   <li>那一次 {@code openCamera} 没在等回调：{@code openCamera} 正常返回之后、它的 onOpened / onError / onDisconnected
 *       到之前算在等。一轮一次 {@code openCamera}、一个 StateCallback（{@link Call}，框架自己认的那一份身份）：上一轮晚到的回调
 *       落在上一轮的账上，认不成这一轮的。开之前就被关了、{@code openCamera} 抛了的，没有在等的；</li>
 *   <li>这一轮拿到过的每一个设备对象都关完了（{@code close()} 返回了）—— 关了之后才开好的那一台也算：关掉它，这一轮才结束；</li>
 *   <li>没有一次关在做（关相机那一下排着、在做），也没有一次开还没进 {@code openCamera}（开之前查列表、查参数）。</li>
 * </ul>
 *
 * <p>一次关卡在相机服务里、一次 {@code openCamera} 卡在相机服务里 ⇒ 这一轮永远不结束 ⇒ 这一台到进程结束都算占着。
 * 这是规矩，不是毛病（项目所有者 2026-10-28：关没返回就不开；10-29 Q1：开没返回同一条）。谁都不等谁：这里只记账，
 * 相机层照答案做完自己那一下就走。</p>
 *
 * <h3>账上的样子（{@link Phase}、{@link #outstandingSince}）</h3>
 *
 * <p>在丢（丢了之后那一次关在做）、在关（下达了关，或者手上有一台不挂着的设备在关：晚到的就地关、开不起来的那一台）、
 * 在开（那一次开还没进 {@code openCamera}、{@code openCamera} 还没回音）、开着（挂着一台设备，别的都没有）。
 * 不是开着就是有开或关没返回：从什么时候起一直这样（{@link #outstandingSince}），接连不断就不从头算 —— 卡住的关上面又来一次关、
 * 被关掉的开在等回调，都还是同一段；稳稳开着才归 0。别的路等多久、黑匣子「还没结束」那一行、状态条那一句都照它算。</p>
 *
 * <p>线程：改的方法都由 SingleCamera 在它的设备锁里调（相机线程，关相机那一下在主线程）；读的方法（{@link #status} 那几个）
 * 哪个线程都能调 —— 每个方法都锁这一份自己。</p>
 */
public final class CameraRound {

    /** 账上的样子。 */
    public enum Phase {
        /** 在开：那一次开还没进 {@code openCamera}，或者 {@code openCamera} 还没回音。 */
        OPENING,
        /** 开着：挂着一台设备，没有别的在途。 */
        OPEN,
        /** 在关：下达了关；或者手上有一台不挂着的设备在关（晚到的就地关、开不起来的那一台）。 */
        CLOSING,
        /** 在丢：挂着的那一台丢了（被断开、报错），这一次关还没返回。 */
        LOSING
    }

    /** 这一轮那一次 {@code openCamera} 的回调来了，相机层照它做什么（{@link #opened}、{@link #failed}）。 */
    public enum Action {
        /** 那一次 {@code openCamera} 开好了，这一轮没下达关：设备挂上，接着建会话。 */
        ADOPT,
        /** 那一次 {@code openCamera} 还没开好就报错、被断开，这一轮没下达关：这个设备对象关掉，报开不起来。 */
        OPEN_FAILED,
        /** 挂着的那一台丢了（被断开、报错）：先报、再关。 */
        LOST,
        /** 这一轮下达了关之后那一次 {@code openCamera} 才回音：设备晚到，就地关掉，关完这一轮才可能结束。 */
        CLOSE_LATE,
        /** 这一台的关已经有人在做（关相机那一下、丢了之后的关、晚到的就地关）：什么都不做。 */
        IGNORE,
        /**
         * 不归这一轮的账：{@code openCamera} 抛了的那一次的回调（框架报错时可能照样 post 一声），或者早关完了的设备又来一声。
         * 相机层关一下（关过的、没连上的，{@code close()} 都不进相机服务那一侧的连接），不记账。
         */
        STRAY
    }

    /** 这一轮那一次 {@code openCamera}：一个 StateCallback（相机层的回调拿着它，凭它认是哪一轮的）。 */
    public static final class Call {
        enum State {
            /** 在调 {@code openCamera}（还没返回）。 */
            CALLING,
            /** {@code openCamera} 正常返回了，这一次的回调还没来。 */
            PENDING,
            /** {@code openCamera} 抛了：这一次没有在等的。 */
            THREW,
            /** 这一次的第一声回调来了。 */
            ANSWERED
        }

        State state = State.CALLING;

        boolean outstanding() {
            return state == State.CALLING || state == State.PENDING;
        }
    }

    /** 一轮此刻的样子（{@link #status}，不可变）：调度、诊断报告一次读齐。 */
    public static final class Status {
        public final Phase phase;
        /** 从什么时候起一直有开或关没返回（{@link CameraRound#outstandingSince}）；0 = 稳稳开着。 */
        public final long outstandingSince;
        /** 那一次 {@code openCamera} 还没返回（卡在相机服务里的就是这样）。 */
        public final boolean inOpenCamera;
        /** 那一次 {@code openCamera} 返回了、还在等回调。 */
        public final boolean openPending;
        /** 这一轮下达了关。 */
        public final boolean cancelled;

        Status(Phase phase, long outstandingSince, boolean inOpenCamera, boolean openPending, boolean cancelled) {
            this.phase = phase;
            this.outstandingSince = outstandingSince;
            this.inOpenCamera = inOpenCamera;
            this.openPending = openPending;
            this.cancelled = cancelled;
        }

        /** 在关、在丢：有一次关没返回。 */
        public boolean closing() {
            return phase == Phase.CLOSING || phase == Phase.LOSING;
        }

        /** 日志、黑匣子用的记号（英文，不是给人读的句子）：「opening openCamera」「closing open-pending」「closing-lost」。 */
        public String kinds() {
            StringBuilder sb = new StringBuilder();
            switch (phase) {
                case OPENING:
                    sb.append("opening");
                    break;
                case CLOSING:
                    sb.append("closing");
                    break;
                case LOSING:
                    sb.append("closing-lost");
                    break;
                default:
                    break;
            }
            if (phase != Phase.OPEN) {
                if (inOpenCamera) {
                    sb.append(" openCamera");
                } else if (openPending) {
                    sb.append(" open-pending");
                }
            }
            return sb.toString();
        }
    }

    /** 账本给的轮次号（进程里唯一、只增）。 */
    public final int number;
    public final String cameraId;
    /** 下达它的那一份相机对象（SingleCamera；测试里是假的那一份）：账本、相机层凭它认「是不是这一份的那一轮」。 */
    final Object owner;
    /** 那一份是哪一路（{@link CameraSlots} 的 key：front / left / back …）；诊断报告照它写。 */
    public final String ownerKey;
    /** 下达的时刻（调度的钟）。 */
    public final long since;

    /** 下达的那一次开还没进 {@code openCamera}（开之前查列表、查参数）：一轮里只有这一次开。 */
    private boolean beforeCall = true;
    /** 这一轮那一次 {@code openCamera}；还没调过（或者没进去）是 null。 */
    private Call current;
    /** 交到这一轮手上、还没关完的设备对象（按对象认）。 */
    private final List<Object> devices = new ArrayList<>();
    /** 挂在相机对象上的那一台（开好了、没丢、没摘下）；没有是 null。 */
    private Object adopted;
    /** 丢了、在关的那一台；没有是 null。 */
    private Object losing;
    /** 关相机那一下：下达了、还没做完的几次。 */
    private int jobs;
    private boolean cancelled;
    /**
     * 在丢的那一台是被踢的（被断开、报错 1 / 2）：相机服务那边已经把我们踢了，那几秒里它报的「被占用」是新的主人（{@link #holdsInService}）。
     * 跟着丢了那一下记（{@link #failed}），那一次关返回就清（{@link #closed}）。以前是一份相机对象自己的标记（SingleCamera.kickedClose）：
     * 管线重建时另一份相机对象问不到这一轮被踢（第 1 步审查留给第 2 步，2026-10-29）。
     */
    private boolean kicked;
    /** 这一轮丢过（{@link #closedOut}）。 */
    private boolean lost;
    private long outstandingSince;
    private boolean ended;

    CameraRound(int number, String cameraId, Object owner, String ownerKey, long now) {
        this.number = number;
        this.cameraId = cameraId;
        this.owner = owner;
        this.ownerKey = ownerKey;
        this.since = now;
        this.outstandingSince = now;
    }

    // ================================================================= 开

    /**
     * 相机线程：要进 {@code openCamera} 了 —— 这一次开从「还没进」变成「在调」。这一轮下达了关（或者已经结束）返回 null：
     * 不进 {@code openCamera}，这一次开就当没开（接着 {@link #takeEnd}）。
     */
    synchronized Call beginCall(long now) {
        beforeCall = false;
        if (cancelled || ended) {
            touch(now);
            return null;
        }
        current = new Call();
        touch(now);
        return current;
    }

    /** 相机线程：这一次开没进 {@code openCamera}（不在列表里、查参数抛了、开之前就被关了）。接着 {@link #takeEnd}。 */
    synchronized void noCall(long now) {
        beforeCall = false;
        touch(now);
    }

    /** 相机线程：{@code openCamera} 正常返回了 —— 这一次在等回调。 */
    synchronized void returned(Call call, long now) {
        if (call.state == Call.State.CALLING) {
            call.state = Call.State.PENDING;
        }
        touch(now);
    }

    /** 相机线程：{@code openCamera} 抛了 —— 这一次没有在等的。接着 {@link #takeEnd}。 */
    synchronized void threw(Call call, long now) {
        if (call.state == Call.State.CALLING) {
            call.state = Call.State.THREW;
        }
        touch(now);
    }

    /** 相机线程：{@code call} 那一次的 onOpened。 */
    synchronized Action opened(Call call, Object device, long now) {
        Action action;
        if (ended || call.state == Call.State.THREW) {
            action = Action.STRAY;
        } else if (call.outstanding()) {
            call.state = Call.State.ANSWERED;
            received(device);
            if (!cancelled) {
                adopted = device;
                action = Action.ADOPT;
            } else {
                action = Action.CLOSE_LATE;
            }
        } else {
            action = holds(device) ? Action.IGNORE : Action.STRAY;
        }
        touch(now);
        return action;
    }

    /**
     * 相机线程：{@code call} 那一次的 onError / onDisconnected。
     *
     * @param kicked 被断开、报错 1 / 2（多半是别的程序拿走了）：挂着的那一台丢了的话，那一次关返回之前相机服务那边已经不算我们的
     *               （{@link #holdsInService}）；设备错误 3 / 4 / 5 不算 —— 我们的连接还在
     */
    synchronized Action failed(Call call, Object device, boolean kicked, long now) {
        Action action;
        if (ended || call.state == Call.State.THREW) {
            action = Action.STRAY;
        } else if (call.outstanding()) {
            call.state = Call.State.ANSWERED;
            received(device);
            action = cancelled ? Action.CLOSE_LATE : Action.OPEN_FAILED;
        } else if (device != null && device == adopted) {
            adopted = null;
            losing = device;
            this.kicked = kicked;
            lost = true;
            action = Action.LOST;
        } else {
            action = holds(device) ? Action.IGNORE : Action.STRAY;
        }
        touch(now);
        return action;
    }

    // ================================================================= 关

    /** 这一台设备关完了（{@code close()} 返回了）：不论是哪一种关。接着 {@link #takeEnd}。 */
    synchronized void closed(Object device, long now) {
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i) == device) {
                devices.remove(i);
                break;
            }
        }
        if (adopted == device) {
            adopted = null;
        }
        if (losing == device) {
            losing = null;
            kicked = false;
        }
        touch(now);
    }

    /**
     * 主线程：下达了关。从此不再开新的（还没进 {@code openCamera} 的那一下不进），晚到的设备就地关；挂着的那一台交给关相机那一下去关
     * （关完才算）。这一轮已经结束了返回 false（相机层那边当它没开着）。
     */
    synchronized boolean closeRequested(long now) {
        if (ended) {
            return false;
        }
        cancelled = true;
        jobs++;
        adopted = null;
        touch(now);
        return true;
    }

    /** 关相机那一下做完了（它手上的设备另由 {@link #closed} 记）。接着 {@link #takeEnd}。 */
    synchronized void closeDone(long now) {
        if (jobs > 0) {
            jobs--;
        }
        touch(now);
    }

    // ================================================================= 结束

    /**
     * 这一轮现在结束吗：没有在等回调的 {@code openCamera}、没有还没进 {@code openCamera} 的开、每一台设备都关完了、没有关在做。
     * 第一次成立时返回 true，之后都是 false（结束只有一次）。每一次改完都问一次。
     */
    synchronized boolean takeEnd() {
        if (ended || beforeCall || current != null && current.outstanding() || !devices.isEmpty() || jobs > 0) {
            return false;
        }
        ended = true;
        return true;
    }

    // ================================================================= 读（哪个线程都行）

    /** 结束了（{@link #takeEnd} 答过 true）。 */
    public synchronized boolean ended() {
        return ended;
    }

    /** 下达了关。 */
    public synchronized boolean cancelled() {
        return cancelled;
    }

    /**
     * 相机服务那边此刻算不算我们占着这一台：除了被踢之后那一次关在做（{@link #kicked}，那一下返回就清），这一轮在开、开着、在关都算 ——
     * 从字段上摘下来到相机服务真的放开之间，占着它的仍然是我们。账本上这一台的那一轮谁的都一样问它（相机服务那几声认人，
     * CameraAvailabilityWatch）：管线重建时另一份相机对象被踢、正在关，也照这一轮答（第 1 步审查留给第 2 步）。
     */
    public synchronized boolean holdsInService() {
        return losing == null || !kicked;
    }

    /** 这一轮此刻挂着一台设备（开好了、没丢、没摘下：SingleCamera 那一份的设备字段就是它）。 */
    public synchronized boolean holdsDevice() {
        return !ended && adopted != null;
    }

    /**
     * 这一轮是一次关做完才结束的（下达过关，或者丢过 —— 丢了之后那一次关也是我们的关）：账本据此记这一台我们最近一次关完的时刻
     * （{@link CameraRounds#closedAt}）。开不起来、没下达关就结束的那种不算。
     */
    public synchronized boolean closedOut() {
        return cancelled || lost;
    }

    public synchronized Phase phase() {
        return phaseNow();
    }

    /** 从什么时候起一直有开或关没返回（调度的钟）；0 = 稳稳开着。 */
    public synchronized long outstandingSince() {
        return outstandingSince;
    }

    /** 此刻的样子，一次读齐。 */
    public synchronized Status status() {
        return new Status(phaseNow(), outstandingSince, current != null && current.state == Call.State.CALLING,
                current != null && current.state == Call.State.PENDING, cancelled);
    }

    // ================================================================= 小工具

    private Phase phaseNow() {
        if (losing != null) {
            return Phase.LOSING;
        }
        if (cancelled || holdsOtherThan(adopted)) {
            return Phase.CLOSING;
        }
        if (beforeCall || current != null && current.outstanding()) {
            return Phase.OPENING;
        }
        if (adopted != null) {
            return Phase.OPEN;
        }
        // 什么都不剩了：这一轮马上结束（结束了的也是这样）
        return Phase.CLOSING;
    }

    /** 每一次改完：稳稳开着就归 0；从开着变成别的那一下记下时刻，接连不断就不动它。 */
    private void touch(long now) {
        if (ended) {
            return;
        }
        if (phaseNow() == Phase.OPEN) {
            outstandingSince = 0;
        } else if (outstandingSince == 0) {
            outstandingSince = now;
        }
    }

    private void received(Object device) {
        if (device != null && !holds(device)) {
            devices.add(device);
        }
    }

    private boolean holds(Object device) {
        for (Object d : devices) {
            if (d == device) {
                return true;
            }
        }
        return false;
    }

    private boolean holdsOtherThan(Object device) {
        for (Object d : devices) {
            if (d != device) {
                return true;
            }
        }
        return false;
    }

    /** 日志用：「round 12 camera 1 left CLOSING」。 */
    @Override
    public String toString() {
        return "round " + number + " camera " + cameraId + " " + ownerKey + " " + phase();
    }
}
