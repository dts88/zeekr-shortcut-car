package com.kooo.evcam.zeekr;

/**
 * 录像回放里谁领着时间走（项目所有者 2026-10-10：进度条跟着环视）。
 *
 * <p>进度条按环视排。网格里环视领头；放大了一路座舱时由它领 —— <b>但只在它此时有录像的时候</b>。
 * 一路相机被别的程序占用时只停那一路、其余照录，放开后单独接回，于是座舱的录像中间会缺几分钟、
 * 也可能比环视早结束。以前放大的座舱领到这种地方：进度条跳过缺的那几分钟（领头的直接接下一段），
 * 放完它的最后一段整个停下（环视明明还有），拖到它没有录像的地方就卡住不动。
 * 现在一条规则：放大的座舱此时没有录像，就收回网格，环视接着领。</p>
 *
 * <p>「此时有录像」带一点宽容：下一段在 {@link RecordingTimeline#DEFAULT_MAX_GAP_MS} 以内开头也算 ——
 * 和环视自己判断「还是同一次录制」用同一个数。文件名只精确到秒，接回来的那一路文件名
 * 又比它真正出画面早一点，算出来的两段之间的空会比实际大；按回放里「接着的」那 2 秒算，
 * 放大的座舱会在其实连着的地方被收回去。</p>
 *
 * <p>时刻都先夹进这一条录制（{@link #clamp}）：环视结束之后的座舱录像存着，但不放。</p>
 *
 * <p>调用方先为<b>要去的那一刻</b>定好谁领头，再摆一次画面 —— 不放进对齐（place）里判断：
 * 那里一收回网格就又要摆画面、又回到对齐，拖动时还会拿旧位置盖掉要去的位置。</p>
 *
 * <p>纯逻辑，不碰 Android API，可以直接跑 JVM 单元测试。</p>
 */
public final class PlaybackLead {

    /** 领头的那一路一个文件放完之后怎么走。 */
    public enum AfterClip {
        /** 接着放这一路的下一段。 */
        NEXT_CLIP,
        /** 收回网格，环视从这一段结束的那一刻接着领。 */
        SURROUND,
        /** 这一条录制放完了：停在末尾。 */
        END
    }

    private PlaybackLead() {
    }

    /**
     * 放大的这一路座舱，到 epochMs 这一刻能不能领头。
     *
     * <p>能：这一刻在它的某个文件里，或者它的下一个文件 {@link RecordingTimeline#DEFAULT_MAX_GAP_MS}
     * 以内就开头；而且那个文件在环视结束之前开头。环视结束那一刻及以后，座舱一律不领。</p>
     */
    public static boolean cabinLeads(LaneTrack cabin, RecordingTimeline.Session session,
                                     long epochMs) {
        long end = session.endEpochMs();
        if (cabin == null || epochMs >= end) {
            return false;
        }
        LaneTrack.Hit hit = cabin.atOrAfter(epochMs);
        return hit != null && hit.clip.startEpochMs < end
                && hit.clip.startEpochMs - epochMs <= RecordingTimeline.DEFAULT_MAX_GAP_MS;
    }

    /**
     * 夹进这一条录制：不早于第一段开头，不晚于最后一段结束前 1 毫秒。
     *
     * <p>结束那一刻本身不在任何一段里（段是左闭右开的），落在那里环视就找不到要放的文件。</p>
     */
    public static long clamp(RecordingTimeline.Session session, long epochMs) {
        long last = Math.max(session.startEpochMs, session.endEpochMs() - 1);
        return Math.max(session.startEpochMs, Math.min(epochMs, last));
    }

    /**
     * 领头的那一路第 finished 个文件放完了，接下来怎么走（环视的文件放不出来也按放完算；
     * 放大的座舱放不出来则直接交回环视，不走这里）。
     *
     * <ul>
     *   <li>环视：有下一段就接，没有就是放完了 —— 和以前一样。</li>
     *   <li>座舱：这一段结束那一刻它还能领（{@link #cabinLeads}）就接下一段；不能的话，
     *       离环视结束不到 {@link RecordingTimeline#DEFAULT_MAX_GAP_MS} 就当放完了
     *       （同一个宽容：各路的最后一段本来就差一两秒结束，不为这一两秒收回网格），
     *       否则交回环视。</li>
     * </ul>
     *
     * @param finished 放完的那个文件在 track 里是第几个；必须是有效下标
     */
    public static AfterClip afterClip(boolean surround, LaneTrack track, int finished,
                                      RecordingTimeline.Session session) {
        boolean hasNext = finished + 1 < track.size();
        if (surround) {
            return hasNext ? AfterClip.NEXT_CLIP : AfterClip.END;
        }
        long end = track.clip(finished).endEpochMs();
        if (hasNext && cabinLeads(track, session, end)) {
            return AfterClip.NEXT_CLIP;
        }
        return session.endEpochMs() - end <= RecordingTimeline.DEFAULT_MAX_GAP_MS
                ? AfterClip.END : AfterClip.SURROUND;
    }
}
