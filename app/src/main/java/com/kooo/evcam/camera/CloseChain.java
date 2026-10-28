package com.kooo.evcam.camera;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 每台相机还没返回的关，按相机 id 记（SingleCamera 的 CLOSING 就是一份：跟着进程，管线重建、调度停了都还在）。
 * 纯 Java：时刻由调用方给，「当前这条线程」由调用方给一个记号（SingleCamera 给相机线程的 Looper），见 {@code CloseChainTest}。
 *
 * <p><b>一条链</b>：同一台相机连着关几次，后一次登记时记下前一次（{@link Closing#previous}），关完之前先等前一次返回
 * （调用方等，SingleCamera.finishClosing）—— 一次关返回了，它之前的都返回了。所以这里只留最新登记的那一次：它在，
 * 这一台就还有关没返回。这一台从什么时候起一直有关没返回，记在链头那一次的时刻上，之后登记的每一次都带着它
 * （{@link Closing#outstandingSince}）：一次关卡住了、上面又登记一次，调度那边的钟不从头算（审查 2026-10-28：
 * 以前按最新那一次的时刻算，上面每压一次关，别的路就再等 60 秒，「满 60 秒还没返回」那一行、状态条那一句也跟着往后推）。</p>
 *
 * <p><b>不等自己</b>（{@link #begin}）：链上还没返回的哪一次，关它的那一下排在当前这条线程上、还没轮到，这一次就不另登记、不等。</p>
 */
final class CloseChain {

    /** 一次关：等它返回的 latch，加上从什么时候起关的、为什么关、关它的那一下在哪条线程上做、链上它前面那一次。 */
    static final class Closing extends CountDownLatch {
        /** 这一次从什么时候起关的（调用方的钟；SingleCamera 用 uptime，和调度同一个钟）。 */
        final long since;
        /** 为什么关（英文短语）。 */
        final String why;
        /** 关它的那一下（返回、放行）在哪条线程上做：那条线程的记号（SingleCamera 给 Looper）；不知道是 null。 */
        final Object finisher;
        /**
         * 登记时这一台还没返回的那一次（链上的前一次）；没有是 null。在 {@link #begin} 里设；这一次返回了就断开
         * （{@link #finish}），链上已经返回的那一串不留着。
         */
        volatile Closing previous;
        /** 这一台从什么时候起一直有关没返回：登记时前一次还没返回，跟着前一次；否则就是 {@link #since}（{@link #begin}）。 */
        volatile long outstandingSince;

        Closing(String why, Object finisher, long since) {
            super(1);
            this.since = since;
            this.why = why;
            this.finisher = finisher;
            this.outstandingSince = since;
        }

        /**
         * 从这一次往前、链上还没返回的那几次里，有没有哪一次关它的那一下排在 {@code thread} 上、还没轮到。遇到返回了的那一次就停：
         * 每一次都等前一次返回了才返回，它返回了，它前面的都返回了（返回之后才断开 {@link #previous}，断开的那一下读到 null 也停得对）。
         */
        boolean queuedOn(Object thread) {
            for (Closing c = this; c != null && c.getCount() > 0; c = c.previous) {
                if (c.finisher != null && c.finisher == thread) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 每台相机最新登记的那一次。 */
    private final Map<String, Closing> latest = new ConcurrentHashMap<>();

    /**
     * 登记一次关：从这一刻起这一台算在关。返回链上的前一次 —— 这一次返回之前要等它返回；没有是 null。
     *
     * <p>链上还没返回的哪一次，关它的那一下排在 {@code thread} 上、还没轮到（{@link Closing#queuedOn}，一直往前看到返回了的那一次）：
     * 这一次不另登记、返回 null（不等）。等它就是等自己 —— 那一下要等当前这一下做完才开始，这条线程从此堵住，链上比它新的都跟着返回不了。
     * 不另登记照样对：那一次排在当前这一下后面，比它新的每一次都要等它返回才返回，所以最新登记着的那一次一直在、盖住这一次，
     * 直到当前这一下做完之后；后面的开、调度照它等。查和登记在同一下里做（{@code compute}）。</p>
     *
     * <p>以前只看最新的那一次排没排在这条线程上（审查 2026-10-28）：同步的那几种关（过时的回调就地关、丢了之后的关）在回调里才登记，
     * 排在这条线程上的那一次上面可能已经压了一次别的线程的。这条线程等最新的那一次，最新的那一次等它前面那一次，
     * 前面那一次排在这条线程上、等这一下做完 —— 两条相机线程永远堵住。</p>
     */
    Closing begin(String id, Closing closing, Object thread) {
        final Closing[] previous = new Closing[1];
        latest.compute(id, (key, current) -> {
            if (current != null && current.queuedOn(thread)) {
                return current;
            }
            if (current != null) {
                closing.previous = current;
                if (current.getCount() > 0) {
                    closing.outstandingSince = current.outstandingSince;
                }
            }
            previous[0] = current;
            return closing;
        });
        return previous[0];
    }

    /**
     * 一次关返回了（调用方先等过它的前一次）：还是最新的那一次就摘掉登记，放行等它的，断开它和前一次。
     * 上面又登记了一次的只放行：最新的那一次还在，这一台还在关。
     */
    void finish(String id, Closing closing) {
        latest.remove(id, closing);
        closing.countDown();
        closing.previous = null;
    }

    /** 这一台最新登记的那一次；没有关没返回是 null。 */
    Closing get(String id) {
        return latest.get(id);
    }

    /** 这一台有关没返回。 */
    boolean containsKey(String id) {
        return latest.containsKey(id);
    }

    /** 这一台从什么时候起一直有关没返回（{@link Closing#outstandingSince}）；没有是 0。 */
    long outstandingSince(String id) {
        Closing closing = latest.get(id);
        return closing == null ? 0L : closing.outstandingSince;
    }

    /** 此刻每台相机最新登记的那一次（只读，跟着变）：退出时等全部返回用。 */
    Map<String, Closing> all() {
        return Collections.unmodifiableMap(latest);
    }
}
