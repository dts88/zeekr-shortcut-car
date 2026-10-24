package com.kooo.evcam.sound;

/**
 * 「电动概念车」风格（{@link SoundStyle#ELECTRIC}，试制。项目所有者 2026-10-23：参考车三种主题里电动概念车声的那一种，照「超音速」的做法仿制；
 * 参考车说它代表高性能电动车「未来感」的声音，评测说是三种里最温和的、像电动车自己的声音）：一套电驱的声音，按车内喇叭播放来做。
 * 纯计算（{@code ElectricVoiceTest}），声音线程上一块一块地调，循环里不分配内存。量过的参考在 docs/local/drive-sound-plan.md §13。
 *
 * <ul>
 *   <li><b>电机</b>：电机的电磁力按转一圈的整数倍出声（阶次音），扭矩越大越响、高处的阶次越亮。这里的「转速」是虚拟发动机的
 *       （参考车的 e-Shift 也让这一套跟着模拟的 8 速走）：主音 {@link #MAIN_ORDER} 阶，全油门升挡点（6000 rpm）约 260 Hz ——
 *       参考全油门挂挡加速时主音在 155–275 Hz 之间，升挡掉回 0.7–0.8 倍，和发动机那一种一样。</li>
 *   <li><b>几根音</b>：主音、下面八度、2、4 倍，弱一点的 1.5、3 倍，和一根不成倍数的 7.45 倍（像逆变器、减速齿轮的啸叫，「电」的味道）。
 *       照参考量的：全油门时 4 倍只比主音低 1–2 dB、7.45 倍低 6 dB；轻踩时这两根低 12–17 dB（扭矩小，高处的阶次弱）。
 *       除了 7.45 倍都是下面八度的整数倍，彼此不拍；7.45 倍旁边没有别的线。线不抖。</li>
 *   <li><b>底子</b>：一层宽带噪声，一直到 8 kHz（参考 1–3 kHz 只比 300–1000 Hz 低 3–5 dB，3–8 kHz 低 8–16 dB），左右几乎各自独立；
 *       扭矩越大越响。宽的一层是「空气」和厚度，不是颗粒（「超音速」那里车主听出的颗粒是线两边窄窄的随机噪声）。</li>
 *   <li><b>宽度</b>：每根音左右错开一点相位（同一个频率，不拍），高处的错得多；参考左右的相关只有 0.3–0.5。参考车还让声场随横向
 *       加速度左右偏，这里没有横向加速度的信号，不做。</li>
 *   <li><b>回收</b>：松油门、刹车时电机在发电，扭矩是负的：音还在，按回收的几成，轻一些、暗一些。</li>
 *   <li><b>换挡</b>（e-Shift）：升挡断扭时电平掉一下，开头「嗞」一声（从高往低扫的短音；参考换挡时是一小串高频的「咔」）；
 *       降挡反过来，轻一点。</li>
 *   <li><b>启动、熄火</b>：从静止起转，音调从低往上走到怠速；熄火时落下去、越来越轻。</li>
 * </ul>
 *
 * <p>电平跟虚拟发动机变得比「超音速」多一点（声音跟着电机扭矩走）。音量、阀门（低通，现在一直全开）、30 Hz 高通、
 * 最后的限幅（{@link Limiter}）和别的风格一样，峰值不超过 {@link EngineVoice#CEILING}。</p>
 */
final class ElectricVoice implements Voice {

    // ---- 电机
    static final float IDLE_RPM = 900f;
    /** 主音是虚拟转速的几阶；最低到多少（Hz）；跟着转速再平一点（秒）。 */
    static final float MAIN_ORDER = 2.6f;
    static final float MIN_HZ = 20f;
    static final float LAG_SEC = 0.03f;

    // ---- 几根音：× 主音；扭矩小、满扭矩时的 dB（相对主音，中间按 dB 走）；右声道比左声道晚多少相位（周）
    static final float[] PARTIAL = {0.5f, 1f, 1.5f, 2f, 3f, 4f, 7.45f};
    static final float[] LIGHT_DB = {-7f, 0f, -24f, -8f, -20f, -16f, -24f};
    static final float[] FULL_DB = {-5f, 0f, -16f, -5f, -12f, -2f, -7f};
    static final float[] SPREAD = {0f, 0f, 0.08f, 0.1f, 0.15f, 0.18f, 0.22f};
    /** 主音的电平（dB）；高处的线从这里（Hz）开始淡出、到那里没了。 */
    static final float MAIN_DB = -8f;
    static final float FADE_FROM_HZ = 6000f;
    static final float FADE_TOP_HZ = 9000f;

    // ---- 底子：粉红噪声，高通、一阶低通压一点高处，二阶低通收尾；电平（相对主音 dB，扭矩小 / 满）；左右各自的、共用的份额
    static final float FLOOR_LIGHT_DB = -12f;
    static final float FLOOR_FULL_DB = 0f;
    static final float FLOOR_LOW_HZ = 180f;
    static final float FLOOR_TILT_HZ = 1200f;
    static final float FLOOR_TOP_HZ = 7000f;
    static final float FLOOR_OWN = 0.8f;
    static final float FLOOR_COMMON = 0.5f;

    // ---- 换挡：「嗞」（Hz 从、到；多长、多快落下去（秒）；dB，满负荷升挡）；降挡是它的几成；断扭时电平掉多少（dB，和发动机那一种
    //      一样掉 6 dB 左右）
    static final float ZAP_FROM_HZ = 3200f;
    static final float ZAP_TO_HZ = 900f;
    static final float ZAP_SEC = 0.06f;
    static final float ZAP_DECAY_SEC = 0.025f;
    static final float ZAP_ATTACK_SEC = 0.001f;
    static final float ZAP_DB = -16f;
    static final float ZAP_DOWN = 0.5f;
    static final float CUT_DIP_DB = -6f;

    // ---- 回收：发电时的扭矩按收油的几成算
    static final float REGEN_SHARE = 0.35f;

    // ---- 输出
    /** 虚拟发动机电平的这个次方；总的倍数（全油门时限幅只压两三 dB）。 */
    static final float LEVEL_POW = 0.25f;
    static final float NORM = 0.5f;

    private static final int PARTIALS = PARTIAL.length;

    private final float rate;
    private final float lagK;
    private final float ampK;
    private final float gainK;
    private final float mainGain;
    private final float[] lightGain = new float[PARTIALS];
    private final float[] lnSpan = new float[PARTIALS];
    private final float[] gainNow = new float[PARTIALS];
    private final float[] gainTarget = new float[PARTIALS];
    private final double[] phase = new double[PARTIALS];
    private float hz = -1f;
    private float startNow = -1f;

    private final float[] pinkState = new float[9];
    private final Biquad[] floorHighpass = new Biquad[2];
    private final Biquad[] floorLowpass = new Biquad[2];
    private final float[] floorTilt = new float[2];
    private final float tiltK;
    private float floorNow;

    private boolean serialSeen;
    private int lastSerial;
    private int zapPos = -1;
    private final int zapLength;
    private final int zapAttack;
    private float zapAmp;
    private double zapPhase;
    private final float zapDecay;
    private float zapEnv;
    private final double zapRatio;

    private final Biquad[] highpass = new Biquad[2];
    private final Biquad[] tone = new Biquad[2];
    private float toneSetHz = -1f;
    private final Limiter limiter;
    private float levelNow;
    private float outGainNow;
    private int noise = 0x1F83D9AB;

    ElectricVoice(float sampleRate) {
        this.rate = sampleRate;
        lagK = 1f - (float) Math.exp(-1.0 / (LAG_SEC * sampleRate));
        ampK = 1f - (float) Math.exp(-1.0 / (EngineVoice.AMP_SMOOTH_SEC * sampleRate));
        gainK = 1f - (float) Math.exp(-1.0 / (EngineVoice.GAIN_SMOOTH_SEC * sampleRate));
        mainGain = EngineVoice.dbToLin(MAIN_DB);
        float dbToLn = (float) (Math.log(10.0) / 20.0);
        for (int k = 0; k < PARTIALS; k++) {
            lightGain[k] = EngineVoice.dbToLin(LIGHT_DB[k]) * mainGain;
            lnSpan[k] = (FULL_DB[k] - LIGHT_DB[k]) * dbToLn;
            phase[k] = 0.0;
        }
        for (int c = 0; c < 2; c++) {
            floorHighpass[c] = Biquad.highpass(FLOOR_LOW_HZ, 0.7071f, sampleRate);
            floorLowpass[c] = Biquad.lowpass(Math.min(FLOOR_TOP_HZ, 0.45f * sampleRate), 0.7071f, sampleRate);
            highpass[c] = Biquad.highpass(EngineVoice.HIGHPASS_HZ, 0.7071f, sampleRate);
            tone[c] = new Biquad();
        }
        tiltK = 1f - (float) Math.exp(-2.0 * Math.PI * FLOOR_TILT_HZ / sampleRate);
        zapLength = Math.max(1, Math.round(ZAP_SEC * sampleRate));
        zapAttack = Math.max(1, Math.round(ZAP_ATTACK_SEC * sampleRate));
        zapDecay = (float) Math.exp(-1.0 / (ZAP_DECAY_SEC * sampleRate));
        zapRatio = Math.log(ZAP_TO_HZ / ZAP_FROM_HZ) / zapLength;
        limiter = new Limiter(sampleRate);
    }

    @Override
    public void renderStereo(float[] lr, int frames, VoiceParams params) {
        if (frames <= 0) {
            return;
        }
        if (params.gain <= 0f && outGainNow < 1e-5f) {
            for (int i = 0; i < 2 * frames; i++) {
                lr[i] = 0f;
            }
            outGainNow = 0f;
            limiter.reset();
            return;
        }
        float rpm = params.rpm > 0f ? params.rpm : 0f;
        float load = VirtualEngine.clamp01(params.load);
        float over = VirtualEngine.clamp01(params.overrun);
        float cut = VirtualEngine.clamp01(params.cut);
        // 扭矩：驱动时跟负荷，回收时按收油的几成（断扭只让电平掉一下，见 CUT_DIP_DB）
        float torque = Math.max(load, REGEN_SHARE * over);
        float target = Math.max(MIN_HZ, MAIN_ORDER * rpm / 60f);
        float start = rpm < IDLE_RPM ? VirtualEngine.clamp01(rpm / IDLE_RPM) : 1f;
        if (hz < 0f) {
            hz = target;
            startNow = start;
        }

        // ---- 换挡：升挡「嗞」，降挡轻一点
        if (!serialSeen) {
            serialSeen = true;
            lastSerial = params.shiftSerial;
        } else if (params.shiftSerial != lastSerial) {
            lastSerial = params.shiftSerial;
            zapPos = 0;
            zapPhase = 0.0;
            zapEnv = 1f;
            zapAmp = EngineVoice.dbToLin(ZAP_DB) * (0.3f + 0.7f * load) * (params.shiftUp ? 1f : ZAP_DOWN);
        }

        // ---- 几根音的目标增益（按这一块的扭矩、主音；逐样本平滑走过去）
        float bright = EngineVoice.dbToLin(Math.max(0f, params.brightDb));
        for (int k = 0; k < PARTIALS; k++) {
            float f = target * PARTIAL[k];
            float fade = VirtualEngine.clamp01((FADE_TOP_HZ - f) / (FADE_TOP_HZ - FADE_FROM_HZ));
            float g = lightGain[k] * (float) Math.exp(VirtualEngine.clamp01(torque) * lnSpan[k]) * fade;
            gainTarget[k] = PARTIAL[k] > 2f ? g * bright : g;
        }
        float floorTarget = mainGain * EngineVoice.dbToLin(FLOOR_LIGHT_DB + (FLOOR_FULL_DB - FLOOR_LIGHT_DB) * VirtualEngine.clamp01(torque));
        float levelTarget = (float) Math.pow(Math.max(params.level, 0f), LEVEL_POW) * NORM * EngineVoice.dbToLin(CUT_DIP_DB * cut);
        boolean toneOn = params.toneHz < EngineVoice.TONE_BYPASS_HZ;
        if (toneOn && Math.abs(params.toneHz - toneSetHz) > 1f) {
            tone[0].setLowpass(params.toneHz, 0.7071f, rate);
            tone[1].setLowpass(params.toneHz, 0.7071f, rate);
            toneSetHz = params.toneHz;
        }

        for (int i = 0; i < frames; i++) {
            hz += (target - hz) * lagK;
            startNow += (start - startNow) * gainK;
            float inc = hz / rate;

            // 几根音：左右错开一点相位
            float l = 0f;
            float r = 0f;
            for (int k = 0; k < PARTIALS; k++) {
                gainNow[k] += (gainTarget[k] - gainNow[k]) * ampK;
                double ph = phase[k] + inc * PARTIAL[k];
                ph -= Math.floor(ph);
                phase[k] = ph;
                float g = gainNow[k];
                if (g > 1e-7f) {
                    l += g * EngineVoice.sin(ph);
                    r += g * EngineVoice.sin(ph + SPREAD[k]);
                }
            }

            // 底子：左右各自的粉红噪声 + 共用的一份，高通，压一点高处，收尾
            floorNow += (floorTarget - floorNow) * ampK;
            float common = FLOOR_COMMON * pink(2, white());
            float fl = FLOOR_OWN * pink(0, white()) + common;
            float fr = FLOOR_OWN * pink(1, white()) + common;
            fl = floorHighpass[0].process(fl);
            fr = floorHighpass[1].process(fr);
            floorTilt[0] += tiltK * (fl - floorTilt[0]);
            floorTilt[1] += tiltK * (fr - floorTilt[1]);
            l += floorLowpass[0].process(0.5f * (fl + floorTilt[0])) * floorNow;
            r += floorLowpass[1].process(0.5f * (fr + floorTilt[1])) * floorNow;

            // 换挡的「嗞」（居中）
            if (zapPos >= 0) {
                double f = ZAP_FROM_HZ * Math.exp(zapRatio * Math.min(zapPos, zapLength));
                zapPhase += f / rate;
                if (zapPhase >= 1.0) {
                    zapPhase -= 1.0;
                }
                zapEnv *= zapDecay;
                float z = zapAmp * zapEnv * Math.min(1f, (zapPos + 1) / (float) zapAttack) * EngineVoice.sin(zapPhase);
                l += PulseVoice.CENTER * z;
                r += PulseVoice.CENTER * z;
                zapPos++;
                if (zapPos >= 2 * zapLength) {
                    zapPos = -1;
                }
            }

            levelNow += (levelTarget - levelNow) * gainK;
            float g = levelNow * startNow;
            l *= g;
            r *= g;
            if (toneOn) {
                l = tone[0].process(l);
                r = tone[1].process(r);
            }
            l = highpass[0].process(l);
            r = highpass[1].process(r);
            outGainNow += (params.gain - outGainNow) * gainK;
            limiter.push(l * outGainNow, r * outGainNow);
            lr[2 * i] = limiter.left;
            lr[2 * i + 1] = limiter.right;
        }
    }

    /** 此刻的主音（Hz）：测试看跟没跟上虚拟转速。 */
    float mainHz() {
        return hz;
    }

    /** 第 {@code i} 股粉红噪声往前走一个样本（Paul Kellet 的三级近似，每倍频程低 3 dB）。 */
    private float pink(int i, float w) {
        int o = 3 * i;
        pinkState[o] = 0.99765f * pinkState[o] + w * 0.0990460f;
        pinkState[o + 1] = 0.96300f * pinkState[o + 1] + w * 0.2965164f;
        pinkState[o + 2] = 0.57000f * pinkState[o + 2] + w * 1.0526913f;
        return pinkState[o] + pinkState[o + 1] + pinkState[o + 2] + w * 0.1848f;
    }

    /** 白噪声，方差 1。 */
    private float white() {
        int x = noise;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        noise = x;
        return x * (1.7320508f / 2147483648f);
    }
}
