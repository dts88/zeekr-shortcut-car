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
        /** 自动录制开着、熄屏录制没生效，熄屏 10 秒停录。熄屏录制不在这一次的范围里。 */
        SCREEN_OFF,
        /** U 盘满了。存储那边会自己从最老的清，走到这一步说明清不动。 */
        STORAGE_FULL,
        /** U 盘满了而且清不出空间（没设上限、或者全是锁定的）。 */
        STORAGE_CANNOT_FREE,
        /** 开始录之后一直没收到画面，看门狗把它停了。 */
        NO_DATA,
        /** 录制器自己停了，没人告诉我们为什么。 */
        UNKNOWN,
    }

    private RecordingStops() {
    }

    /**
     * 这个原因停的，环视一恢复要不要自动接回去。
     *
     * <ul>
     *   <li>人停的 —— 永远不接；</li>
     *   <li>熄屏 10 秒 —— 不在这一次的范围里，维持原来亮屏时接回的做法；</li>
     *   <li>存储满了 —— 环视好不好跟它无关，接回去也录不下；</li>
     *   <li>其余 —— 都是相机那一侧的问题，环视回来了就接。</li>
     * </ul>
     */
    public static boolean resumesOnSurround(Reason reason) {
        return reason == Reason.NO_DATA || reason == Reason.UNKNOWN;
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
