package com.kooo.evcam.sound;

/**
 * 「经典 V8」风格（{@link SoundStyle#CLASSIC}，第一版）：把虚拟发动机（{@link VirtualEngine}）的转速、负荷、电平和几样音色参数
 * 变成一串单声道样本，左右声道一样。纯计算（{@code EngineVoiceTest}），声音线程上一块一块地调，循环里不分配内存。
 *
 * <ul>
 *   <li><b>阶次</b>：所有阶次共用一个曲轴相位（两转一个周期，半阶次也接得上），转速怎么变相位都连着走 —— 不出「咔哒」。
 *       每个阶次的幅度按负荷变（{@link SoundProfile#orderLoadDb}），高阶次在急踩、猛加速时再亮一点，半阶次在急松油门时多一点。</li>
 *   <li><b>进气声</b>：白噪声过带通，中心跟着转速；<b>排气</b>：白噪声过低通，负荷大时、急松油门的突突（随机起伏）走这一路。</li>
 *   <li><b>起动机</b>（启动时）：电机的啸声 + 齿轮的沙沙声，按压缩冲程一顿一顿；<b>倒挡齿轮</b>的啸叫（倒挡时，音调跟着车速）。</li>
 *   <li>两个固定共振（峰值均衡）、30 Hz 高通（保护喇叭）、接入时的阀门（低通慢慢打开）。</li>
 *   <li>电平、音量、转速逐样本平滑；最后软限幅，峰值不超过 {@link #CEILING}（−3 dBFS）。</li>
 * </ul>
 */
final class EngineVoice implements Voice {

    /** 峰值上限：−3 dBFS。 */
    static final float CEILING = 0.708f;
    /** 阶次频率到这里（Hz）开始渐隐，到 {@link #BAND_TOP_HZ} 没了：车内喇叭放得出，也不会混叠。 */
    static final float BAND_FADE_HZ = 4500f;
    static final float BAND_TOP_HZ = 6000f;
    static final float HIGHPASS_HZ = 30f;
    /** 起动机那一层（相对满负荷的点火阶次，dB）、电机啸声的频率。 */
    static final float STARTER_DB = -16f;
    static final float STARTER_WHINE_HZ = 1050f;
    /** 倒挡齿轮的啸叫（dB）。 */
    static final float WHINE_DB = -20f;
    /** 急松油门时半阶次多加多少（dB）、排气那一路的突突多响（dB）、随机起伏多快（Hz）。 */
    static final float BURBLE_HALF_ORDER_DB = 8f;
    static final float BURBLE_DB = -10f;
    static final float BURBLE_RATE_HZ = 22f;
    /** 排气那一路的低通（Hz）。 */
    static final float EXHAUST_LOWPASS_HZ = 300f;
    /** 阀门低通高于这个（Hz）就当全开，不滤。 */
    static final float TONE_BYPASS_HZ = 16000f;
    static final float AMP_SMOOTH_SEC = 0.005f;
    static final float GAIN_SMOOTH_SEC = 0.010f;
    /** 白噪声过带通 / 低通之后补回的倍数：让它的有效值和同样电平的正弦差不多。 */
    static final float INTAKE_NOISE_NORM = 4f;
    static final float EXHAUST_NOISE_NORM = 8f;

    private static final int TABLE = 4096;
    private static final float[] SINE = sineTable();

    private final SoundProfile p;
    private final float rate;
    private final int n;
    /** 每个阶次（不含负荷）的线性幅度，已按满负荷时全部加起来的峰值归一（同相相加也不超过 1）。 */
    private final float[] baseAmp;
    /** 每个阶次固定的相位偏移（周）：错开峰值。 */
    private final float[] orderPhase;
    private final boolean[] halfOrder;
    private final boolean[] highOrder;
    private final float[] amp;
    private final float[] ampNow;
    private final float intakeUnit;
    private final float exhaustUnit;
    private final float ampK;
    private final float gainK;

    private double crank;
    private float rpmNow = -1f;
    private float levelNow;
    private float gainNow;
    private float intakeNow;
    private float exhaustNow;
    private float burbleNow;
    private float starterNow;
    private float whineNow;
    private double starterPhase;
    private double whinePhase;
    private int noise = 0x2545F491;
    // 进气带通、排气低通：两个状态变量滤波器（TPT SVF）
    private float in1;
    private float in2;
    private float ex1;
    private float ex2;
    private final float exA1;
    private final float exA2;
    private final float exA3;
    // 突突的随机起伏
    private float gate;
    private float gateTarget;
    private int gateLeft;
    private final int gateSamples;
    private final float gateK;
    // 均衡
    private final Biquad[] resonances;
    private final Biquad highpass;
    private final Biquad tone = new Biquad();
    private float toneSetHz = -1f;
    /** {@link #renderStereo} 先合成到这里再复制到左右（第一次用时按块大小分配）。 */
    private float[] mono = new float[0];

    EngineVoice(SoundProfile profile, float sampleRate) {
        this.p = profile;
        this.rate = sampleRate;
        this.n = profile.orders.length;
        baseAmp = new float[n];
        orderPhase = new float[n];
        halfOrder = new boolean[n];
        highOrder = new boolean[n];
        amp = new float[n];
        ampNow = new float[n];
        float peak = 0f;
        for (int k = 0; k < n; k++) {
            peak += dbToLin(profile.orderDb[k] + profile.orderLoadDb[k]);
        }
        float unit = peak > 0f ? 1f / peak : 1f;
        for (int k = 0; k < n; k++) {
            baseAmp[k] = dbToLin(profile.orderDb[k]) * unit;
            // 黄金比例错开相位，几个阶次不会同时冲到峰值
            orderPhase[k] = (float) ((k * 0.6180339887) % 1.0);
            float order = profile.orders[k];
            halfOrder[k] = Math.abs(order - Math.floor(order) - 0.5) < 1e-3;
            highOrder[k] = order >= 6f;
        }
        intakeUnit = dbToLin(profile.intakeDb) * unit * INTAKE_NOISE_NORM;
        exhaustUnit = dbToLin(profile.exhaustDb) * unit * EXHAUST_NOISE_NORM;
        ampK = 1f - (float) Math.exp(-1.0 / (AMP_SMOOTH_SEC * sampleRate));
        gainK = 1f - (float) Math.exp(-1.0 / (GAIN_SMOOTH_SEC * sampleRate));
        float g = (float) Math.tan(Math.PI * EXHAUST_LOWPASS_HZ / sampleRate);
        float k = 1.4142135f;
        exA1 = 1f / (1f + g * (g + k));
        exA2 = g * exA1;
        exA3 = g * exA2;
        gateSamples = Math.max(1, Math.round(sampleRate / BURBLE_RATE_HZ));
        gateK = 1f - (float) Math.exp(-3.0 / gateSamples);
        resonances = new Biquad[profile.resonanceHz.length];
        for (int i = 0; i < resonances.length; i++) {
            resonances[i] = Biquad.peaking(profile.resonanceHz[i], profile.resonanceDb[i], profile.resonanceQ[i], sampleRate);
        }
        highpass = Biquad.highpass(HIGHPASS_HZ, 0.7071f, sampleRate);
    }

    @Override
    public void renderStereo(float[] lr, int frames, VoiceParams params) {
        if (mono.length < frames) {
            mono = new float[frames];
        }
        render(mono, 0, frames, params);
        for (int i = 0; i < frames; i++) {
            lr[2 * i] = mono[i];
            lr[2 * i + 1] = mono[i];
        }
    }

    /**
     * 合成一块（{@code frames} 个单声道样本）写进 {@code out}。参数在块里逐样本平滑过去。
     */
    void render(float[] out, int offset, int frames, VoiceParams params) {
        if (frames <= 0) {
            return;
        }
        float rpmTarget = params.rpm > 0f ? params.rpm : 0f;
        if (rpmNow < 0f) {
            rpmNow = rpmTarget;
        }
        if (params.gain <= 0f && gainNow < 1e-5f) {
            // 静音着：不算，相位停在原地（重新出声时从这里接着走）
            for (int i = 0; i < frames; i++) {
                out[offset + i] = 0f;
            }
            gainNow = 0f;
            rpmNow = rpmTarget;
            return;
        }
        float load = VirtualEngine.clamp01(params.load);
        // 这一块里每个阶次该有的幅度
        for (int k = 0; k < n; k++) {
            float order = p.orders[k];
            float db = p.orderLoadDb[k] * load;
            if (highOrder[k]) {
                db += params.brightDb;
            }
            if (halfOrder[k]) {
                db += BURBLE_HALF_ORDER_DB * params.burble;
            }
            amp[k] = baseAmp[k] * dbToLin(db) * bandWeight(order * rpmTarget / 60f);
        }
        // 进气带通：中心跟着转速，系数每块算一次
        float center = VirtualEngine.clamp(p.intakeCenterOrder * rpmTarget / 60f, 200f, 6000f);
        float ig = (float) Math.tan(Math.PI * center / rate);
        float ik = 1f / p.intakeQ;
        float inA1 = 1f / (1f + ig * (ig + ik));
        float inA2 = ig * inA1;
        float inA3 = ig * inA2;
        float intakeTarget = intakeUnit * (float) Math.pow(load, 1.5) * dbToLin(params.intakeBoostDb);
        float exhaustTarget = exhaustUnit * load * load;
        float burbleTarget = exhaustUnit * dbToLin(BURBLE_DB - p.exhaustDb) * VirtualEngine.clamp01(params.burble);
        float starterTarget = dbToLin(STARTER_DB) * VirtualEngine.clamp01(params.starter);
        float whineTarget = dbToLin(WHINE_DB) * VirtualEngine.clamp01(params.whineAmp);
        boolean toneOn = params.toneHz < TONE_BYPASS_HZ;
        if (toneOn && Math.abs(params.toneHz - toneSetHz) > 1f) {
            tone.setLowpass(params.toneHz, 0.7071f, rate);
            toneSetHz = params.toneHz;
        }
        float rpmStep = (rpmTarget - rpmNow) / frames;
        float levelTarget = params.level;
        float gainTarget = params.gain;
        for (int i = 0; i < frames; i++) {
            float r = rpmNow + rpmStep * (i + 1);
            crank += r / 60.0 / rate;
            if (crank >= 2.0) {
                crank -= 2.0;
            }
            float engine = 0f;
            for (int k = 0; k < n; k++) {
                ampNow[k] += (amp[k] - ampNow[k]) * ampK;
                engine += ampNow[k] * sin(p.orders[k] * crank + orderPhase[k]);
            }
            // 进气
            float w = nextNoise();
            float v3 = w - in2;
            float v1 = inA1 * in1 + inA2 * v3;
            float v2 = in2 + inA2 * in1 + inA3 * v3;
            in1 = 2f * v1 - in1;
            in2 = 2f * v2 - in2;
            intakeNow += (intakeTarget - intakeNow) * ampK;
            engine += intakeNow * ik * v1;
            // 排气（低通）+ 急松油门的突突
            float w2 = nextNoise();
            float e3 = w2 - ex2;
            float e1 = exA1 * ex1 + exA2 * e3;
            float e2 = ex2 + exA2 * ex1 + exA3 * e3;
            ex1 = 2f * e1 - ex1;
            ex2 = 2f * e2 - ex2;
            if (--gateLeft <= 0) {
                gateLeft = gateSamples;
                gateTarget = (nextNoise() + 1f) * 0.5f;
                gateTarget *= gateTarget;
            }
            gate += (gateTarget - gate) * gateK;
            exhaustNow += (exhaustTarget - exhaustNow) * ampK;
            burbleNow += (burbleTarget - burbleNow) * ampK;
            engine += e2 * (exhaustNow + burbleNow * gate);
            levelNow += (levelTarget - levelNow) * gainK;
            float s = engine * levelNow;
            // 起动机：电机啸声 + 齿轮沙沙声，按压缩冲程（点火阶次）一顿一顿
            starterNow += (starterTarget - starterNow) * ampK;
            if (starterNow > 1e-5f) {
                starterPhase += STARTER_WHINE_HZ * (0.8 + 0.2 * starterNow) / rate;
                if (starterPhase >= 1.0) {
                    starterPhase -= 1.0;
                }
                float pulse = 0.55f + 0.45f * sin(4.0 * crank);
                float buzz = sin(starterPhase) + 0.35f * sin(2.0 * starterPhase) + 0.25f * nextNoise();
                s += starterNow * pulse * buzz;
            }
            // 倒挡齿轮的啸叫
            whineNow += (whineTarget - whineNow) * ampK;
            if (whineNow > 1e-5f && params.whineHz > 0f) {
                whinePhase += params.whineHz / rate;
                if (whinePhase >= 1.0) {
                    whinePhase -= 1.0;
                }
                s += whineNow * (sin(whinePhase) + 0.3f * sin(2.0 * whinePhase));
            }
            for (Biquad b : resonances) {
                s = b.process(s);
            }
            s = highpass.process(s);
            if (toneOn) {
                s = tone.process(s);
            }
            gainNow += (gainTarget - gainNow) * gainK;
            out[offset + i] = limit(s * gainNow);
        }
        rpmNow = rpmTarget;
    }

    /** 阶次频率太高的渐隐（Hz）。 */
    static float bandWeight(float hz) {
        if (hz <= BAND_FADE_HZ) {
            return 1f;
        }
        if (hz >= BAND_TOP_HZ) {
            return 0f;
        }
        return (BAND_TOP_HZ - hz) / (BAND_TOP_HZ - BAND_FADE_HZ);
    }

    /** 软限幅：小信号原样，大信号平滑地压到 {@link #CEILING} 以内（tanh 的有理近似，单调）。 */
    static float limit(float x) {
        float y = x / CEILING;
        float t;
        if (y >= 3f) {
            t = 1f;
        } else if (y <= -3f) {
            t = -1f;
        } else {
            float y2 = y * y;
            t = y * (27f + y2) / (27f + 9f * y2);
        }
        return t * CEILING;
    }

    static float dbToLin(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    /** 正弦，参数是「周」（1 = 一整圈），查表 + 线性插值。 */
    static float sin(double cycles) {
        double x = cycles - Math.floor(cycles);
        float pos = (float) (x * TABLE);
        int i = (int) pos;
        if (i >= TABLE) {
            i = TABLE - 1;
        }
        float frac = pos - i;
        return SINE[i] + (SINE[i + 1] - SINE[i]) * frac;
    }

    private static float[] sineTable() {
        float[] t = new float[TABLE + 1];
        for (int i = 0; i <= TABLE; i++) {
            t[i] = (float) Math.sin(2.0 * Math.PI * i / TABLE);
        }
        return t;
    }

    /** 白噪声：xorshift32，[−1, 1)。可复现（测试靠它）。 */
    private float nextNoise() {
        int x = noise;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        noise = x;
        return x * (1f / 2147483648f);
    }
}
