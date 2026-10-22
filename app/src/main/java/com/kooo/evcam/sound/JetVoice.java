package com.kooo.evcam.sound;

/**
 * 「超音速」风格（{@link SoundStyle#JET}，试制第二版。项目所有者 2026-10-21：参考一款纯电高性能车的主动声浪主题，它完全是模拟出来的；
 * 第一版「差距十分巨大，基本需要重做」）：照参考的整张频谱图重做，按车内喇叭播放来做。
 * 纯计算（{@code JetVoiceTest}），声音线程上一块一块地调，循环里不分配内存。量过的参考在 docs/local/drive-sound-plan.md §13。
 *
 * <ul>
 *   <li><b>转子</b>：停着时跟油门（喷气机的转速跟油门走：一踩约 0.2 秒升上去，一松 0.7 秒落到一半以下）；挂 D 挡就是虚拟转速
 *       （伪挡位、模拟换挡时每一挡从低爬到高、升挡掉下来）。主音和转速成正比（{@link #MAIN_ORDER} 阶）：怠速约 150 Hz，
 *       转满约 1030 Hz。项目所有者 2026-10-22 挑的几段参考：主音跟着虚拟转速走，挂挡加速每一挡从约 550 爬到约 1050 Hz，
 *       升挡掉回 0.65–0.8 倍，停着踩到顶约 1000–1030 Hz（第二版只在 533–1190 Hz 里走，挂挡全油门时还先顶到七成，几乎是平的）。</li>
 *   <li><b>哨声</b>：风扇轴转一圈的每个倍数上都有音（叶尖超音速时的「锯齿声」）—— 第 3 到第 24 次一整排跟着转子一起走，
 *       主音是第 12 次，旁边几根最响，其余参差不齐地低下去；每一根各自带随机的相位抖动（发糊、彼此不锁死），主音附近三根
 *       再成对（相差 1.3 %，每秒「拍」十几下）；左右两台，差一点点。项目所有者 2026-10-22：我们的电音多了一些，原厂那种飞机的
 *       气势、多频率的齐鸣、厚度不够 —— 参考踩到顶那一刻 200–2000 Hz 里 20 dB 以内有 13–22 根线，线和线之间是满的。</li>
 *   <li><b>低音</b>：三根（88 → 140、76 → 120、165 → 215 Hz），也成对、抖得更宽，转满时是最响的。</li>
 *   <li><b>喷流</b>：250 Hz 以下的噪声，大半是两边共用的一股，随推力长；一踩油门（负荷比它最近的平均高出一截）「轰」一下 ——
 *       一段很大的低频噪声，约 0.2 秒；一松也「轰」，小一些。</li>
 *   <li><b>气声</b>：线之间 0.4–1.6 kHz 的气声、1.5–4 kHz 的嘶声，跟着转子长，一闪一闪（斑点状）。</li>
 *   <li><b>换挡</b>：升挡一声「音爆」（N 形的一下 + 45 Hz 的一捶）加一点「轰」；降挡只「轰」一点。松油门转子往下掉时「呼」一声。</li>
 *   <li><b>启动、熄火</b>：从静止起转，音调从低往上走到怠速（行驶中也一样，从低处赶到该有的转速）；熄火时转子落下去，
 *       音调往下走、越来越轻。</li>
 * </ul>
 *
 * <p>电平不大跟虚拟发动机变（参考里踩住油门并不比怠速响多少，变化靠低音、喷流随推力长和一踩一松的「轰」）。
 * 音量、阀门（低通，现在一直全开）、30 Hz 高通、最后的限幅（{@link Limiter}）和别的风格一样，峰值不超过 {@link EngineVoice#CEILING}。
 * 不和别的风格比响度（项目所有者 2026-10-21：两种取向各自对各自的原型）。
 * 抖动、哨声的电平、低音的音调变得慢，每 16 个样本才算一次。</p>
 */
final class JetVoice implements Voice {

    // ---- 转子
    static final float IDLE_RPM = 900f;
    /** 挂着挡时虚拟转速到这里算转满（停着时转子跟油门，不看转速）。 */
    static final float FULL_RPM = 6200f;
    /** 主音是转子转速的几阶（转一圈几下）：转子 0 是怠速、1 是转满，中间按转速直线走。 */
    static final float MAIN_ORDER = 10f;
    /** 怠速、转满时的主音（Hz）。 */
    static final float IDLE_HZ = MAIN_ORDER * IDLE_RPM / 60f;
    static final float TOP_HZ = MAIN_ORDER * FULL_RPM / 60f;
    /** 主音最低到多少（Hz；起转、熄火落到底时）。 */
    static final float MIN_HZ = 20f;
    /** 停着：转子 = 负荷^它（平方：负荷松开时那段拖尾很快就没了）。 */
    static final float FREE_CURVE = 2f;
    /** 停着时转子的惯性（秒，上、下）：参考一踩约 0.2 秒到顶，一松 0.7 秒落到一半以下、再慢慢回怠速。 */
    static final float LAG_UP_SEC = 0.14f;
    static final float LAG_DOWN_SEC = 0.7f;
    /** 挂着挡（或低于怠速：起转、熄火）时转子就是虚拟转速，只再平一点（秒）：升挡那一下要掉得干脆。 */
    static final float DRIVE_LAG_SEC = 0.04f;

    // ---- 哨声：风扇轴的第 COMB_FROM 次起一整排谐波（转满时 dB、怠速时 dB，相对主音，中间按 dB 走），主音是第 COMB_MAIN 次
    static final int COMB_MAIN = 12;
    static final int COMB_FROM = 3;
    static final float[] COMB_FULL_DB = {-18f, -16f, -10f, -17f, -12f, -7f, -11f, -6f, -2f, 0f, -9f, -13f,
        -15f, -14f, -17f, -16f, -19f, -18f, -21f, -20f, -23f, -22f};
    static final float[] COMB_IDLE_DB = {-30f, -29f, -24f, -28f, -26f, -20f, -22f, -10f, -6f, -2f, -8f, -12f,
        -14f, -13f, -16f, -15f, -18f, -17f, -21f, -20f, -24f, -23f};
    /** 主音附近成对的几根（第几次到第几次）、一对的两根差多少；左右两台再差多少。 */
    static final int PAIR_FROM = 10;
    static final int PAIR_TO = 12;
    static final float PAIR = 0.013f;
    static final float ENGINE_SPLIT = 0.002f;
    /**
     * 每根谐波的随机相位抖动（周的标准差、低通 Hz）：参考的线 −10 dB 宽 50–100 Hz。每根还有一个固定的相位（叶片之间的细小差别），
     * 不然一整排对齐的谐波叠成一串尖脉冲 —— 峰值高（全油门时限幅要压 4 dB），听着也是「嗡嗡」的电子味。
     */
    static final float COMB_JITTER = 0.06f;
    static final float COMB_JITTER_HZ = 10f;
    /** 低音的随机抖动（份额、低通 Hz）。 */
    static final float LOW_WOBBLE = 0.012f;
    static final float WOBBLE_HZ = 8f;
    /** 主音的电平（相对转满时的低音，dB；怠速时两者差不多）；两台摆在左右多远。 */
    static final float WHINE_DB = -4.5f;
    static final float ENGINE_PAN = 0.3f;

    // ---- 低音（怠速 Hz、转满 Hz、dB），怠速时比转满低多少
    static final float[] LOW_IDLE_HZ = {88f, 76f, 165f};
    static final float[] LOW_FULL_HZ = {140f, 120f, 215f};
    static final float[] LOW_DB = {-5f, -14f, -17f};
    static final float LOW_IDLE_DB = -1f;

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
    /** 电平不大跟油门变：虚拟发动机电平的这个次方（2026-10-22 输入还原平滑以后略放大了响度差）。 */
    static final float LEVEL_POW = 0.12f;
    /** 总的倍数：全油门时限幅只压两三 dB（{@link Limiter}；「轰」「音爆」那一下压得多些）。 */
    static final float NORM = 0.32f;

    private static final int LINES = COMB_FULL_DB.length;
    private static final int LOWS = LOW_DB.length;
    /** 抖动、哨声电平、低音音调每这么多个样本算一次。 */
    private static final int CONTROL = 16;
    private static final float LN2 = 0.6931472f;
    private static final float SQRT2 = 1.4142135f;
    /** 一对里的两根、两台发动机：频率的倍数。 */
    private static final float[] MEMBER = {1f - 0.5f * PAIR, 1f + 0.5f * PAIR};
    private static final float[] ENGINE = {1f - ENGINE_SPLIT, 1f + ENGINE_SPLIT};

    private final float rate;
    private final float lagUpK;
    private final float lagDownK;
    private final float driveLagK;
    private final float wobbleK;
    private final float wobbleNorm;
    private final float jitterK;
    private final float jitterNorm;
    private final float speckleK;
    private final float speckleNorm;
    private final float whoompDecay;
    private final float whooshDecay;
    private final float thumpDecay;
    private final float gainK;
    /** 每根谐波：怠速时的增益（含主音电平；成对的是一半）、到转满差多少（自然对数）。低音一样，差的是音调。 */
    private final float[] fanIdleGain = new float[LINES];
    private final float[] fanLnSpan = new float[LINES];
    private final float[] lowGain = new float[LOWS];
    private final float[] lowLnSpan = new float[LOWS];

    /** 转子（0 怠速 … 1 转满；起转时是负的）。 */
    private float spool;
    private boolean started;
    private int controlLeft;
    /** 两台风扇轴的相位（周）；每根谐波 [台 × 根] 的相位抖动（周）和它的低通噪声、成对的另一根的相位；每根这一刻的增益。 */
    private final double[] shaftPhase = new double[2];
    private final float[] jitter = new float[2 * LINES];
    private final float[] jitterNoise = new float[2 * LINES];
    private final double[] pairPhase = new double[2 * LINES];
    /** 每根谐波 [台 × 根] 固定的相位（周）。 */
    private final float[] combOffset = new float[2 * LINES];
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
        driveLagK = 1f - (float) Math.exp(-1.0 / (DRIVE_LAG_SEC * sampleRate));
        // 抖动每 CONTROL 个样本走一步：低通按这个步长算，输出方差归一
        wobbleK = 1f - (float) Math.exp(-2.0 * Math.PI * WOBBLE_HZ * CONTROL / sampleRate);
        wobbleNorm = (float) (1.0 / Math.sqrt(wobbleK / (2.0 - wobbleK)));
        jitterK = 1f - (float) Math.exp(-2.0 * Math.PI * COMB_JITTER_HZ * CONTROL / sampleRate);
        jitterNorm = (float) (1.0 / Math.sqrt(jitterK / (2.0 - jitterK)));
        // Schroeder 的相位（第 k 根 k(k − 1) / 2N 周）：一整排谐波叠出来最平；第二台错开半周
        for (int e = 0; e < 2; e++) {
            for (int k = 0; k < LINES; k++) {
                combOffset[e * LINES + k] = (float) ((k * (k + 1) / (2.0 * LINES) + 0.5 * e) % 1.0);
            }
        }
        speckleK = 1f - (float) Math.exp(-2.0 * Math.PI * SPECKLE_HZ / sampleRate);
        speckleNorm = (float) (1.0 / Math.sqrt(speckleK / (2.0 - speckleK)));
        whoompDecay = perSample(WHOOMP_SEC);
        whooshDecay = perSample(WHOOSH_SEC);
        thumpDecay = perSample(THUMP_SEC);
        gainK = 1f - (float) Math.exp(-1.0 / (EngineVoice.GAIN_SMOOTH_SEC * sampleRate));
        float dbToLn = (float) (Math.log(10.0) / 20.0);
        for (int k = 0; k < LINES; k++) {
            int n = COMB_FROM + k;
            fanIdleGain[k] = (n >= PAIR_FROM && n <= PAIR_TO ? 0.5f : 1f) * EngineVoice.dbToLin(COMB_IDLE_DB[k] + WHINE_DB);
            fanLnSpan[k] = (COMB_FULL_DB[k] - COMB_IDLE_DB[k]) * dbToLn;
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
        float lagK = params.drive || rpm < IDLE_RPM ? driveLagK : target > spool ? lagUpK : lagDownK;
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
            float inc = mainHz(spool) / COMB_MAIN / rate;

            // 哨声：风扇轴的一整排谐波，左右两台
            float e0 = comb(0, inc) * fanLevel;
            float e1 = comb(1, inc) * fanLevel;

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

    /** 慢变的部分：每根谐波的相位抖动和这一刻的增益（按 dB 在怠速、转满之间走），低音的音调和抖动。 */
    private void control(float s) {
        for (int k = 0; k < LINES; k++) {
            fanGain[k] = fanIdleGain[k] * (float) Math.exp(s * fanLnSpan[k]);
            for (int e = 0; e < 2; e++) {
                int o = e * LINES + k;
                jitterNoise[o] += jitterK * (white() - jitterNoise[o]);
                jitter[o] = COMB_JITTER * jitterNorm * jitterNoise[o];
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

    /** 转子的目标：停着跟油门；挂着挡就是虚拟转速（转子 0 是怠速、1 是转满）；低于怠速（起转）是负的，主音跟着往下。 */
    private static float spoolTarget(float rpm, float load, boolean drive) {
        if (rpm < IDLE_RPM || drive) {
            return Math.min(1.2f, (rpm - IDLE_RPM) / (FULL_RPM - IDLE_RPM));
        }
        return (float) Math.pow(load, FREE_CURVE);
    }

    /** 转子在 {@code s} 时的主音（Hz）：和转子转速成正比，最低 {@link #MIN_HZ}。 */
    private static float mainHz(float s) {
        return Math.max(MIN_HZ, MAIN_ORDER * (IDLE_RPM + (FULL_RPM - IDLE_RPM) * s) / 60f);
    }

    /** 第 {@code e} 台的哨声往前走一个样本（风扇轴每样本走 {@code inc} 周）：每根谐波按轴的相位 × 次数，加自己的抖动。 */
    private float comb(int e, float inc) {
        double ph = shaftPhase[e] + inc * ENGINE[e];
        ph -= Math.floor(ph);
        shaftPhase[e] = ph;
        float sum = 0f;
        for (int k = 0; k < LINES; k++) {
            int n = COMB_FROM + k;
            int o = e * LINES + k;
            float s = EngineVoice.sin(n * ph + combOffset[o] + jitter[o]);
            if (n >= PAIR_FROM && n <= PAIR_TO) {
                double q = pairPhase[o] + n * inc * ENGINE[e] * (1f + PAIR);
                q -= Math.floor(q);
                pairPhase[o] = q;
                s += EngineVoice.sin(q + combOffset[o] + jitter[o]);
            }
            sum += fanGain[k] * s;
        }
        return sum;
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
        return mainHz(spool);
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
