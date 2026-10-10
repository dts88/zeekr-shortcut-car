package com.kooo.evcam.recording;

/**
 * 录像为什么停了，以及停了之后要不要等环视恢复再接回去。
 *
 * <h3>定义（2026-09-26 项目所有者，规格 §2.3）</h3>
 *
 * <p>录像被非人为原因打断后，<b>环视视频流一恢复就继续录</b>，不用等主界面显示。
 * 打断要记进日志、告诉用户是被什么打断的；在自动恢复的话也要说一声。</p>
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
        /** 开始录之后 15 秒一个字节都没写进文件（录制器判的，和 WRITE_STALLED 是同一个裁判）。 */
        NO_DATA,
        /**
         * 录着录着写不进文件了：编码器坏了没修好，或者 U 盘写不进。相机可能一切正常 ——
         * 2026-09-26 哨兵模式那一次就是这样，界面显示「录制中」两个小时，一个文件都没写。
         */
        WRITE_STALLED,
        /**
         * 录着的那一路相机被相机服务断开了：别的程序（车机原生功能）拿走了相机（2026-09-27 实测，
         * 相机 1 和 2 同时只能开一路）。它放开、我们接回相机之后录像自动继续。
         * 2026-10-10 起只有它是最后一路在录的时候才整次停在这个原因上（MediaRecorder 模式下哪一路都是）：
         * 别的路还在录时只停这一路、放开了单独接回，录像不停（{@code RecordingLanes}）。
         */
        CAMERA_LOST,
        /**
         * 开录走到底一路都没起来：会话全配不上、录制器全启动失败、准备失败。录了一阵之后 MediaRecorder 重建没起来不算这个（录像开始过），算 UNKNOWN。
         * 录像根本没开始，所以不说「中断」（2026-10-05 界面文字审查 rec_reason_unknown）。
         */
        START_FAILED,
        /** 录制器自己停了，没人告诉我们为什么（含录着的那一份相机管线被释放、换掉）。 */
        UNKNOWN,
    }

    private RecordingStops() {
    }

    /**
     * 这个原因停的，环视一恢复要不要自动接回去。
     *
     * <ul>
     *   <li>人停的 —— 永远不接；</li>
     *   <li>熄屏停的 —— 不等环视：黑着的时候不该自己录起来，亮屏时接（接不接熄屏时照 {@link ScreenOffPlan} 定）；</li>
     *   <li>存储满了 —— 环视好不好跟它无关，接回去也录不下；</li>
     *   <li>写不进文件 —— 重开一次录制就是换一个新的编码器、新的文件，常常就好了；</li>
     *   <li>相机被拿走 —— 它放开、我们接回之后就接着录；</li>
     *   <li>开录失败 —— 用户要录，只是这一次没起来：和别的打断一样接，走同一份额度；</li>
     *   <li>其余 —— 都是相机那一侧的问题，环视回来了就接。</li>
     * </ul>
     */
    public static boolean resumesOnSurround(Reason reason) {
        return reason == Reason.NO_DATA || reason == Reason.UNKNOWN || reason == Reason.WRITE_STALLED
                || reason == Reason.CAMERA_LOST || reason == Reason.START_FAILED;
    }

    /**
     * 这次接回计不计入额度。
     *
     * <p>额度防的是「接回去又立刻停」的循环。相机确实被别的程序拿走的那种不计：它放开之前接不回去，
     * 不会循环。但「被相机服务断开」还有一种是自己顶掉自己（2026-10-08：同一路相机两个打开在途），
     * 相机马上回来、马上又被顶掉，不计额度就是无限循环 —— 所以只有别的程序真占着相机时才不计。</p>
     */
    public static boolean countsTowardBudget(Reason reason, boolean othersHoldCamera) {
        return reason != Reason.CAMERA_LOST || !othersHoldCamera;
    }

    /**
     * 自动恢复的额度：连着失败这么多次就不再试。
     *
     * <h3>成功的标准是「录满一分钟」，不是「开始录了」</h3>
     *
     * <p>以前的计数在录像<b>开始</b>时就清零。开始录之后一直没画面的话，会一直循环：
     * 开始（清零）→ 超时停 → 接回去 → 开始（又清零）…… 每一轮都把环视重建一次，
     * 而环视最怕的就是被反复开关。所以只有真的录满一分钟，才把失败次数清掉。</p>
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

        /** 一段录像停了，它录了多久。录满一分钟就算恢复成功，额度回满。 */
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
