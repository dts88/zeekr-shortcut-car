package com.kooo.evcam.camera;

/**
 * 一个编码录制器（{@link CodecVideoRecorder}）能在哪些状态之间走、每走一步之前看哪道闸（2026-10-11）。
 *
 * <h3>为什么</h3>
 *
 * <p>项目所有者 2026-10-10 定：录像每一路各自进出 —— 一路的相机走了，这一路自己收尾退出，别的路照录；
 * 相机回来了，它再加入。为了加入时不用为它改会话，退出的那一路<b>不放录制器，而是停放</b>：编码器放掉（不占硬件编码器），
 * EGL 和输入 SurfaceTexture 留着、照常取帧，它的录像输出就一直活着、留在会话里（减输出能拖就拖，同日确认的第 4 条），
 * 相机回来后的第一份会话就带着它；加入时再重新备好一个编码器开录。停录之后也一样停放着，下一次开录直接沿用。</p>
 *
 * <p>于是一个录制器不再是「备好 → 录 → 停 → 放」一条直线走到底，而是「停 → 停放 → 重新备好 → 再录」能来回走好几趟。
 * 以前开录的闸是几个散着的标志（编码器在不在、在不在录、叫停过没有），其中「叫停过的不再开」在来回走以后就不成立了；
 * 停放、重新备好又各要花几秒（在后台线程上），这中间再开录、再停放、再备好一次，都会两边一起动同一个编码器。
 * 所以把能走的几步写成这一张表：录制器每做一步之前都先在这里走一次，走得成才做，走不成就不做。</p>
 *
 * <h3>几步</h3>
 *
 * <pre>
 *   从                     走哪一步        到
 *   NEW                    prepared       PREPARED
 *   PREPARED               start          RECORDING
 *   PREPARED / RECORDING   beginStop      STOPPING
 *   STOPPING               stopped        STOPPED
 *   STOPPED                beginPark      PARKING
 *   PARKING                parked         PARKED
 *   PARKED                 beginRearm     REARMING
 *   REARMING               rearmed        PREPARED（建不起来：rearmFailed → PARKED）
 *   除了 RELEASED 的哪一步   release        RELEASED
 * </pre>
 *
 * <ul>
 *   <li>新备好的（重新备好的也一样）能开；开着的不能再开。</li>
 *   <li>正在停的不能开，也不能停放、重新备好。</li>
 *   <li>停完的能停放；停放着的能重新备好。</li>
 *   <li>写入线程被放弃过的（盘卡死了，见 {@code CodecVideoRecorder.abandonWriter}）不能重新备好，也不能开 ——
 *       新文件只会排给那条卡住的线程。它只能停放着，等它的录像输出被丢了再放掉；这一路要录就换一个新录制器。</li>
 *   <li>放掉了的什么都不能做。</li>
 * </ul>
 *
 * <p>备好了、还没开过录的，也照「停 → 停放」这一条路走（没有文件要收，一下就停完）：停录时一路还没加入，
 * 它的编码器照样放掉，不另开一条「直接停放」的路。</p>
 *
 * <p>停放、重新备好中间各有一个「正在」的状态，理由和「正在停」一样：那几秒里什么都不许插进来。
 * 做完时再走一次（{@link #parked}、{@link #rearmed}、{@link #stopped}）；走不成就是中途被放掉了，
 * 自己刚建的东西自己收（{@link #release} 只管它接手那一刻已经在的）。</p>
 *
 * <h3>线程</h3>
 *
 * <p>录制器的几步在不同线程上：建、停放、重新备好在后台线程，开录在主线程，停录在收尾的线程，放掉在哪都可能。
 * 所以每一次转移都是「看一眼、走得成就当场走」，在同一把锁里一下做完：两条线程同时想走同一步，只有一条走得成。
 * 表本身（{@code can*}）是静态的纯函数，给只想问一句的地方用。</p>
 *
 * <p>纯 Java，见 {@code RecorderRunTest}。</p>
 */
public final class RecorderRun {

    /** 录制器此刻走到哪一步。 */
    public enum Phase {
        /** 建了，还没备好（编码器、EGL、输入 SurfaceTexture 在建）。 */
        NEW,
        /** 备好了，没开录：新备好的、重新备好的都是它。 */
        PREPARED,
        /** 开着：开录了、没叫停（分段切换、编码器重建的那一下也算开着）。 */
        RECORDING,
        /** 正在停：叫停了，文件还在收。 */
        STOPPING,
        /** 停完了：文件收好了，编码器还没放。 */
        STOPPED,
        /** 正在停放：在放编码器和它的输入 Surface。 */
        PARKING,
        /** 停放着：编码器放掉了；EGL 和输入 SurfaceTexture 还在，照常取帧，录像输出还活着。 */
        PARKED,
        /** 正在重新备好：在建新的编码器。 */
        REARMING,
        /** 放掉了。 */
        RELEASED
    }

    private Phase phase = Phase.NEW;
    private boolean writerAbandoned;

    // ================================================================= 表

    /** 能不能开录：备好了，而且写入线程没被放弃过。 */
    public static boolean canStart(Phase phase, boolean writerAbandoned) {
        return phase == Phase.PREPARED && !writerAbandoned;
    }

    /** 能不能叫停：开着的，或者备好了还没开过录的。 */
    public static boolean canStop(Phase phase) {
        return phase == Phase.PREPARED || phase == Phase.RECORDING;
    }

    /** 能不能停放：只有停完了的。 */
    public static boolean canPark(Phase phase) {
        return phase == Phase.STOPPED;
    }

    /** 能不能重新备好：停放着的，而且写入线程没被放弃过。 */
    public static boolean canRearm(Phase phase, boolean writerAbandoned) {
        return phase == Phase.PARKED && !writerAbandoned;
    }

    /** 能不能放掉：除了已经放掉的，哪一步都能（正在做的那一步做完时自己发现，见类说明）。 */
    public static boolean canRelease(Phase phase) {
        return phase != Phase.RELEASED;
    }

    // ================================================================= 转移

    /** 建好了（编码器、EGL、输入 SurfaceTexture 都在）。只从 {@link Phase#NEW} 走。建不起来的直接 {@link #release}。 */
    public synchronized boolean prepared() {
        return move(Phase.NEW, Phase.PREPARED);
    }

    /** 开录（{@link #canStart}）。走不成就不开。 */
    public synchronized boolean start() {
        if (!canStart(phase, writerAbandoned)) {
            return false;
        }
        phase = Phase.RECORDING;
        return true;
    }

    /** 叫停（{@link #canStop}）。已经在停、停完了的走不成：叫停只做一次。 */
    public synchronized boolean beginStop() {
        if (!canStop(phase)) {
            return false;
        }
        phase = Phase.STOPPING;
        return true;
    }

    /** 文件收好了（到点没收好、放弃了写入线程的也算停完，先 {@link #noteWriterAbandoned}）。 */
    public synchronized boolean stopped() {
        return move(Phase.STOPPING, Phase.STOPPED);
    }

    /** 开始停放（{@link #canPark}）。 */
    public synchronized boolean beginPark() {
        if (!canPark(phase)) {
            return false;
        }
        phase = Phase.PARKING;
        return true;
    }

    /** 编码器放掉了，停放好了。走不成就是中途被放掉了。 */
    public synchronized boolean parked() {
        return move(Phase.PARKING, Phase.PARKED);
    }

    /** 开始重新备好（{@link #canRearm}）。 */
    public synchronized boolean beginRearm() {
        if (!canRearm(phase, writerAbandoned)) {
            return false;
        }
        phase = Phase.REARMING;
        return true;
    }

    /** 新的编码器建好了，又能开录。走不成就是中途被放掉了：刚建的编码器由调用方自己放。 */
    public synchronized boolean rearmed() {
        return move(Phase.REARMING, Phase.PREPARED);
    }

    /**
     * 新的编码器没建起来：还是停放着（EGL 和输入 SurfaceTexture 照常取帧，录像输出还活着）。
     * 这一路要录就换一个新录制器，这一个等它的录像输出被丢了再放。
     */
    public synchronized boolean rearmFailed() {
        return move(Phase.REARMING, Phase.PARKED);
    }

    /**
     * 放掉。返回放掉之前在哪一步，放掉的那一方按它收拾（开着的要先收文件、正在停放的那一步做完时会自己发现）；
     * 返回 {@link Phase#RELEASED} 就是已经放过了，什么都不用做。
     */
    public synchronized Phase release() {
        Phase was = phase;
        phase = Phase.RELEASED;
        return was;
    }

    /** 写入线程被放弃了（到点没做完，多半卡在一个没了的盘上）：从此不能再开录、不能再重新备好。 */
    public synchronized void noteWriterAbandoned() {
        writerAbandoned = true;
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized boolean writerAbandoned() {
        return writerAbandoned;
    }

    /** 诊断用：如 {@code PARKED} 或 {@code PARKED, writer abandoned}。 */
    @Override
    public synchronized String toString() {
        return writerAbandoned ? phase.name() + ", writer abandoned" : phase.name();
    }

    private boolean move(Phase from, Phase to) {
        if (phase != from) {
            return false;
        }
        phase = to;
        return true;
    }
}
