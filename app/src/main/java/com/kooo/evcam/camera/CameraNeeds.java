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
 *   <li>主界面 {@code onPause}：录制中 / 自动录制等待中 / 有补盲悬浮窗 / 后视镜开着，都保留；</li>
 *   <li>熄屏 15 秒那个任务：只看录制中和熄屏录制，<b>不看后视镜也不看悬浮窗</b>；</li>
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
 * <h3>悬浮窗那一类是「问」不是「登记」</h3>
 *
 * <p>补盲、常驻预览、副屏这几个窗口的开关由 {@code BlindSpotService} 自己管着，
 * 它已经有一个现成的 {@code hasActiveCameraWindows()}。再让它登记一遍，就等于同一件事
 * 有两个真相，迟早对不上 —— 所以这里直接问它。其余三类（预览、录制、后视镜）
 * 的生命周期清楚，走登记。</p>
 *
 * <p>纯逻辑（除了问悬浮窗那一句），见 {@code CameraNeedsTest}。</p>
 */
public final class CameraNeeds {

    /** 谁在用相机。 */
    public enum Holder {
        /** 主界面的实时预览。 */
        PREVIEW,
        /** 录制中，或者正要开始录。 */
        RECORDING,
        /** 超级后视镜那个悬浮窗。 */
        MIRROR
    }

    private static final CameraNeeds CURRENT = new CameraNeeds();

    /** 整个进程共用的那一份。 */
    public static CameraNeeds current() {
        return CURRENT;
    }

    private final Set<Holder> holders = EnumSet.noneOf(Holder.class);
    /** 悬浮窗那一类要不要算，由调用方接上；没接就当没有。 */
    private OverlayProbe overlayProbe;

    /** 补盲 / 常驻 / 副屏那几个窗口在不在用相机。 */
    public interface OverlayProbe {
        boolean hasActiveCameraWindows();
    }

    public void setOverlayProbe(OverlayProbe probe) {
        this.overlayProbe = probe;
    }

    /**
     * 登记：我要用相机。
     *
     * <p>重复登记无害 —— 同一个登记者只算一次，所以不需要配对计数。</p>
     */
    public synchronized void claim(Holder holder) {
        holders.add(holder);
    }

    /** 注销：我不用了。没登记过就注销也无害。 */
    public synchronized void release(Holder holder) {
        holders.remove(holder);
    }

    /** 还有人要吗。没人要才该关相机。 */
    /** 这一项此刻有没有登记着。 */
    public synchronized boolean isHeld(Holder holder) {
        return holders.contains(holder);
    }

    public synchronized boolean heldByAnyone() {
        return !holders.isEmpty() || overlayActive();
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
        return overlayActive();
    }

    private boolean overlayActive() {
        OverlayProbe probe = overlayProbe;
        try {
            return probe != null && probe.hasActiveCameraWindows();
        } catch (Throwable t) {
            return false;
        }
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
        if (overlayActive()) {
            if (sb.length() > 0) {
                sb.append('+');
            }
            sb.append("OVERLAY");
        }
        // 状态记号，不是给人读的句子 —— 日志那一行自带上下文
        return sb.length() == 0 ? "(none)" : sb.toString();
    }
}
