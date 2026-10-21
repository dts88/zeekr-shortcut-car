package com.kooo.evcam.sound;

/**
 * 合成器一块的参数（{@link Voice}）：声音线程里重用一份，不分配。都由 {@link VirtualEngine} 给出，
 * 两种合成器各取所需（{@link EngineVoice} 不看收油、车速、挡位）。
 */
final class VoiceParams {
    float rpm;
    /** 合成用的负荷（0–1）。 */
    float load;
    /** 发动机的电平（线性，{@link VirtualEngine#levelDb} 换来的）：管发动机本身的声音。 */
    float level;
    /** 总音量（线性）：声浪自己的音量 × 门控 × 开始工作时的淡入。起动机、倒挡啸叫、传动的啸叫也跟着它。 */
    float gain;
    float brightDb;
    float intakeBoostDb;
    float burble;
    float starter;
    float whineHz;
    float whineAmp;
    float toneHz = VirtualEngine.TONE_OPEN_HZ;
    /** 收油时发动机被车拖着转（断油）多少（0–1，{@link VirtualEngine#overrun}）。 */
    float overrun;
    float speedKmh;
    /** 按挡位走时的挡位（0 = 一挡；模拟换挡、或连续走法里的伪挡位）；没有挡位是 −1。 */
    int gearIndex = -1;
    /** 在 D 挡（变速箱、传动轴、差速器的声音只在 D 挡有）。 */
    boolean drive;
    /** 升挡断扭（0–1，{@link VirtualEngine#cut}）。 */
    float cut;
    /** 换了几次挡（{@link VirtualEngine#shiftSerial}）：一变就放换挡的声音；最近一次是不是升挡。 */
    int shiftSerial;
    boolean shiftUp = true;
}
