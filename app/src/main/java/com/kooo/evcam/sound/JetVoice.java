package com.kooo.evcam.sound;

/**
 * 「超音速」风格（{@link SoundStyle#JET}，试制。项目所有者 2026-10-21：参考一款纯电高性能车的主动声浪主题，它完全是模拟出来的，
 * 结构和机械 V8 不一样）：一台双发喷气机，转速跟着虚拟发动机走，按车内喇叭播放来做。
 * 纯计算（{@code JetVoiceTest}），声音线程上一块一块地调，循环里不分配内存。量过的参考和来历在 docs/local/drive-sound-plan.md §13。
 *
 * <ul>
 *   <li><b>转子</b>：虚拟转速换成转子的「转速」（喷气机的转速主要跟油门：松油门马上往下走一截），再加一点惯性。
 *       主音怠速约 530 Hz，转满约 1200 Hz（参考：一踩 0.3 秒扫上去，松开约半秒落回来）；按虚拟转速走，所以伪挡位、
 *       模拟换挡时是锯齿。</li>
 *   <li><b>哨声</b>：左右两台发动机（音调差一点点，会「拍」），各有主音、约 0.81 倍的第二个音、转满时约 1.67 倍的高哨；
 *       转得高时再加风扇轴的「锯齿」嗡嗡声。怠速有一个约 164 Hz 的低音。</li>
 *   <li><b>喷流</b>：大部分能量在 250 Hz 以下；一踩油门先「轰」一下（低频猛地大 14 dB），松油门小一点；转得越高，2–8 kHz 的气声越多。</li>
 *   <li><b>换挡</b>：升挡时一声「音爆」（N 形的一下 + 低频的一捶）；松油门、转子往下掉时「呼」一声。</li>
 *   <li><b>启动</b>：从静止起转，音调从低往上走到怠速。</li>
 * </ul>
 *
 * <p>电平跟虚拟发动机，但范围窄一些（怠速的哨声也要听得到）。音量、阀门（接入时的低通）、30 Hz 高通、软限幅和别的风格一样，
 * 峰值不超过 {@link EngineVoice#CEILING}。没有变速箱、倒挡的啸叫。</p>
 */
final class JetVoice implements Voice {

    // ---- 转子
    static final float IDLE_RPM = 900f;
    /** 虚拟转速到这里算转满（D 挡）；P / N 里虚拟发动机只转到 4500，到那里就算满。 */
    static final float FULL_RPM = 6200f;
    static final float FREE_FULL_RPM = 4500f;
    /** 转子 = ((rpm − 怠速) / (满 − 怠速))^它：前半段走得快。 */
    static final float SPOOL_CURVE = 0.7f;
    /** 怠速、转满时的主音（Hz）。 */
    static final float IDLE_HZ = 530f;
    static final float TOP_HZ = 1200f;
    /** 低于怠速（起转）时主音 × (rpm / 怠速)^它。 */
    static final float BELOW_IDLE_POW = 0.5f;
    /** 转子跟虚拟转速多出来的惯性（秒，上、下）。 */
    static final float LAG_UP_SEC = 0.1f;
    static final float LAG_DOWN_SEC = 0.1f;
    /** 转子跟油门的份额：目标 × (1 − 它 + 它 × 负荷)。 */
    static final float LOAD_WEIGHT = 0.25f;
    /** 电平范围比活塞发动机窄（怠速的哨声也听得到）：线性电平的这个次方。参考：踩满持续时比怠速大 8–12 dB，起「轰」那一下 14–18 dB。 */
    static final float LEVEL_POW = 0.5f;

    // ---- 哨声：每台发动机三个音（× 主音、dB、转子到多少才出来）
    static final float[] PARTIAL_RATIO = {1f, 0.81f, 1.67f};
    static final float[] PARTIAL_DB = {0f, -5f, -16f};
    static final float[] PARTIAL_FROM = {0f, 0f, 0.75f};
    /** 转满、满推力时主音的电平（相对满推力的喷流）；怠速时还剩多少（dB）。 */
    static final float WHINE_DB = -15f;
    static final float WHINE_IDLE_DB = -6f;
    /** 两台发动机：主音 × (1 ∓ 它)；摆在左右多远。 */
    static final float DETUNE = 0.003f;
    static final float ENGINE_PAN = 0.35f;
    /** 低音：怠速时的频率、转满时的倍数、电平（dB）。 */
    static final float LOW_HZ = 164f;
    static final float LOW_TOP = 1.25f;
    static final float LOW_DB = -12f;
    /** 风扇轴的「锯齿」：轴的频率 = 主音 / 叶片数，带通、电平、转子到多少才出来。 */
    static final float BUZZ_BLADES = 18f;
    static final float BUZZ_HZ = 900f;
    static final float BUZZ_Q = 0.8f;
    static final float BUZZ_DB = -18f;
    static final float BUZZ_FROM = 0.6f;

    // ---- 喷流
    static final float ROAR_HZ = 90f;
    static final float ROAR_Q = 0.7f;
    static final float ROAR_LOWPASS_HZ = 250f;
    /** 怠速时的喷流（相对满推力，dB）；喷流噪声的总倍数。 */
    static final float ROAR_IDLE_DB = -14f;
    static final float ROAR_GAIN = 3f;
    /** 两边共用的那一份、各自的那一份：大半是同一股喷流，左右只差一点。 */
    static final float ROAR_COMMON = 0.8f;
    static final float ROAR_OWN = 0.6f;
    /** 一踩油门的「轰」：多快落下去（秒）、满踩时多大（dB）、松油门时是踩下时的几成；负荷每块变多少算满。 */
    static final float WHOOMP_SEC = 0.15f;
    static final float WHOOMP_DB = 14f;
    static final float WHOOMP_RELEASE = 0.5f;
    static final float WHOOMP_PER_LOAD = 6f;
    /** 气声：带（Hz）、转满满推力时的电平（dB）、怠速时还剩多少（dB）。 */
    static final float AIR_LOW_HZ = 2000f;
    static final float AIR_HIGH_HZ = 8000f;
    static final float AIR_DB = -22f;
    static final float AIR_IDLE_DB = -24f;

    // ---- 换挡、松油门
    /** 「音爆」：N 形那一下多长（秒）、多大（dB）；跟着的一捶（Hz、多快落下去、dB）。 */
    static final float BOOM_SEC = 0.06f;
    static final float BOOM_DB = -6f;
    static final float BOOM_LOWPASS_HZ = 2000f;
    static final float THUMP_HZ = 45f;
    static final float THUMP_SEC = 0.12f;
    static final float THUMP_DB = -8f;
    /** 升挡时也「轰」一下（满负荷时是满踩的几成）。 */
    static final float SHIFT_WHOOMP = 0.6f;
    /** 松油门转子往下掉的「呼」：带通、电平、多快淡掉；负荷一块掉多少、转子在多少以上才有。 */
    static final float WHOOSH_HZ = 900f;
    static final float WHOOSH_Q = 1f;
    static final float WHOOSH_DB = -20f;
    static final float WHOOSH_SEC = 0.6f;
    static final float WHOOSH_DROP = 0.15f;
    static final float WHOOSH_FROM = 0.3f;
    /** 断扭时推力少几成。 */
    static final float CUT_THRUST = 0.7f;
    /**
     * 总的倍数：只管别顶到限幅 —— 满推力、一踩一松时峰值和「美式 V8」满油门一样留在限幅以内。不和别的风格比响度
     * （项目所有者 2026-10-21：两种取向各自对各自的原型），多响由声浪音量定。
     */
    static final float NORM = 1.68f;

    private static final float LN2 = 0.6931472f;
    private static final float FULL_OCTAVES = (float) (Math.log(TOP_HZ / IDLE_HZ) / Math.log(2.0));
    private static final float[] PARTIAL_GAIN = gains(PARTIAL_DB);
    private static final float BUZZ_GAIN = EngineVoice.dbToLin(BUZZ_DB);
    private static final float LOW_GAIN = EngineVoice.dbToLin(LOW_DB);
    private static final float WHOOSH_GAIN = EngineVoice.dbToLin(WHOOSH_DB);

    private final float rate;
    private final float lagUpK;
    private final float lagDownK;
    private final float whoompDecay;
    private final float whooshDecay;
    private final float thumpDecay;
    private final float ampK;
    private final float gainK;

    /** 转子：log2(主音 / 怠速主音)。 */
    private float spool;
    private boolean started;
    private final double[][] partialPhase = new double[2][PARTIAL_RATIO.length];
    private final double[] buzzPhase = new double[2];
    private double lowPhase;
    private final Biquad[] buzzBand = new Biquad[2];
    private final Biquad[] roarBand = new Biquad[3];
    private final Biquad[] roarLowpass = new Biquad[3];
    private final Biquad[] airHighpass = new Biquad[2];
    private final Biquad[] airLowpass = new Biquad[2];
    private final Biquad whooshBand;
    private final Biquad boomLowpass;
    private float whoomp;
    private float whoosh;
    private float loadPrev;
    private int boomPos = -1;
    private int boomLength;
    private float boomAmp;
    private float thump;
    private double thumpPhase;
    private boolean serialSeen;
    private int lastSerial;
    private final float engineNear;
    private final float engineFar;

    private final Biquad[] highpass = new Biquad[2];
    private final Biquad[] tone = new Biquad[2];
    private float toneSetHz = -1f;
    private float levelNow;
    private float gainNow;
    private int noise = 0x6D2B79F5;

    JetVoice(float sampleRate) {
        this.rate = sampleRate;
        lagUpK = 1f - (float) Math.exp(-1.0 / (LAG_UP_SEC * sampleRate));
        lagDownK = 1f - (float) Math.exp(-1.0 / (LAG_DOWN_SEC * sampleRate));
        whoompDecay = perSample(WHOOMP_SEC);
        whooshDecay = perSample(WHOOSH_SEC);
        thumpDecay = perSample(THUMP_SEC);
        ampK = 1f - (float) Math.exp(-1.0 / (EngineVoice.AMP_SMOOTH_SEC * sampleRate));
        gainK = 1f - (float) Math.exp(-1.0 / (EngineVoice.GAIN_SMOOTH_SEC * sampleRate));
        for (int c = 0; c < 2; c++) {
            buzzBand[c] = Biquad.bandpass(BUZZ_HZ, BUZZ_Q, sampleRate);
            airHighpass[c] = Biquad.highpass(AIR_LOW_HZ, 0.7071f, sampleRate);
            airLowpass[c] = Biquad.lowpass(AIR_HIGH_HZ, 0.7071f, sampleRate);
            highpass[c] = Biquad.highpass(EngineVoice.HIGHPASS_HZ, 0.7071f, sampleRate);
            tone[c] = new Biquad();
        }
        for (int c = 0; c < 3; c++) {
            roarBand[c] = Biquad.bandpass(ROAR_HZ, ROAR_Q, sampleRate);
            roarLowpass[c] = Biquad.lowpass(ROAR_LOWPASS_HZ, 0.7071f, sampleRate);
        }
        whooshBand = Biquad.bandpass(WHOOSH_HZ, WHOOSH_Q, sampleRate);
        boomLowpass = Biquad.lowpass(BOOM_LOWPASS_HZ, 0.7071f, sampleRate);
        boomLength = Math.max(1, Math.round(BOOM_SEC * sampleRate));
        engineNear = PulseVoice.CENTER_PAN * PulseVoice.panLeft(-ENGINE_PAN);
        engineFar = PulseVoice.CENTER_PAN * PulseVoice.panRight(-ENGINE_PAN);
    }

    @Override
    public void renderStereo(float[] lr, int frames, VoiceParams params) {
        if (frames <= 0) {
            return;
        }
        if (params.gain <= 0f && gainNow < 1e-5f) {
            for (int i = 0; i < 2 * frames; i++) {
                lr[i] = 0f;
            }
            gainNow = 0f;
            return;
        }
        float rpm = params.rpm > 0f ? params.rpm : 0f;
        float load = VirtualEngine.clamp01(params.load);
        float thrust = load * (1f - CUT_THRUST * VirtualEngine.clamp01(params.cut));
        float target = spoolTarget(rpm, params.drive ? FULL_RPM : FREE_FULL_RPM);
        if (target > 0f) {
            target *= 1f - LOAD_WEIGHT + LOAD_WEIGHT * load;
        }
        if (!started) {
            spool = target;
            started = true;
        }
        float lagK = target > spool ? lagUpK : lagDownK;
        // 从静止起转：音量跟着转速起来
        float start = rpm < IDLE_RPM ? VirtualEngine.clamp01(rpm / IDLE_RPM) : 1f;

        // ---- 一踩、一松的「轰」，松油门的「呼」
        float dLoad = load - loadPrev;
        loadPrev = load;
        if (dLoad > 0f) {
            whoomp = Math.max(whoomp, Math.min(1f, dLoad * WHOOMP_PER_LOAD));
        } else if (dLoad < 0f) {
            whoomp = Math.max(whoomp, Math.min(1f, -dLoad * WHOOMP_PER_LOAD) * WHOOMP_RELEASE);
        }
        float nowSpool = VirtualEngine.clamp01(spool / FULL_OCTAVES);
        if (dLoad < -WHOOSH_DROP && nowSpool > WHOOSH_FROM) {
            whoosh = Math.max(whoosh, nowSpool);
        }

        // ---- 换挡：升挡「音爆」+ 一捶 + 「轰」
        if (!serialSeen) {
            serialSeen = true;
            lastSerial = params.shiftSerial;
        } else if (params.shiftSerial != lastSerial) {
            lastSerial = params.shiftSerial;
            float amp = 0.3f + 0.7f * load;
            if (params.shiftUp) {
                boomPos = 0;
                boomAmp = EngineVoice.dbToLin(BOOM_DB) * amp;
                thump = EngineVoice.dbToLin(THUMP_DB) * amp;
                thumpPhase = 0.0;
            }
            whoomp = Math.max(whoomp, SHIFT_WHOOMP * amp);
        }

        float whineDb = EngineVoice.dbToLin(WHINE_DB);
        float whineIdle = EngineVoice.dbToLin(WHINE_IDLE_DB);
        float roarIdle = EngineVoice.dbToLin(ROAR_IDLE_DB);
        float whoompGain = EngineVoice.dbToLin(WHOOMP_DB) - 1f;
        float airIdle = EngineVoice.dbToLin(AIR_IDLE_DB);
        float airDb = EngineVoice.dbToLin(AIR_DB);
        float levelTarget = (float) Math.pow(Math.max(params.level, 0f), LEVEL_POW);
        boolean toneOn = params.toneHz < EngineVoice.TONE_BYPASS_HZ;
        if (toneOn && Math.abs(params.toneHz - toneSetHz) > 1f) {
            tone[0].setLowpass(params.toneHz, 0.7071f, rate);
            tone[1].setLowpass(params.toneHz, 0.7071f, rate);
            toneSetHz = params.toneHz;
        }

        for (int i = 0; i < frames; i++) {
            spool += (target - spool) * lagK;
            float s = VirtualEngine.clamp01(spool / FULL_OCTAVES);
            float main = IDLE_HZ * (float) Math.exp(spool * LN2);

            // 哨声：两台发动机，各三个音 + 转得高时的「锯齿」
            float whineLevel = whineDb * (whineIdle + (1f - whineIdle) * s) * (0.6f + 0.4f * thrust) * start;
            float buzz = VirtualEngine.clamp01((s - BUZZ_FROM) / (1f - BUZZ_FROM)) * BUZZ_GAIN * thrust * start;
            float e0 = engine(0, main * (1f - DETUNE), s, whineLevel, buzz);
            float e1 = engine(1, main * (1f + DETUNE), s, whineLevel, buzz);

            // 低音
            lowPhase += LOW_HZ * (1f + (LOW_TOP - 1f) * s) / rate;
            if (lowPhase >= 1.0) {
                lowPhase -= 1.0;
            }
            float low = LOW_GAIN * EngineVoice.sin(lowPhase) * start;

            // 喷流：大半是同一股，左右各自一点
            whoomp *= whoompDecay;
            float roarLevel = (roarIdle + (1f - roarIdle) * thrust * (0.4f + 0.6f * s)) * (1f + whoompGain * whoomp)
                    * start * ROAR_GAIN;
            float common = roarLowpass[2].process(roarBand[2].process(white()));
            float roarL = (ROAR_COMMON * common + ROAR_OWN * roarLowpass[0].process(roarBand[0].process(white()))) * roarLevel;
            float roarR = (ROAR_COMMON * common + ROAR_OWN * roarLowpass[1].process(roarBand[1].process(white()))) * roarLevel;

            // 气声
            float airLevel = airDb * (airIdle + (1f - airIdle) * s * s) * (0.3f + 0.7f * thrust) * start;
            float airL = airLowpass[0].process(airHighpass[0].process(white())) * airLevel;
            float airR = airLowpass[1].process(airHighpass[1].process(white())) * airLevel;

            // 「呼」、「音爆」、一捶
            whoosh *= whooshDecay;
            float middle = low + whooshBand.process(white()) * WHOOSH_GAIN * whoosh;
            float n = 0f;
            if (boomPos >= 0) {
                n = boomAmp * (1f - 2f * boomPos / (float) boomLength);
                boomPos++;
                if (boomPos >= boomLength) {
                    boomPos = -1;
                }
            }
            middle += boomLowpass.process(n);
            if (thump > 1e-7f) {
                thump *= thumpDecay;
                thumpPhase += THUMP_HZ / rate;
                if (thumpPhase >= 1.0) {
                    thumpPhase -= 1.0;
                }
                middle += thump * EngineVoice.sin(thumpPhase);
            }

            float l = engineNear * e0 + engineFar * e1 + roarL + airL + PulseVoice.CENTER * middle;
            float r = engineFar * e0 + engineNear * e1 + roarR + airR + PulseVoice.CENTER * middle;
            levelNow += (levelTarget - levelNow) * gainK;
            float g = levelNow * NORM;
            l *= g;
            r *= g;
            if (toneOn) {
                l = tone[0].process(l);
                r = tone[1].process(r);
            }
            l = highpass[0].process(l);
            r = highpass[1].process(r);
            gainNow += (params.gain - gainNow) * gainK;
            lr[2 * i] = EngineVoice.limit(l * gainNow);
            lr[2 * i + 1] = EngineVoice.limit(r * gainNow);
        }
    }

    /** 一台发动机这一个样本：三个音（高哨要转得高才出来）+ 风扇轴的「锯齿」过带通。 */
    private float engine(int e, float f0, float s, float whineLevel, float buzz) {
        float y = 0f;
        for (int k = 0; k < PARTIAL_RATIO.length; k++) {
            partialPhase[e][k] += f0 * PARTIAL_RATIO[k] / rate;
            if (partialPhase[e][k] >= 1.0) {
                partialPhase[e][k] -= 1.0;
            }
            float on = PARTIAL_FROM[k] > 0f ? VirtualEngine.clamp01((s - PARTIAL_FROM[k]) / (1f - PARTIAL_FROM[k])) : 1f;
            y += PARTIAL_GAIN[k] * on * EngineVoice.sin(partialPhase[e][k]);
        }
        buzzPhase[e] += f0 / BUZZ_BLADES / rate;
        if (buzzPhase[e] >= 1.0) {
            buzzPhase[e] -= 1.0;
        }
        float saw = (float) (2.0 * buzzPhase[e] - 1.0);
        return y * whineLevel + buzzBand[e].process(saw) * buzz;
    }

    /** 转子的目标：log2(主音 / 怠速主音)；低于怠速（起转）是负的。 */
    private static float spoolTarget(float rpm, float fullRpm) {
        if (rpm < IDLE_RPM) {
            return BELOW_IDLE_POW * (float) (Math.log(Math.max(rpm, 30f) / IDLE_RPM) / Math.log(2.0));
        }
        float x = VirtualEngine.clamp01((rpm - IDLE_RPM) / (fullRpm - IDLE_RPM));
        return (float) Math.pow(x, SPOOL_CURVE) * FULL_OCTAVES;
    }

    private static float[] gains(float[] db) {
        float[] out = new float[db.length];
        for (int k = 0; k < db.length; k++) {
            out[k] = EngineVoice.dbToLin(db[k]);
        }
        return out;
    }

    /** 此刻的主音（Hz）：测试看转子跟没跟上。 */
    float mainHz() {
        return IDLE_HZ * (float) Math.exp(spool * LN2);
    }

    private float perSample(float tauSec) {
        return (float) Math.exp(-1.0 / (Math.max(tauSec, 1e-6f) * rate));
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
