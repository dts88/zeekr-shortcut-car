package com.kooo.evcam.sound;

/**
 * 「超音速」风格（{@link SoundStyle#JET}，试制第二版。项目所有者 2026-10-21：参考一款纯电高性能车的主动声浪主题，它完全是模拟出来的；
 * 第一版「差距十分巨大，基本需要重做」）：照参考的整张频谱图重做，按车内喇叭播放来做。
 * 纯计算（{@code JetVoiceTest}），声音线程上一块一块地调，循环里不分配内存。量过的参考在 docs/local/drive-sound-plan.md §13。
 *
 * <ul>
 *   <li><b>转子</b>：停着时跟油门（喷气机的转速跟油门走：一踩 0.3 秒升上去，一松约半秒落回怠速）；挂 D 挡跟虚拟转速
 *       （伪挡位、模拟换挡时是锯齿），一踩先往上赶，松油门马上低一截。主音怠速约 533 Hz，转满约 1190 Hz。</li>
 *   <li><b>哨声</b>：一扇平行的线跟着转子一起走（相对主音 0.42、0.64、0.74、0.81、0.83、0.95、1、1.06，转满时还有很淡的 1.68），
 *       每根是相差 1.3 % 的一对（每秒「拍」十几下，粗糙、发颤），各自带一点随机的抖动；左右两台，差一点点。</li>
 *   <li><b>低音</b>：三根（88 → 140、76 → 120、165 → 215 Hz），也成对、抖得更宽，转满时是最响的。</li>
 *   <li><b>喷流</b>：250 Hz 以下的噪声，大半是两边共用的一股，随推力长；一踩油门（负荷比它最近的平均高出一截）「轰」一下 ——
 *       一段很大的低频噪声，约 0.2 秒；一松也「轰」，小一些。</li>
 *   <li><b>气声</b>：线之间 0.4–1.6 kHz 的气声、1.5–4 kHz 的嘶声，跟着转子长，一闪一闪（斑点状）。</li>
 *   <li><b>换挡</b>：升挡一声「音爆」（N 形的一下 + 45 Hz 的一捶）加一点「轰」；降挡只「轰」一点。松油门转子往下掉时「呼」一声。</li>
 *   <li><b>启动</b>：从静止起转，音调从低往上走到怠速。</li>
 * </ul>
 *
 * <p>电平几乎不跟虚拟发动机变（参考里踩住油门并不比怠速响多少，变化靠低音、喷流随推力长和一踩一松的「轰」）。
 * 音量、阀门（接入时的低通）、30 Hz 高通、最后的限幅（{@link Limiter}）和别的风格一样，峰值不超过 {@link EngineVoice#CEILING}。
 * 不和别的风格比响度（项目所有者 2026-10-21：两种取向各自对各自的原型）。
 * 抖动、哨声的电平、低音的音调变得慢，每 16 个样本才算一次。</p>
 */
final class JetVoice implements Voice {

    // ---- 转子
    static final float IDLE_RPM = 900f;
    /** 挂着挡时虚拟转速到这里算转满（停着时转子跟油门，不看转速）。 */
    static final float FULL_RPM = 6200f;
    /** 转子 = ((rpm − 怠速) / (满 − 怠速))^它：前半段走得快。 */
    static final float SPOOL_CURVE = 0.7f;
    /** 怠速、转满时的主音（Hz）。 */
    static final float IDLE_HZ = 533f;
    static final float TOP_HZ = 1190f;
    /** 低于怠速（起转）时主音 × (rpm / 怠速)^它。 */
    static final float BELOW_IDLE_POW = 0.5f;
    /** 挂着挡：一踩先往「负荷 × 它」赶，不等虚拟转速；目标再 × (1 − 份额 + 份额 × 负荷)，松油门马上低一截。 */
    static final float LOAD_LEAD = 0.7f;
    static final float LOAD_WEIGHT = 0.6f;
    /** 停着：转子 = 负荷^它（平方：负荷松开时那段拖尾很快就没了）。 */
    static final float FREE_CURVE = 2f;
    /** 转子的惯性（秒，上、下）。 */
    static final float LAG_UP_SEC = 0.14f;
    static final float LAG_DOWN_SEC = 0.16f;

    // ---- 哨声：一扇线（× 主音，转满时 dB，怠速时 dB，中间按 dB 走），每根一对
    static final float[] FAN_RATIO = {0.42f, 0.64f, 0.74f, 0.81f, 0.83f, 0.95f, 1f, 1.06f, 1.68f};
    static final float[] FAN_FULL_DB = {-3f, -7f, -10f, -2f, -5f, -11f, 0f, -12f, -20f};
    static final float[] FAN_IDLE_DB = {-4f, -14f, -9f, -12f, -14f, -2f, 0f, -3f, -40f};
    /** 一对的两根差多少；左右两台再差多少。 */
    static final float PAIR = 0.013f;
    static final float ENGINE_SPLIT = 0.002f;
    /** 每根线的随机抖动（份额、低通 Hz）；低音抖得宽。 */
    static final float WOBBLE = 0.0025f;
    static final float WOBBLE_HZ = 8f;
    static final float LOW_WOBBLE = 0.012f;
    /** 主音的电平（相对转满时的低音，dB；怠速时两者差不多）；两台摆在左右多远。 */
    static final float WHINE_DB = -7f;
    static final float ENGINE_PAN = 0.3f;

    // ---- 低音（怠速 Hz、转满 Hz、dB），怠速时比转满低多少
    static final float[] LOW_IDLE_HZ = {88f, 76f, 165f};
    static final float[] LOW_FULL_HZ = {140f, 120f, 215f};
    static final float[] LOW_DB = {0f, -9f, -12f};
    static final float LOW_IDLE_DB = -6f;

    // ---- 喷流、「轰」
    static final float ROAR_HZ = 90f;
    static final float ROAR_Q = 0.7f;
    static final float ROAR_LOWPASS_HZ = 250f;
    /** 满推力、怠速时的喷流（dB）；噪声的倍数；两边共用、各自的份额。 */
    static final float ROAR_FULL_DB = -10f;
    static final float ROAR_IDLE_DB = -22f;
    static final float ROAR_GAIN = 3f;
    static final float ROAR_COMMON = 0.8f;
    static final float ROAR_OWN = 0.6f;
    /** 「轰」：多快落下去（秒）、多大（dB，相对转满时的低音）、松油门时是踩下时的几成；负荷「最近的平均」跟多慢、高出多少算满。 */
    static final float WHOOMP_SEC = 0.12f;
    static final float WHOOMP_DB = 20f;
    static final float WHOOMP_RELEASE = 0.63f;
    static final float WHOOMP_LAG_SEC = 0.3f;
    static final float WHOOMP_GAIN = 1.5f;

    // ---- 气声（带 Hz、转满时 dB、怠速时再低多少 dB）、一闪一闪（低通 Hz、深浅）
    static final float HISS_LOW_HZ = 1500f;
    static final float HISS_HIGH_HZ = 4000f;
    static final float HISS_DB = -12f;
    static final float HISS_IDLE_DB = -18f;
    static final float AIR_LOW_HZ = 400f;
    static final float AIR_HIGH_HZ = 1600f;
    static final float AIR_DB = -24f;
    static final float AIR_IDLE_DB = -32f;
    static final float SPECKLE_HZ = 25f;
    static final float SPECKLE = 0.7f;

    // ---- 换挡、松油门
    /** 「音爆」：N 形那一下多长（秒）、多大（dB）；跟着的一捶（Hz、多快落下去、dB）；换挡时的「轰」（满负荷时，升、降挡）。 */
    static final float BOOM_SEC = 0.06f;
    static final float BOOM_DB = -2f;
    static final float BOOM_LOWPASS_HZ = 2000f;
    static final float THUMP_HZ = 45f;
    static final float THUMP_SEC = 0.12f;
    static final float THUMP_DB = -4f;
    static final float SHIFT_WHOOMP_UP = 0.35f;
    static final float SHIFT_WHOOMP_DOWN = 0.2f;
    /** 松油门转子往下掉的「呼」：带通、电平、多快淡掉；负荷一块掉多少、转子在多少以上才有。 */
    static final float WHOOSH_HZ = 900f;
    static final float WHOOSH_Q = 1f;
    static final float WHOOSH_DB = -22f;
    static final float WHOOSH_SEC = 0.5f;
    static final float WHOOSH_DROP = 0.15f;
    static final float WHOOSH_FROM = 0.3f;
    /** 断扭时推力少几成。 */
    static final float CUT_THRUST = 0.7f;
    /** 电平几乎不跟油门变：虚拟发动机电平的这个次方。 */
    static final float LEVEL_POW = 0.05f;
    /** 总的倍数：全油门时限幅只压两三 dB（{@link Limiter}；「轰」「音爆」那一下压得多些）。 */
    static final float NORM = 0.34f;

    private static final int LINES = FAN_RATIO.length;
    private static final int LOWS = LOW_DB.length;
    /** 抖动、哨声电平、低音音调每这么多个样本算一次。 */
    private static final int CONTROL = 16;
    private static final float LN2 = 0.6931472f;
    private static final float SQRT2 = 1.4142135f;
    private static final float OCTAVES = (float) (Math.log(TOP_HZ / IDLE_HZ) / Math.log(2.0));
    /** 一对里的两根、两台发动机：频率的倍数。 */
    private static final float[] MEMBER = {1f - 0.5f * PAIR, 1f + 0.5f * PAIR};
    private static final float[] ENGINE = {1f - ENGINE_SPLIT, 1f + ENGINE_SPLIT};

    private final float rate;
    private final float lagUpK;
    private final float lagDownK;
    private final float wobbleK;
    private final float wobbleNorm;
    private final float speckleK;
    private final float speckleNorm;
    private final float whoompDecay;
    private final float whooshDecay;
    private final float thumpDecay;
    private final float gainK;
    /** 每根线：怠速时的增益（含主音电平和一对的一半）、到转满差多少（自然对数）。低音一样，差的是音调。 */
    private final float[] fanIdleGain = new float[LINES];
    private final float[] fanLnSpan = new float[LINES];
    private final float[] lowGain = new float[LOWS];
    private final float[] lowLnSpan = new float[LOWS];

    /** 转子（0 怠速 … 1 转满；起转时是负的）。 */
    private float spool;
    private boolean started;
    private int controlLeft;
    /** 哨声振荡器 [台 × 线 × 对里的哪根]：相位、抖动、频率是主音的几倍；每根线这一刻的增益。 */
    private final double[] fanPhase = new double[2 * LINES * 2];
    private final float[] fanWobble = new float[2 * LINES * 2];
    private final float[] fanMult = new float[2 * LINES * 2];
    private final float[] fanGain = new float[LINES];
    /** 低音振荡器 [台 × 根 × 对里的哪根]：相位、抖动、每样本走多少周。 */
    private final double[] lowPhase = new double[2 * LOWS * 2];
    private final float[] lowWobble = new float[2 * LOWS * 2];
    private final float[] lowInc = new float[2 * LOWS * 2];
    private final Biquad[] roarBand = new Biquad[3];
    private final Biquad[] roarLowpass = new Biquad[3];
    private final Biquad[] hissHighpass = new Biquad[2];
    private final Biquad[] hissLowpass = new Biquad[2];
    private final Biquad[] airHighpass = new Biquad[2];
    private final Biquad[] airLowpass = new Biquad[2];
    private final float[] speckle = new float[2];
    private final Biquad whooshBand;
    private final Biquad boomLowpass;
    private float whoomp;
    private float whoosh;
    private float loadPrev;
    private float loadSlow;
    private int boomPos = -1;
    private final int boomLength;
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
    private final Limiter limiter;
    private float levelNow;
    private float gainNow;
    private int noise = 0x6D2B79F5;

    JetVoice(float sampleRate) {
        this.rate = sampleRate;
        lagUpK = 1f - (float) Math.exp(-1.0 / (LAG_UP_SEC * sampleRate));
        lagDownK = 1f - (float) Math.exp(-1.0 / (LAG_DOWN_SEC * sampleRate));
        // 抖动每 CONTROL 个样本走一步：低通按这个步长算，输出方差归一
        wobbleK = 1f - (float) Math.exp(-2.0 * Math.PI * WOBBLE_HZ * CONTROL / sampleRate);
        wobbleNorm = (float) (1.0 / Math.sqrt(wobbleK / (2.0 - wobbleK)));
        speckleK = 1f - (float) Math.exp(-2.0 * Math.PI * SPECKLE_HZ / sampleRate);
        speckleNorm = (float) (1.0 / Math.sqrt(speckleK / (2.0 - speckleK)));
        whoompDecay = perSample(WHOOMP_SEC);
        whooshDecay = perSample(WHOOSH_SEC);
        thumpDecay = perSample(THUMP_SEC);
        gainK = 1f - (float) Math.exp(-1.0 / (EngineVoice.GAIN_SMOOTH_SEC * sampleRate));
        float dbToLn = (float) (Math.log(10.0) / 20.0);
        for (int k = 0; k < LINES; k++) {
            fanIdleGain[k] = 0.5f * EngineVoice.dbToLin(FAN_IDLE_DB[k] + WHINE_DB);
            fanLnSpan[k] = (FAN_FULL_DB[k] - FAN_IDLE_DB[k]) * dbToLn;
        }
        for (int k = 0; k < LOWS; k++) {
            lowGain[k] = 0.5f * EngineVoice.dbToLin(LOW_DB[k]);
            lowLnSpan[k] = (float) Math.log(LOW_FULL_HZ[k] / LOW_IDLE_HZ[k]);
        }
        for (int c = 0; c < 3; c++) {
            roarBand[c] = Biquad.bandpass(ROAR_HZ, ROAR_Q, sampleRate);
            roarLowpass[c] = Biquad.lowpass(ROAR_LOWPASS_HZ, 0.7071f, sampleRate);
        }
        for (int c = 0; c < 2; c++) {
            hissHighpass[c] = Biquad.highpass(HISS_LOW_HZ, 0.7071f, sampleRate);
            hissLowpass[c] = Biquad.lowpass(HISS_HIGH_HZ, 0.7071f, sampleRate);
            airHighpass[c] = Biquad.highpass(AIR_LOW_HZ, 0.7071f, sampleRate);
            airLowpass[c] = Biquad.lowpass(AIR_HIGH_HZ, 0.7071f, sampleRate);
            highpass[c] = Biquad.highpass(EngineVoice.HIGHPASS_HZ, 0.7071f, sampleRate);
            tone[c] = new Biquad();
        }
        whooshBand = Biquad.bandpass(WHOOSH_HZ, WHOOSH_Q, sampleRate);
        boomLowpass = Biquad.lowpass(BOOM_LOWPASS_HZ, 0.7071f, sampleRate);
        boomLength = Math.max(1, Math.round(BOOM_SEC * sampleRate));
        limiter = new Limiter(sampleRate);
        engineNear = SQRT2 * PulseVoice.panLeft(-ENGINE_PAN);
        engineFar = SQRT2 * PulseVoice.panRight(-ENGINE_PAN);
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
            limiter.reset();
            return;
        }
        float dt = frames / rate;
        float rpm = params.rpm > 0f ? params.rpm : 0f;
        float load = VirtualEngine.clamp01(params.load);
        float thrust = load * (1f - CUT_THRUST * VirtualEngine.clamp01(params.cut));
        float target = spoolTarget(rpm, load, params.drive);
        if (!started) {
            spool = target;
            started = true;
        }
        float lagK = target > spool ? lagUpK : lagDownK;
        // 从静止起转：音量跟着转速起来
        float start = rpm < IDLE_RPM ? VirtualEngine.clamp01(rpm / IDLE_RPM) : 1f;

        // ---- 「轰」：负荷比它最近的平均高出（或低下）一截；松油门转子往下掉的「呼」
        loadSlow += (load - loadSlow) * VirtualEngine.follow(dt, WHOOMP_LAG_SEC);
        float dev = load - loadSlow;
        float hit = dev > 0f ? VirtualEngine.clamp01(dev * WHOOMP_GAIN)
                : VirtualEngine.clamp01(-dev * WHOOMP_GAIN) * WHOOMP_RELEASE;
        whoomp = Math.max(whoomp, hit);
        float nowSpool = VirtualEngine.clamp01(spool);
        if (load - loadPrev < -WHOOSH_DROP && nowSpool > WHOOSH_FROM) {
            whoosh = Math.max(whoosh, nowSpool);
        }
        loadPrev = load;

        // ---- 换挡：升挡「音爆」+ 一捶；升、降挡都「轰」一点
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
            whoomp = Math.max(whoomp, (params.shiftUp ? SHIFT_WHOOMP_UP : SHIFT_WHOOMP_DOWN) * amp);
        }

        float lowIdle = EngineVoice.dbToLin(LOW_IDLE_DB);
        float roarFull = EngineVoice.dbToLin(ROAR_FULL_DB);
        float roarIdle = EngineVoice.dbToLin(ROAR_IDLE_DB);
        float burstGain = EngineVoice.dbToLin(WHOOMP_DB) * ROAR_GAIN * start;
        float hissFull = EngineVoice.dbToLin(HISS_DB);
        float hissIdle = EngineVoice.dbToLin(HISS_IDLE_DB);
        float airFull = EngineVoice.dbToLin(AIR_DB);
        float airIdle = EngineVoice.dbToLin(AIR_IDLE_DB);
        float whooshGain = EngineVoice.dbToLin(WHOOSH_DB);
        float fanLevel = (0.7f + 0.3f * thrust) * start;
        float levelTarget = (float) Math.pow(Math.max(params.level, 0f), LEVEL_POW) * NORM;
        boolean toneOn = params.toneHz < EngineVoice.TONE_BYPASS_HZ;
        if (toneOn && Math.abs(params.toneHz - toneSetHz) > 1f) {
            tone[0].setLowpass(params.toneHz, 0.7071f, rate);
            tone[1].setLowpass(params.toneHz, 0.7071f, rate);
            toneSetHz = params.toneHz;
        }

        for (int i = 0; i < frames; i++) {
            spool += (target - spool) * lagK;
            float s = VirtualEngine.clamp01(spool);
            if (--controlLeft < 0) {
                controlLeft = CONTROL - 1;
                control(s);
            }
            float inc = IDLE_HZ * (float) Math.exp(spool * OCTAVES * LN2) / rate;

            // 哨声：一扇线，每根一对，左右两台
            float e0 = 0f;
            float e1 = 0f;
            for (int k = 0; k < LINES; k++) {
                int o = 2 * k;
                int o1 = o + 2 * LINES;
                e0 += fanGain[k] * (fan(o, inc) + fan(o + 1, inc));
                e1 += fanGain[k] * (fan(o1, inc) + fan(o1 + 1, inc));
            }
            e0 *= fanLevel;
            e1 *= fanLevel;

            // 低音
            float l0 = 0f;
            float l1 = 0f;
            for (int k = 0; k < LOWS; k++) {
                int o = 2 * k;
                int o1 = o + 2 * LOWS;
                l0 += lowGain[k] * (low(o) + low(o + 1));
                l1 += lowGain[k] * (low(o1) + low(o1 + 1));
            }
            float lowLevel = (lowIdle + (1f - lowIdle) * s) * start;
            l0 *= lowLevel;
            l1 *= lowLevel;

            // 喷流 + 「轰」（一段很大的低频噪声）
            whoomp *= whoompDecay;
            float roarLevel = (roarIdle + (roarFull - roarIdle) * thrust * (0.4f + 0.6f * s)) * start * ROAR_GAIN;
            float common = roarLowpass[2].process(roarBand[2].process(white()));
            float burst = common * burstGain * whoomp;
            float roarL = (ROAR_COMMON * common + ROAR_OWN * roarLowpass[0].process(roarBand[0].process(white()))) * roarLevel
                    + burst;
            float roarR = (ROAR_COMMON * common + ROAR_OWN * roarLowpass[1].process(roarBand[1].process(white()))) * roarLevel
                    + burst;

            // 气声：跟着转子，一闪一闪
            float hissLevel = hissFull * (hissIdle + (1f - hissIdle) * s * s) * start;
            float airLevel = airFull * (airIdle + (1f - airIdle) * s) * start;
            float hissL = noisy(0, hissLevel, airLevel);
            float hissR = noisy(1, hissLevel, airLevel);

            // 「呼」、「音爆」、一捶
            whoosh *= whooshDecay;
            float middle = whooshBand.process(white()) * whooshGain * whoosh;
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

            float a = e0 + l0;
            float b = e1 + l1;
            float l = engineNear * a + engineFar * b + roarL + hissL + PulseVoice.CENTER * middle;
            float r = engineFar * a + engineNear * b + roarR + hissR + PulseVoice.CENTER * middle;
            levelNow += (levelTarget - levelNow) * gainK;
            l *= levelNow;
            r *= levelNow;
            if (toneOn) {
                l = tone[0].process(l);
                r = tone[1].process(r);
            }
            l = highpass[0].process(l);
            r = highpass[1].process(r);
            gainNow += (params.gain - gainNow) * gainK;
            limiter.push(l * gainNow, r * gainNow);
            lr[2 * i] = limiter.left;
            lr[2 * i + 1] = limiter.right;
        }
    }

    /** 慢变的部分：每根线的抖动和这一刻的增益（按 dB 在怠速、转满之间走），低音的音调和抖动。 */
    private void control(float s) {
        for (int k = 0; k < LINES; k++) {
            fanGain[k] = fanIdleGain[k] * (float) Math.exp(s * fanLnSpan[k]);
            for (int e = 0; e < 2; e++) {
                for (int m = 0; m < 2; m++) {
                    int o = e * 2 * LINES + 2 * k + m;
                    fanWobble[o] += wobbleK * (white() - fanWobble[o]);
                    fanMult[o] = FAN_RATIO[k] * MEMBER[m] * ENGINE[e] * (1f + WOBBLE * wobbleNorm * fanWobble[o]);
                }
            }
        }
        for (int k = 0; k < LOWS; k++) {
            float hz = LOW_IDLE_HZ[k] * (float) Math.exp(s * lowLnSpan[k]);
            for (int e = 0; e < 2; e++) {
                for (int m = 0; m < 2; m++) {
                    int o = e * 2 * LOWS + 2 * k + m;
                    lowWobble[o] += wobbleK * (white() - lowWobble[o]);
                    lowInc[o] = hz * MEMBER[m] * (1f + LOW_WOBBLE * wobbleNorm * lowWobble[o]) / rate;
                }
            }
        }
    }

    /** 转子的目标：停着跟油门；挂着挡跟虚拟转速，一踩先往上赶、松油门低一截；低于怠速（起转）是负的。 */
    private static float spoolTarget(float rpm, float load, boolean drive) {
        if (rpm < IDLE_RPM) {
            return BELOW_IDLE_POW * (float) (Math.log(Math.max(rpm, 30f) / IDLE_RPM) / Math.log(2.0)) / OCTAVES;
        }
        if (!drive) {
            return (float) Math.pow(load, FREE_CURVE);
        }
        float s = (float) Math.pow(VirtualEngine.clamp01((rpm - IDLE_RPM) / (FULL_RPM - IDLE_RPM)), SPOOL_CURVE);
        return Math.max(s, LOAD_LEAD * load) * (1f - LOAD_WEIGHT + LOAD_WEIGHT * load);
    }

    /** 哨声的第 {@code o} 个振荡器往前走一个样本（主音每样本走 {@code inc} 周）。 */
    private float fan(int o, float inc) {
        double ph = fanPhase[o] + inc * fanMult[o];
        if (ph >= 1.0) {
            ph -= 1.0;
        }
        fanPhase[o] = ph;
        return EngineVoice.sin(ph);
    }

    /** 低音的第 {@code o} 个振荡器往前走一个样本。 */
    private float low(int o) {
        double ph = lowPhase[o] + lowInc[o];
        if (ph >= 1.0) {
            ph -= 1.0;
        }
        lowPhase[o] = ph;
        return EngineVoice.sin(ph);
    }

    /** 一个声道的气声：两层带通噪声，一起一闪一闪。 */
    private float noisy(int c, float hissLevel, float airLevel) {
        speckle[c] += speckleK * (white() - speckle[c]);
        float flicker = Math.max(0f, 1f + SPECKLE * speckleNorm * speckle[c]);
        float h = hissLowpass[c].process(hissHighpass[c].process(white())) * hissLevel;
        float a = airLowpass[c].process(airHighpass[c].process(white())) * airLevel;
        return (h + a) * flicker;
    }

    /** 此刻的主音（Hz）：测试看转子跟没跟上。 */
    float mainHz() {
        return IDLE_HZ * (float) Math.exp(spool * OCTAVES * LN2);
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
