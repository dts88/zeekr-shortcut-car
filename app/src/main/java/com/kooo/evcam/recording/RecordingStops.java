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
         * 因熄屏停的（{@link ScreenOffPlan}）：哨兵模式没开、车没在走时熄屏那一刻就停（车机几秒后就断电，2026-10-10）；
         * 熄屏持续录制关着、而车机醒着（哨兵模式开着、车在走）时熄屏 10 秒后停。亮屏后接不接，停的那一刻照表定好了。
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
         * 录像根本没开始，所以不说「中断」（2026-10-05 界面文字审查 rec_reason_unknown）。
         * 计划里每一路的录制器都建不起来（会话都带不上它们的录像输出；MediaRecorder 模式还算上开录没成）——
         * 这不是相机的问题，相机还没出画面的路等着加入，不算失败。两种录制器一样（MediaRecorder 模式 2.11 阶段 5d 起也按路进出）。
         * 录了一阵之后才每一路都录不了的不算这个（录像开始过），算 UNKNOWN。
         */
        START_FAILED,
        /** 录制器自己停了，没人告诉我们为什么（含录着的那一份相机管线被释放、换掉）。 */
        UNKNOWN,
    }

    private RecordingStops() {
    }

    /**
     * 这个原因停的，要不要自动接回去（大纲 §7 第 3 问，2026-10-11「按建议」）。
     *
     * <ul>
     *   <li>人停的 —— 永远不接；</li>
     *   <li>熄屏停的 —— 不自动接：黑着的时候不该自己录起来，亮屏时接（接不接熄屏时照 {@link ScreenOffPlan} 定）；</li>
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
}
