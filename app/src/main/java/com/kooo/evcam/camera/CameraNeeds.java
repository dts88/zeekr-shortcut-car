package com.kooo.evcam.camera;

import java.util.EnumSet;
import java.util.Set;

/**
 * 「现在还有人要相机吗」——这件事只在这里答。
 *
 * <h3>为什么要收到一处</h3>
 *
 * <p>以前这个问题有四个各自为政的答案，条件不同、节奏也不同：</p>
 *
 * <ul>
 *   <li>主界面 {@code onPause}：录制中 / 自动录制等待中 / 后视镜开着，都保留；</li>
 *   <li>熄屏 15 秒那个任务：只看录制中和熄屏录制，<b>不看后视镜</b>；</li>
 *   <li>后视镜服务每 2 秒一次的看门狗：只要窗口还显示着、相机没开，就重新绑定；</li>
 *   <li>相机兜底看门狗 {@link CameraLiveness}：有人要画面而长时间没帧就重开。</li>
 * </ul>
 *
 * <p>前两条直接打架：开着后视镜时，熄屏 15 秒把相机关掉，2 秒后看门狗又把它打开。
 * 「释放资源」这个动作每次熄屏都会白做一遍，相机整夜照开。</p>
 *
 * <p>收成登记表之后规则只有一句：<b>谁要用就登记，没人登记才关。</b>
 * 以后再加一个用相机的地方，是加一个登记者，而不是加第五条判断。</p>
 *
 * <p>以前还有一类是「问」不是「登记」：补盲 / 常驻预览 / 副屏那几个窗口，开关由它们自己的服务管着，
 * 这里去问它。那一套 1.44.0 删了，剩下的生命周期都清楚，都走登记。
 * 拍照（1.67.0）就是按这句话加进来的：加了一个登记者，没有加判断。</p>
 *
 * <p>纯逻辑，见 {@code CameraNeedsTest}。</p>
 *
 * <h3>2.11 起：只是数据，调度来读</h3>
 *
 * <p>登记表自己不动手：谁要哪几路、要什么输出、该不该保持，都由通道调度（{@link ChannelScheduler}）读这里的数据去判
 * （channel-logic §一，项目所有者 2026-10-10 确认）。监听者只是叫醒调度。同一张表上还记一项事实：「车机要睡」
 * （{@link #headUnitSleeps}）—— 熄屏那张表判成「现在停」时写（安卓熄屏了：熄屏 v3 起就是车机下电那一下）、安卓亮着时清
 * （车机黑着醒了、亮屏；录像协调器写，它是熄屏的唯一裁判，在不在录都判），
 * 调度看到它就不再保持、手上那一步最多再等 1 秒、然后按次序关相机。</p>
 */
public final class CameraNeeds {

    /** 谁在用相机。 */
    public enum Holder {
        /** 主界面的实时预览。 */
        PREVIEW,
        /** 录制中，或者正要开始录。 */
        RECORDING,
        /** 超级后视镜那个悬浮窗。 */
        MIRROR,
        /**
         * 拍照：从按下快门到这一张存完（或放弃）。相机没开就为它开，主界面不在前台时由出帧口出画面；
         * 拍完注销，没人要了照常关（1.67.0）。
         */
        PHOTO,
        /** 打转向灯的侧视窗：亮屏时显示或即时弹出备着，只要环视。 */
        SIDE_POPUP
    }

    private static final CameraNeeds CURRENT = new CameraNeeds();

    /** 整个进程共用的那一份。 */
    public static CameraNeeds current() {
        return CURRENT;
    }

    private final Set<Holder> holders = EnumSet.noneOf(Holder.class);

    /** 车机要睡，见 {@link #headUnitSleeps}。 */
    private boolean headUnitSleeps;

    /**
     * 登记表变了（或者「车机要睡」变了）谁来看：当前管理器的通道调度（{@link ChannelScheduler}）—— 只叫醒它，自己不判。
     * 在登记的那个线程上调。
     */
    public interface Listener {
        void onNeedsChanged();
    }

    private volatile Listener listener;

    public synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    /**
     * 摘掉监听者 —— 只在它还是 {@code listener} 时摘。换车型时两份管理器并存：旧的那份晚 release，
     * 不能把新那份接上的监听摘了（以前 release 一律 setListener(null)，新管线从此听不到登记表）。
     */
    public synchronized void clearListener(Listener listener) {
        if (this.listener == listener) {
            this.listener = null;
        }
    }

    /**
     * 写下 / 清掉「车机要睡」：熄屏那张表判成「现在停」（安卓熄屏了，哨兵模式关或读不到、没在开车）时写，安卓亮着时清
     * （写还是清由 {@code ScreenOffPlan.sleepFact} 定，录像协调器来写：RecordingCoordinator 的熄屏判定、车机黑着醒了、亮屏）。变了才叫监听者 ——
     * 调度据此当场把「正在按次序关」置真，不等它自己 post 出去的那一轮（熄屏录制放唤醒锁之前要读它）。
     */
    public void setHeadUnitSleeps(boolean sleeps) {
        boolean changed;
        synchronized (this) {
            changed = headUnitSleeps != sleeps;
            headUnitSleeps = sleeps;
        }
        if (changed) {
            notifyChanged();
        }
    }

    /**
     * 车机要睡：安卓熄屏了、熄屏那张表判了「现在停」，车机几秒后就断电。这不是谁要不要相机，是一项事实 ——
     * 调度看到它就不再保持，手上那一步最多再等 1 秒，然后按次序关（channel-logic §四，项目所有者 2026-10-10 确认）。
     */
    public synchronized boolean headUnitSleeps() {
        return headUnitSleeps;
    }

    /**
     * 登记：我要用相机。
     *
     * <p>重复登记无害 —— 同一个登记者只算一次，所以不需要配对计数。</p>
     */
    public void claim(Holder holder) {
        boolean changed;
        synchronized (this) {
            changed = holders.add(holder);
        }
        if (changed) {
            notifyChanged();
        }
    }

    /** 注销：我不用了。没登记过就注销也无害。 */
    public void release(Holder holder) {
        boolean changed;
        synchronized (this) {
            changed = holders.remove(holder);
        }
        if (changed) {
            notifyChanged();
        }
    }

    private void notifyChanged() {
        Listener l = listener;
        if (l != null) {
            l.onNeedsChanged();
        }
    }

    /** 这一项此刻有没有登记着。 */
    public synchronized boolean isHeld(Holder holder) {
        return holders.contains(holder);
    }

    /** 还有人要吗。没人要才该关相机。 */
    public synchronized boolean heldByAnyone() {
        return !holders.isEmpty();
    }

    /**
     * 除了这一个，还有别人要吗。
     *
     * <p>主界面退到后台时用：先问「除了预览还有谁」，有就留着，没有才关。</p>
     */
    public synchronized boolean heldByAnyoneExcept(Holder holder) {
        for (Holder held : holders) {
            if (held != holder) {
                return true;
            }
        }
        return false;
    }

    /** 日志和诊断报告里的一行。 */
    public synchronized String describe() {
        StringBuilder sb = new StringBuilder();
        for (Holder held : holders) {
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append(held);
        }
        // 状态记号，不是给人读的句子 —— 日志那一行自带上下文
        return sb.length() == 0 ? "(none)" : sb.toString();
    }
}
