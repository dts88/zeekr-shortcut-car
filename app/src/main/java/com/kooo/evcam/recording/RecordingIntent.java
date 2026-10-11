package com.kooo.evcam.recording;

/**
 * 「现在该不该自己开始录制」——这件事只在这里判。
 *
 * <h3>为什么要单独拎出来</h3>
 *
 * <p>「启动自动录制」这个开关原本被当成「只要没在录就去录」来用，于是有四条路都会
 * 悄悄把录制打开：</p>
 *
 * <ul>
 *   <li>从视频回放（另一个 Activity）切回主界面 —— 回到前台就开录，<b>连用户停没停过都不看</b>；</li>
 *   <li>主界面重建（切日夜模式、换语言、关掉再打开）—— 「已经自动开过了」这个标记
 *       是<b>界面自己的字段</b>，界面一换就归零，于是又开一次；</li>
 *   <li>30 秒一次的定时检查 —— 「用户手动停过」这个标记同样跟着界面走，重建之后就忘了；</li>
 *   <li>同一个定时检查在<b>从来没录起来过</b>时也会开录（比如开机没插 U 盘，
 *       自动录制失败，它就每 30 秒重试一次并弹一次提示）。</li>
 * </ul>
 *
 * <p>把判断集中到这里，语义就只剩一句话：<b>「启动自动录制」是启动时自动开一次；
 * 此外只有「录着录着意外停了」才接回去，用户自己停的永远不接。</b></p>
 *
 * <p>状态挂在进程上而不是界面上 —— 界面会被重建，而「这次启动开过没有」「用户停过没有」
 * 说的是整个应用这一趟，不是某一个界面实例。</p>
 *
 * <p>还有一位「这一趟要录」（{@link #recordingWanted}，2026-10-10）：在录、在等开录、因熄屏停下等亮屏接回时立着，
 * 进程被车机结束了也还在 —— 新的进程在屏幕亮着时照它接着录（规格 1.2：被杀又拉回来，恢复到被杀前的状态）。
 * 以前「熄屏暂停，唤醒后恢复」只记在内存里，熄屏几秒后进程一被结束就忘了。</p>
 *
 * <p>纯逻辑，见 {@code RecordingIntentTest}。</p>
 */
public final class RecordingIntent {

    /**
     * 连着接不回去这么多次就不再试。
     *
     * <p>录不起来往往是拔了 U 盘、相机被占这类不会自己好的原因，
     * 每 30 秒重试一次只是每 30 秒弹一次提示。</p>
     */
    private static final RecordingIntent CURRENT = new RecordingIntent();

    /** 整个进程共用的那一份。 */
    public static RecordingIntent current() {
        return CURRENT;
    }

    private boolean autoStarted;
    private boolean stoppedByUser;
    private boolean everStarted;
    private boolean recordingWanted;

    /**
     * 落盘（规格 1.2）：进程被杀又拉回来时，「这一趟」的选择要还在 ——
     * 手动停过的不能因为进程换了就忘。车机真正开机、用户手动打开时清掉，
     * 那是 Application 和主界面的事。测试里不接。
     */
    public interface Store {
        boolean get(String key, boolean fallback);

        /**
         * 这几项一起写。
         *
         * @param durable true：写到盘上才返回（commit）—— 只给「这一趟要录」变了、人按了停止这两种：哨兵模式没开时车机熄屏
         *                3–5 秒就把进程结束（2026-10-10），熄屏那一刻改的「这一趟要录」（亮屏接不接）得赶在那之前落盘；
         *                这是主线程上唯一一处同步写盘（大纲 §3 线程）。false：排队异步写（apply），别的几项不值得在主线程上等盘
         */
        void put(java.util.Map<String, Boolean> values, boolean durable);
    }

    private Store store;

    public void attach(Store store) {
        this.store = store;
        if (store != null) {
            autoStarted = store.get("autoStarted", false);
            stoppedByUser = store.get("stoppedByUser", false);
            everStarted = store.get("everStarted", false);
            recordingWanted = store.get("recordingWanted", false);
        }
    }

    /**
     * 写下这几项。调用方只在真变了时叫：每次开录、每次立「这一趟要录」都来一遍，没变也写的话，开录时主线程上一两次同步写盘
     * （中转写入时内置闪存正忙着写三路视频，审查 2026-10-11）。
     *
     * @param durable 见 {@link Store#put}
     */
    private void persist(boolean durable) {
        if (store != null) {
            java.util.Map<String, Boolean> values = new java.util.LinkedHashMap<>();
            values.put("autoStarted", autoStarted);
            values.put("stoppedByUser", stoppedByUser);
            values.put("everStarted", everStarted);
            values.put("recordingWanted", recordingWanted);
            store.put(values, durable);
        }
    }

    /**
     * 启动时该不该自动开一次。
     *
     * <p>这一趟已经自动开过、或者用户自己停过，都不再开 —— 后者最要紧：
     * 用户按了停止之后切个日夜模式，不该看见它又录上了。</p>
     */
    public boolean shouldAutoStart(boolean enabled) {
        return enabled && !autoStarted && !stoppedByUser;
    }

    /** 已经自动开过一次了（不管最后录没录起来，这一趟都不再自动开第二次）。 */
    public void noteAutoStarted() {
        if (autoStarted) {
            return;
        }
        autoStarted = true;
        persist(false);
    }

    /**
     * 录着录着停了，该不该接回去。
     *
     * <p>三个前提缺一不可：开着这个功能、<b>这一趟真的录起来过</b>、而且不是用户自己停的。
     * 中间那条是「从没录起来过就别自己开」——回放看完切回来、刚启动还没插 U 盘，
     * 都属于「没录起来过」。连着接不回去的次数不在这里算，那是 RecordingCoordinator 的额度。</p>
     */
    public boolean shouldRestore(boolean enabled) {
        return enabled && everStarted && !stoppedByUser;
    }

    public void noteRecordingStarted() {
        if (everStarted) {
            return;
        }
        everStarted = true;
        persist(false);
    }

    /** 用户自己按了开始 —— 「停过」的记录作废，自动开的额度也算用掉了。 */
    public void noteUserStarted() {
        if (!stoppedByUser && autoStarted) {
            return;
        }
        stoppedByUser = false;
        autoStarted = true;
        persist(false);
    }

    /** 用户自己按了停止。在这一趟里，没有任何一条路可以再自动开起来（「这一趟要录」也撤）。 */
    public void noteUserStopped() {
        if (stoppedByUser && !recordingWanted) {
            return;
        }
        stoppedByUser = true;
        recordingWanted = false;
        persist(true);
    }

    public boolean stoppedByUser() {
        return stoppedByUser;
    }

    /**
     * 这一趟要录：在录、在等开录，或者因熄屏停下、亮屏要接回。进程被结束了也还在（落盘），
     * 屏幕亮着时（亮屏、进程在屏幕亮着时被拉起来）协调器照它接着录。立、撤都由录像协调器做；
     * 人停了、退出、车机真正开机、人点开主界面时撤（{@link #noteUserStopped}、{@link #reset}）。
     */
    public boolean recordingWanted() {
        return recordingWanted;
    }

    /**
     * 立、撤「这一趟要录」。
     *
     * @return 变了没有：变了才落盘，调用方据此记黑匣子
     */
    public boolean noteRecordingWanted(boolean wanted) {
        if (recordingWanted == wanted) {
            return false;
        }
        recordingWanted = wanted;
        persist(true);
        return true;
    }

    /** 退出应用时归零 —— 下一次打开是新的一趟。 */
    public void reset() {
        if (!autoStarted && !stoppedByUser && !everStarted && !recordingWanted) {
            return;
        }
        boolean wantedDropped = recordingWanted;
        autoStarted = false;
        stoppedByUser = false;
        everStarted = false;
        recordingWanted = false;
        persist(wantedDropped);
    }

    /** 诊断报告里的一行。 */
    public String describe() {
        return "autoStarted=" + autoStarted + " stoppedByUser=" + stoppedByUser
                + " everStarted=" + everStarted + " recordingWanted=" + recordingWanted;
    }
}
