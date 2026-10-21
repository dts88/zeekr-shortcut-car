package com.kooo.evcam.sound;

/**
 * 输出前最后一道（每个合成器一个）：峰值不超过 {@link EngineVoice#CEILING}，又不把波形削坏。
 * 项目所有者 2026-10-21 实车：0 dB 时原来那个软限幅（tanh 形的波形整形）在全油门被多推 9–12 dB，声音发糊，远不如试听。
 *
 * <ul>
 *   <li>先看 {@link #LOOKAHEAD} 个样本（48 kHz 下 1.3 ms，输出晚这么多）：后面要来的峰提前看到，增益在峰到之前平滑地降下去，
 *       不碰波形本身的形状。</li>
 *   <li>增益只按左右两边里大的那个算（左右一起降，声像不跑）；峰过去先停 {@link #HOLD_SEC}（比最低的点火周期长，
 *       两个峰之间增益不回弹，不然低频会被增益的起伏调出谐波），再按 {@link #RELEASE_SEC} 慢慢回来。</li>
 *   <li>万一还冒头（增益没完全降到位的那一点点）就截在上限 —— 只是兜底。</li>
 * </ul>
 *
 * <p>纯计算（{@code LimiterTest}），声音线程上逐样本调，不分配内存。</p>
 */
final class Limiter {

    /** 先看多少个样本（2 的幂）。 */
    static final int LOOKAHEAD = 64;
    /** 峰过去以后增益先停多久（秒）：怠速 2 阶 30 Hz 一个周期 33 ms 里有两个峰。 */
    static final float HOLD_SEC = 0.025f;
    /** 再按这个时间常数回来（秒）。 */
    static final float RELEASE_SEC = 0.08f;

    private static final int MASK = LOOKAHEAD - 1;
    private static final int WINDOW = LOOKAHEAD + 1;
    /** 滑动窗口最大值的队列（单调递减），容量取比窗口大的 2 的幂。 */
    private static final int QUEUE = 2 * LOOKAHEAD;
    private static final int QUEUE_MASK = QUEUE - 1;

    private final float attackK;
    private final float releaseK;
    private final int holdSamples;
    private int holdLeft;
    private final float[] delayL = new float[LOOKAHEAD];
    private final float[] delayR = new float[LOOKAHEAD];
    private final float[] queuePeak = new float[QUEUE];
    private final long[] queueAt = new long[QUEUE];
    private int head;
    private int tail;
    private long count;
    private int pos;
    private float gain = 1f;
    /** 刚出来的一对（{@link #push}）。 */
    float left;
    float right;

    Limiter(float sampleRate) {
        // 降：窗口里五个时间常数就到位（差 0.7 %，由兜底截掉）
        attackK = 1f - (float) Math.exp(-5.0 / LOOKAHEAD);
        releaseK = 1f - (float) Math.exp(-1.0 / (RELEASE_SEC * sampleRate));
        holdSamples = Math.round(HOLD_SEC * sampleRate);
    }

    /** 进一对样本，出晚 {@link #LOOKAHEAD} 个样本的那一对（放在 {@link #left}、{@link #right}）。 */
    void push(float l, float r) {
        float peak = Math.max(Math.abs(l), Math.abs(r));
        long now = count++;
        while (tail != head && queuePeak[(tail - 1) & QUEUE_MASK] <= peak) {
            tail = (tail - 1) & QUEUE_MASK;
        }
        queuePeak[tail] = peak;
        queueAt[tail] = now;
        tail = (tail + 1) & QUEUE_MASK;
        while (queueAt[head] <= now - WINDOW) {
            head = (head + 1) & QUEUE_MASK;
        }
        float windowPeak = queuePeak[head];
        float target = windowPeak > EngineVoice.CEILING ? EngineVoice.CEILING / windowPeak : 1f;
        if (target < gain) {
            gain += (target - gain) * attackK;
            holdLeft = holdSamples;
        } else if (holdLeft > 0) {
            holdLeft--;
        } else {
            gain += (target - gain) * releaseK;
        }
        float dl = delayL[pos];
        float dr = delayR[pos];
        delayL[pos] = l;
        delayR[pos] = r;
        pos = (pos + 1) & MASK;
        left = clip(dl * gain);
        right = clip(dr * gain);
    }

    /** 单声道：左右一样。 */
    float push(float x) {
        push(x, x);
        return left;
    }

    /** 静音时清空（重新出声时不带出上一段的尾巴）。 */
    void reset() {
        for (int i = 0; i < LOOKAHEAD; i++) {
            delayL[i] = 0f;
            delayR[i] = 0f;
        }
        head = 0;
        tail = 0;
        count = 0L;
        pos = 0;
        gain = 1f;
        holdLeft = 0;
        left = 0f;
        right = 0f;
    }

    /** 此刻的增益（测试看它降了多少、回没回来）。 */
    float gain() {
        return gain;
    }

    private static float clip(float x) {
        return x > EngineVoice.CEILING ? EngineVoice.CEILING : (x < -EngineVoice.CEILING ? -EngineVoice.CEILING : x);
    }
}
