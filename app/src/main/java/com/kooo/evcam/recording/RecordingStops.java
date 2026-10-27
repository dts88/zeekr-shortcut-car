package com.kooo.evcam.recording;

/**
 * 录像为什么停了，以及停了之后要不要自动接回去。
 *
 * <h3>定义（2026-09-26 项目所有者，规格 §2.3；2026-10-11 大纲 §7 第 3 问）</h3>
 *
 * <p>录像被非人为原因打断后自动接回：收拾完、查过盘就重开，不等环视，也不等主界面显示。
 * 打断要记进日志、告诉用户是被什么打断的；在自动恢复的话也要说一声。</p>
 *
 * <p>摄像头被别的程序拿走、断开、卡住都不是停（2.11，确认文档 §三）：那一路自己收尾退出、回来自己加入，
 * 一路都录不了时录像也不停，显示「等待摄像头」。整次停只有人停、熄屏、存储一类（写不进、没写进、管线被换、
 * 录制器都建不起来）、盘满和退出。</p>
 *
 * <p>纯逻辑，见 {@code RecordingStopsTest}。</p>
 */
public final class RecordingStops {

    /** 录像停下来的原因。 */
    public enum Reason {
        /** 人停的：点了停止、点了退出、远程让停。 */
        USER,
        /**
         * 因熄屏停的（{@link ScreenOffPlan}）：车机要睡（屏幕黑着时安卓熄屏，哨兵模式没开、车没在走）时现在停（车机几秒后就断电，
         * 2026-10-10）；熄屏持续录制、开发者熄屏录制都关着（{@link ScreenOffPlan#keepsRecording}），或者计划里没有环视，
         * 屏幕一黑 10 秒后停（车机还醒着，熄屏 v3）；黑满 30 秒以后通道放开了环视（哨兵模式没开、熄屏中哨兵关了、车机要睡）时现在停
         * （项目所有者 2026-10-27）。亮屏后接不接，停的那一刻照表定好了。
         */
        SCREEN_OFF,
        /** U 盘满了。存储那边会自己从最老的清，走到这一步说明清不动。 */
        STORAGE_FULL,
        /** U 盘满了而且清不出空间：删光本应用的旧录像也不够（盘被别的东西占了）。 */
        STORAGE_CANNOT_FREE,
        /** 锁定的影像占满了空间：没锁的删光也不够，解锁一些就能接着录（FootageLocks）。 */
        STORAGE_LOCKED,
        /**
         * 一路在录了 15 秒一个字节都没写进文件，而它的相机在出画面、会话里带着它的录像输出（录制器判的，
         * 和 WRITE_STALLED 是同一个裁判）：盘或编码器的事。相机没在出画面的，只那一路退出，不是停。
         */
        NO_DATA,
        /**
         * 录着录着写不进文件了，而相机好好的（在出画面、会话里带着它的录像输出）：编码器坏了没修好，或者 U 盘写不进 ——
         * 2026-09-26 哨兵模式那一次就是这样，界面显示「录制中」两个小时，一个文件都没写。相机的问题只那一路退出，不是停。
         */
        WRITE_STALLED,
        /**
         * 录像根本没开始，所以不说「中断」（2026-10-05 界面文字审查 rec_reason_unknown）。两种：相机层没能开始
         * （没有地方存、没有能录的一路）；还没有一路加入时，计划里每一路的录制器都建不起来（编码录制建不起来；
         * MediaRecorder 备不起来、或者开不了录）。两种录制器一样（MediaRecorder 模式 2.11 阶段 5d 起也按路进出）。
         * 会话带不上录像输出是相机那一侧的事，不算这个（那一路退出，隔一会儿再声明）；相机还没出画面的路等着加入，也不算。
         * 有一路加入过之后才每一路的录制器都建不起来的（接回时重新备好没成、另建一个也建不起来）不算这个（录像开始过），算 UNKNOWN。
         */
        START_FAILED,
        /**
         * 录制器自己停了，没人告诉我们为什么（含录着的那一份相机管线被释放、换掉）；有一路加入过之后才每一路的录制器都建不起来，
         * 也算这个（见 {@link #START_FAILED}）。
         */
        UNKNOWN,
    }

    private RecordingStops() {
    }

    /**
     * 这个原因停的，要不要自动接回去（大纲 §7 第 3 问，2026-10-11「按建议」）。
     *
     * <ul>
     *   <li>人停的 —— 永远不接；</li>
     *   <li>熄屏停的 —— 不自动接：亮屏时接（接不接熄屏时照 {@link ScreenOffPlan} 定）；黑着时只有熄屏中哨兵模式变成开、通道留下了环视
     *       才接（车机醒着、熄屏持续录制开着、计划里有环视、能用相机，只录环视，{@link ScreenOffPlan#startsWhileDark}）；</li>
     *   <li>存储满了 —— 接回去也录不下；</li>
     *   <li>存储一类（写不进、一笔没写进、管线被换、录制器都建不起来）—— 收拾完、查过盘就重开：
     *       重开一次就是新的编码器、新的文件，常常就好了。走同一份额度（{@link ResumeBudget}）。</li>
     * </ul>
     */
    public static boolean resumesAutomatically(Reason reason) {
        return reason == Reason.NO_DATA || reason == Reason.UNKNOWN || reason == Reason.WRITE_STALLED
                || reason == Reason.START_FAILED;
    }

    /**
     * 自动恢复的额度：连着失败这么多次就不再试。每一次自动接回都算一次。
     *
     * <h3>成功的标准是「录满一分钟」，不是「开始录了」</h3>
     *
     * <p>以前的计数在录像<b>开始</b>时就清零。开始录之后一直写不进的话，会一直循环：
     * 开始（清零）→ 超时停 → 接回去 → 开始（又清零）…… 所以只有真的录满一分钟，才把失败次数清掉。</p>
     */
    public static final class ResumeBudget {
        public static final int MAX_FAILURES = 3;
        public static final long SUCCESS_AFTER_MS = 60_000L;

        private int attempts;

        /** 还能不能再试一次。 */
        public boolean allows() {
            return attempts < MAX_FAILURES;
        }

        /** 又试了一次。 */
        public void noteAttempt() {
            attempts++;
        }

        /**
         * 一次录像停了，这一次里有一路在录的时间一共多久（一路都不在录、等待摄像头的那几段不算）。
         * 录满一分钟就算恢复成功，额度回满。
         */
        public void noteRecordingLasted(long durationMs) {
            if (durationMs >= SUCCESS_AFTER_MS) {
                attempts = 0;
            }
        }

        /** 人停了、人开了：前面的失败都不算了。 */
        public void reset() {
            attempts = 0;
        }

        public int attempts() {
            return attempts;
        }
    }

    /**
     * 存储一类整次停、判了自动接回之后，录像要的几样留不留（大纲 §7 第 8 问，项目所有者 2026-10-11「留」）：
     * 「录像要用相机」（RECORDING）、熄屏录制的唤醒锁。车辆信号不在其中：熄屏判断进程一起来就登记了核心，一直在读（熄屏 v3）。
     *
     * <ul>
     *   <li><b>判了自动接回就留着</b>（{@link #onStopped}）：相机不放、停放的录制器留在会话里，接回时直接加入 ——
     *       存储停不动通道。唤醒锁拉着车机的照样拉着，它自己的时限照算（以前一停就放，车机几秒后睡过去，
     *       不经那张表重判。审查 2026-10-11）；</li>
     *   <li><b>接回开录了</b>（{@link #finish}）：留着的几样从此是这一次录像的，什么都不放；</li>
     *   <li><b>确定不接了</b>（{@link #finish}，调用方照返回值放）：人停了、退出了、熄屏照表停了、查盘拒了
     *       （没 U 盘、盘满 —— 之后是等，等的不登记）、接回前的查盘卡住了（{@link #checkStuck}）。
     *       额度用完、本来就不接的原因，停的那一下就不留。</li>
     * </ul>
     *
     * <p>纯逻辑，见 {@code RecordingStopsTest}。</p>
     */
    public static final class ResumeHold {
        /**
         * 接回前的查盘多久没回来算卡住了：正常几十毫秒；盘掉线时探测会卡在存储线程上（2026-09-26 固态盘掉过 5 秒），
         * 存储线程只有一个，后面排着的也回不来。开录那边每次查盘之前问它：到点再看的那一轮（30 秒）一定判得出来，
         * 刚发出去又来查一次（人这时按了录制）不算。
         */
        public static final long CHECK_STUCK_MS = 10_000L;

        private boolean held;

        /** 一次录像停了：判了自动接回就留着，不接回就不留。 */
        public void onStopped(boolean willResume) {
            held = willResume;
        }

        /**
         * 留着的这一段到头了：接回开录了（交给这一次录像），或者确定不接了（调用方照返回值放）。
         *
         * @return 刚才留着
         */
        public boolean finish() {
            boolean was = held;
            held = false;
            return was;
        }

        /** 留着、等自动接回。 */
        public boolean held() {
            return held;
        }

        /**
         * 录像要的几样此刻该不该拿着（「录像要用相机」、熄屏录制的唤醒锁）：在录（录像意图开着），
         * 或者留着等自动接回。
         */
        public boolean claims(boolean recording) {
            return recording || held;
        }

        /**
         * 留着等自动接回，而接回前的查盘卡住了（发出去 {@link #CHECK_STUCK_MS} 还没回来）：留着的几样该放了，
         * 「要录」留着、接着等 —— 不然相机开着，什么都不录，一直到有人停、退出（审查 2026-10-11；
         * 以前存储一停就都放了，查盘卡住只是一直等）。
         *
         * @param checkSentAtMs 还没回来的那一次查盘什么时候发出去的（elapsedRealtime）；0 = 没有
         * @param now           此刻（elapsedRealtime）
         */
        public boolean checkStuck(long checkSentAtMs, long now) {
            return held && checkSentAtMs != 0 && now - checkSentAtMs >= CHECK_STUCK_MS;
        }
    }

    /**
     * 开录等 U 盘（项目所有者 2026-10-13「U 盘一可用就开录」）：开录前查盘，盘不能用（没插、车机刚醒还没挂上）就拒了、接着等。
     * 以前一律隔 30 秒再查一次；这台车机收不到 U 盘插拔的广播（VolumeEvents 注册着，09-26 到 10-13 的 32 份黑匣子里
     * 一行「U 盘事件」都没有），盘回来没有人叫。10-13 20:08:02 车机醒了要录，盘还没挂上；20:08:09 主界面回到前台时一查，
     * 盘已经能用了，录像却等到 20:08:33 那一次才开。所以等盘的头 {@link #FAST_FOR_MS}（从第一次拒起算）每
     * {@link #FAST_EVERY_MS} 查一次（查的是存储快照，便宜；黑匣子只在盘的样子变了时写一行），之后照旧 30 秒一次。
     * 只有这一个等法：拒的那一处（RecordingCoordinator.refused）照它排下一次查盘。
     *
     * <p>主线程上用；纯逻辑，见 {@code RecordingStopsTest}。</p>
     */
    public static final class DiskWait {
        /** 等盘的头 2 分钟多久查一次。 */
        public static final long FAST_EVERY_MS = 2_000L;
        /** 从第一次拒起，查得勤的那一段多长。 */
        public static final long FAST_FOR_MS = 120_000L;

        /** 这一次等盘从什么时候起（第一次因为盘不能用拒的那一刻，elapsedRealtime）；0 = 没在等盘。 */
        private long since;

        /**
         * 查盘时盘不能用：等盘从这一刻起（已经在等的不挪起点）。
         *
         * @return 这一次是查得勤的那一段里又查了一次（不是头一次）：拒录的提示不再说一遍 —— 还是同一个原因，
         *         界面本来也只说一次；过了那一段照旧每次都说（30 秒一次，和以前一样）
         */
        public boolean refused(long now) {
            boolean again = since != 0 && now - since < FAST_FOR_MS;
            if (since == 0) {
                since = now;
            }
            return again;
        }

        /** 下一次查盘隔多久：等盘的头 2 分钟 {@link #FAST_EVERY_MS}，之后（或者没在等盘）{@code slowMs}。 */
        public long nextCheckMs(long now, long slowMs) {
            return since != 0 && now - since < FAST_FOR_MS ? FAST_EVERY_MS : slowMs;
        }

        /**
         * 查盘时盘能用了：这一次等盘到头。
         *
         * @return 等了多久（毫秒）；没在等盘是 -1
         */
        public long ready(long now) {
            long waited = since == 0 ? -1 : now - since;
            since = 0;
            return waited;
        }

        /** 不等了（人停了、熄屏照表停了、退出了）。 */
        public void cancel() {
            since = 0;
        }

        /** 在等盘。 */
        public boolean waiting() {
            return since != 0;
        }
    }

    /**
     * 录像要的几样放了（录像停了、不接回；不等自动接回了）的时候，熄屏录制的唤醒锁要不要先照熄屏那张表重判再放：
     * 屏幕黑着、锁正拉着车机时要 —— 和锁到点那一次一样：安卓已经熄屏、判成现在停的，先写下「车机要睡」、
     * 停掉还在等的，锁留到相机按次序关完再放；接着录、10 秒后停的（车机醒着）当场放。亮着、锁没拿着、
     * 已经在等相机关完（熄屏照表停的那一次）：照停录那一下放（审查 2026-10-11）。
     *
     * @param dark        屏幕黑着
     * @param lockPulling 锁拿着、不是在等相机关完（看锁本身，不看时限）
     */
    public static boolean rejudgesBeforeLockGoes(boolean dark, boolean lockPulling) {
        return dark && lockPulling;
    }

    /**
     * 一段熄屏里录像停了几次、开了几次（熄屏小结，设计 v3 §6 L8、v3.1 C16）：熄屏停的（10 秒到点、车机要睡、通道放开了环视，
     * {@link Reason#SCREEN_OFF}）单独数、记下第一次的那一刻，不算「中途停过」；别的停都算中途停过；熄屏中哨兵模式变成开、
     * 通道留下环视后接着录（只录环视）、真开起来的单独数。一段熄屏里熄屏停可能不止一次：通道放开了停了，熄屏中哨兵模式变成开接着录，
     * 哨兵关了、车机要睡又停（审查 rev3 B6：以前只记第一次，后面那一段录像和它的停在小结里看不到）。熄屏那一下清零，
     * 亮屏那一行写完不清（下一次熄屏再清）。主线程上用；纯逻辑。
     */
    public static final class DarkTally {
        private int midwayStops;
        private int tableStops;
        private long firstTableStopAt;
        private int sentryStarts;

        /** 熄屏了：这一段从零数。 */
        public void reset() {
            midwayStops = 0;
            tableStops = 0;
            firstTableStopAt = 0;
            sentryStarts = 0;
        }

        /**
         * 这一段里录像停了。
         *
         * @param at 停的那一刻（elapsedRealtime）
         */
        public void stopped(Reason reason, long at) {
            if (reason != Reason.SCREEN_OFF) {
                midwayStops++;
                return;
            }
            if (tableStops++ == 0) {
                firstTableStopAt = at;
            }
        }

        /** 熄屏中哨兵模式变成开、通道留下环视后接着录，这一次真开起来了。 */
        public void sentryStarted() {
            sentryStarts++;
        }

        /** 中途停过几次（照表停的不算）。 */
        public int midwayStops() {
            return midwayStops;
        }

        /** 熄屏停录几次（照表的、通道放开的）。 */
        public int tableStops() {
            return tableStops;
        }

        /** 第一次熄屏停录的那一刻（elapsedRealtime）；没有是 0。 */
        public long firstTableStopAt() {
            return firstTableStopAt;
        }

        /** 熄屏中哨兵模式变成开后接着录、开起来了几次。 */
        public int sentryStarts() {
            return sentryStarts;
        }

        /** 这一段里停过（中途的、照表的都算）。 */
        public boolean anyStop() {
            return midwayStops > 0 || tableStops > 0;
        }
    }
}
