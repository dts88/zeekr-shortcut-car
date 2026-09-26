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
 * 这里去问它。那一套 1.44.0 删了，剩下三类的生命周期都清楚，都走登记。</p>
 *
 * <p>纯逻辑，见 {@code CameraNeedsTest}。</p>
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
