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
 *   <li><b>哨声</b>：一大排不成倍数的线（相对主音 0.36 … 1.86，32 根，间隔不规则）跟着转子一起走，主音最响；左右两台只差一点点。
 *       线多、线和线之间满（项目所有者 2026-10-22：电音多了，原厂那种飞机的气势、多频率的齐鸣、厚度不够 —— 参考踩到顶那一刻
 *       200–2000 Hz 里 20 dB 以内有 13–22 根线，中间那一格只比最响的低 19–27 dB），但没有共同的「基频」（一整排严格的谐波听得出
 *       它们的公共频率，转速低时就是一串一串的节奏，像活塞发动机 —— 车主：飞机应该是嗡嗡的，没有明显的节奏）。插在中间的 14 根
 *       怠速时几乎没有、转得越高越响：转速低时它们和邻居一秒只拍几下，就是节奏；转满时拍成一秒几十下的「嗡」，把空当填满。</li>
 *   <li><b>低音</b>：一根，居中（88 → 140 Hz），带它的二次谐波，音调随机晃一点，转满时是最响的；随推力加一点它下面八度的低沉。</li>
 *   <li><b>「轰」</b>：一踩油门（负荷比它最近的平均高出一截）低音下面八度「轰」一声，约 0.2 秒；一松也「轰」，小一些。</li>
 *   <li><b>换挡</b>：升挡一声「音爆」（N 形的一下 + 45 Hz 的一捶）加一点「轰」；降挡只「轰」一点。松油门转子往下掉时「呼」一声。</li>
 *   <li><b>启动、熄火</b>：从静止起转，音调从低往上走到怠速（行驶中也一样，从低处赶到该有的转速）；熄火时转子落下去，
 *       音调往下走、越来越轻。</li>
 * </ul>
 *
 * <p>没有随机的快起伏（项目所有者 2026-10-22/23：沙沙的颗粒状噪音，一踩油门就变密变强；一层一层拆开听：一根纯音、一根低音、
 * 不抖的线都没有颗粒，有颗粒的是线的随机抖动、成对又随机晃的三根低音、喷流的噪声和那一大段噪声的「轰」）。所以：线不抖，
 * 低音一根，没有持续的噪声（喷流、气声、嘶声），「轰」是一声低音；跟着油门变的电平逐个样本平滑地走，不按块跳。</p>
 *
 * <p>电平不大跟虚拟发动机变（参考里踩住油门并不比怠速响多少，变化靠低音、推力的低沉和一踩一松的「轰」）。
 * 音量、阀门（低通，现在一直全开）、30 Hz 高通、最后的限幅（{@link Limiter}）和别的风格一样，峰值不超过 {@link EngineVoice#CEILING}。
 * 不和别的风格比响度（项目所有者 2026-10-21：两种取向各自对各自的原型）。
 * 哨声的电平、低音的音调变得慢，每 16 个样本才算一次。</p>
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

    // ---- 哨声：一大排不成倍数的线（× 主音；转满时 dB、怠速时 dB，相对主音，中间按 dB 走）；怠速 −40 dB 的那 14 根是插在中间、
    //      转得越高越响的（不抖以后线和线之间是空的，参考踩到顶那一刻是满的）
    static final float[] LINE_RATIO = {0.36f, 0.39f, 0.42f, 0.455f, 0.49f, 0.525f, 0.56f, 0.6f, 0.64f, 0.69f, 0.715f, 0.74f,
        0.775f, 0.81f, 0.845f, 0.88f, 0.915f, 0.95f, 1f, 1.07f, 1.12f, 1.17f, 1.225f, 1.28f, 1.345f, 1.41f, 1.48f, 1.55f,
        1.615f, 1.68f, 1.77f, 1.86f};
    static final float[] LINE_FULL_DB = {-16f, -15f, -10f, -15f, -14f, -15f, -12f, -15f, -9f, -7f, -15f, -8f, -15f, -6f, -15f,
        -9f, -15f, -7f, 0f, -8f, -15f, -9f, -15f, -11f, -15f, -13f, -15f, -12f, -15f, -15f, -15f, -18f};
    static final float[] LINE_IDLE_DB = {-31.5f, -40f, -25.5f, -40f, -29.5f, -40f, -27.5f, -40f, -23.5f, -21.5f, -40f, -22.5f,
        -40f, -19.5f, -40f, -27.5f, -40f, -30f, -1.5f, -30f, -40f, -27.5f, -40f, -16.5f, -40f, -19.5f, -40f, -19.5f, -40f,
        -23.5f, -40f, -27.5f};
    /** 左右两台差多少（份额；很小：两台的同一根线之间只有一两秒一次的慢起伏）。 */
    static final float ENGINE_SPLIT = 0.0007f;
    /**
     * 每根线的音调各自慢慢漂（份额的标准差、两级低通 Hz；两台一起漂，不然两台同一根线之间拍成一秒几下的「哇哇」）：线和线之间
     * 的拍、拍和拍之间的差（一秒十几下的颤）跟着漂，不成节奏。原来是随机的相位抖动，一秒几十下 —— 线变成一小片噪声，听着是颗粒
     * （项目所有者 2026-10-23）。主音旁边的 0.88、0.95、1.07、1.17 倍怠速时压得很低：它们和主音拍的那几下，转速低时一秒只有
     * 七到十几下。
     */
    static final float WANDER = 0.003f;
    static final float WANDER_HZ = 1f;
    /** 主音的电平（相对转满时的低音，dB；怠速时两者差不多）；两台摆在左右多远。 */
    static final float WHINE_DB = -6f;
    static final float ENGINE_PAN = 0.3f;

    // ---- 低音：一根，居中（怠速 Hz、转满 Hz、dB），怠速时比转满低多少；音调随机晃一点（份额、低通 Hz）
    static final float LOW_IDLE_HZ = 88f;
    static final float LOW_FULL_HZ = 140f;
    static final float LOW_DB = -5f;
    static final float LOW_IDLE_DB = -1f;
    static final float LOW_WOBBLE = 0.012f;
    static final float WOBBLE_HZ = 8f;
    /** 低音的二次谐波（相对低音，dB，转满时；怠速时三成）：补上低音和最低那根哨声之间的空当（原来是喷流的噪声）。 */
    static final float LOW_SECOND_DB = -10f;
    /** 推力的低沉：低音下面八度，满推力时多大（dB）。 */
    static final float SUB_DB = -11f;

    // ---- 「轰」：低音下面八度的一声
    /** 多快落下去（秒）、多大（dB，满的一下）、松油门时是踩下时的几成；负荷「最近的平均」跟多慢、高出多少算满。 */
    static final float WHOOMP_SEC = 0.12f;
    static final float WHOOMP_DB = 0f;
    static final float WHOOMP_RELEASE = 0.63f;
    static final float WHOOMP_LAG_SEC = 0.3f;
    static final float WHOOMP_GAIN = 1.5f;

    // ---- 换挡、松油门
    /** 「音爆」：N 形那一下多长（秒）、多大（dB）；跟着的一捶（Hz、多快落下去、dB）；换挡时的「轰」（满负荷时，升、降挡）。 */
    static final float BOOM_SEC = 0.06f;
    static final float BOOM_DB = -2f;
    static final float BOOM_LOWPASS_HZ = 2000f;
    static final float THUMP_HZ = 45f;
    static final float THUMP_SEC = 0.12f;
    static final float THUMP_DB = -4f;
    static final float SHIFT_WHOOMP_UP = 1f;
    static final float SHIFT_WHOOMP_DOWN = 0.4f;
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

    private static final int LINES = LINE_RATIO.length;
    /** 哨声电平、低音音调每这么多个样本算一次。 */
    private static final int CONTROL = 16;
    private static final float SQRT2 = 1.4142135f;
    /** 两台发动机：频率的倍数。 */
    private static final float[] ENGINE = {1f - ENGINE_SPLIT, 1f + ENGINE_SPLIT};

    private final float rate;
    private final float lagUpK;
    private final float lagDownK;
    private final float driveLagK;
    private final float wobbleK;
    private final float wobbleNorm;
    private final float wanderK;
    private final float wanderNorm;
    private final float whoompDecay;
    private final float whooshDecay;
    private final float thumpDecay;
    private final float gainK;
    /** 每根线：怠速时的增益（含主音电平）、到转满差多少（自然对数）。低音一样，差的是音调。 */
    private final float[] fanIdleGain = new float[LINES];
    private final float[] fanLnSpan = new float[LINES];
    private final float lowGain;
    private final float lowLnSpan;
    private final float secondGain;
    private final float subGain;
    private final float whoompGain;

    /** 转子（0 怠速 … 1 转满；起转时是负的）。 */
    private float spool;
    private boolean started;
    private int controlLeft;
    /** 哨声的线 [台 × 根] 的相位（周）；每根音调漂移的两级低通噪声和这一刻的倍数、这一刻的增益。 */
    private final double[] linePhase = new double[2 * LINES];
    private final float[] wanderNoise = new float[LINES];
    private final float[] wanderSmooth = new float[LINES];
    private final float[] lineMul = new float[LINES];
    private final float[] fanGain = new float[LINES];
    /** 低音：相位（周）、随机晃动、每样本走多少周；它下面八度的相位（跟着它走一半，两者对得齐）。 */
    private double lowPhase;
    private float lowWobble;
    private float lowInc;
    private double subPhase;
    /** 推力、起转的份额：逐个样本平滑地走（按块跳的话，踩油门时电平一块一个小台阶，每秒 188 下）。 */
    private float thrustNow;
    private float startNow = -1f;
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
        // 晃动每 CONTROL 个样本走一步：低通按这个步长算，输出方差归一
        wobbleK = 1f - (float) Math.exp(-2.0 * Math.PI * WOBBLE_HZ * CONTROL / sampleRate);
        wobbleNorm = (float) (1.0 / Math.sqrt(wobbleK / (2.0 - wobbleK)));
        wanderK = 1f - (float) Math.exp(-2.0 * Math.PI * WANDER_HZ * CONTROL / sampleRate);
        wanderNorm = twoPoleNorm(wanderK);
        java.util.Arrays.fill(lineMul, 1f);
        whoompDecay = perSample(WHOOMP_SEC);
        whooshDecay = perSample(WHOOSH_SEC);
        thumpDecay = perSample(THUMP_SEC);
        gainK = 1f - (float) Math.exp(-1.0 / (EngineVoice.GAIN_SMOOTH_SEC * sampleRate));
        float dbToLn = (float) (Math.log(10.0) / 20.0);
        for (int k = 0; k < LINES; k++) {
            fanIdleGain[k] = EngineVoice.dbToLin(LINE_IDLE_DB[k] + WHINE_DB);
            fanLnSpan[k] = (LINE_FULL_DB[k] - LINE_IDLE_DB[k]) * dbToLn;
        }
        lowGain = EngineVoice.dbToLin(LOW_DB);
        lowLnSpan = (float) Math.log(LOW_FULL_HZ / LOW_IDLE_HZ);
        secondGain = EngineVoice.dbToLin(LOW_SECOND_DB);
        subGain = EngineVoice.dbToLin(SUB_DB);
        whoompGain = EngineVoice.dbToLin(WHOOMP_DB);
        for (int c = 0; c < 2; c++) {
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
        // 从静止起转：音量跟着转速起来
        float start = rpm < IDLE_RPM ? VirtualEngine.clamp01(rpm / IDLE_RPM) : 1f;
        if (!started) {
            spool = target;
            thrustNow = thrust;
            startNow = start;
            started = true;
        }
        float lagK = params.drive || rpm < IDLE_RPM ? driveLagK : target > spool ? lagUpK : lagDownK;

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
        float whooshGain = EngineVoice.dbToLin(WHOOSH_DB);
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
            thrustNow += (thrust - thrustNow) * gainK;
            startNow += (start - startNow) * gainK;
            if (--controlLeft < 0) {
                controlLeft = CONTROL - 1;
                control(s);
            }
            float inc = mainHz(spool) / rate;

            // 哨声：一大排不成倍数的线，左右两台
            float fanLevel = (0.7f + 0.3f * thrustNow) * startNow;
            float e0 = lines(0, inc) * fanLevel;
            float e1 = lines(1, inc) * fanLevel;

            // 低音、它下面八度：推力的低沉 + 「轰」
            double lp = lowPhase + lowInc;
            if (lp >= 1.0) {
                lp -= 1.0;
            }
            lowPhase = lp;
            double sp = subPhase + 0.5f * lowInc;
            if (sp >= 1.0) {
                sp -= 1.0;
            }
            subPhase = sp;
            whoomp *= whoompDecay;
            float lowAmp = lowGain * (lowIdle + (1f - lowIdle) * s) * startNow;
            float low = lowAmp * (EngineVoice.sin(lp) + secondGain * (0.3f + 0.7f * s) * EngineVoice.sin(2.0 * lp))
                    + (subGain * thrustNow * (0.4f + 0.6f * s) + whoompGain * whoomp) * startNow * EngineVoice.sin(sp);

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

            float l = engineNear * e0 + engineFar * e1 + low + PulseVoice.CENTER * middle;
            float r = engineFar * e0 + engineNear * e1 + low + PulseVoice.CENTER * middle;
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

    /** 慢变的部分：每根线这一刻的增益（按 dB 在怠速、转满之间走）和音调漂移，低音的音调和晃动。 */
    private void control(float s) {
        for (int k = 0; k < LINES; k++) {
            fanGain[k] = fanIdleGain[k] * (float) Math.exp(s * fanLnSpan[k]);
            wanderNoise[k] += wanderK * (white() - wanderNoise[k]);
            wanderSmooth[k] += wanderK * (wanderNoise[k] - wanderSmooth[k]);
            lineMul[k] = 1f + WANDER * wanderNorm * wanderSmooth[k];
        }
        lowWobble += wobbleK * (white() - lowWobble);
        lowInc = LOW_IDLE_HZ * (float) Math.exp(s * lowLnSpan) * (1f + LOW_WOBBLE * wobbleNorm * lowWobble) / rate;
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

    /** 第 {@code e} 台的哨声往前走一个样本（主音每样本走 {@code inc} 周）：每根线按自己的倍数（和这一刻的漂移）走。 */
    private float lines(int e, float inc) {
        float step = inc * ENGINE[e];
        float sum = 0f;
        for (int k = 0; k < LINES; k++) {
            int o = e * LINES + k;
            double ph = linePhase[o] + step * LINE_RATIO[k] * lineMul[k];
            if (ph >= 1.0) {
                ph -= 1.0;
            }
            linePhase[o] = ph;
            sum += fanGain[k] * EngineVoice.sin(ph);
        }
        return sum;
    }

    /** 此刻的主音（Hz）：测试看转子跟没跟上。 */
    float mainHz() {
        return mainHz(spool);
    }

    /** 方差 1 的白噪声过两级一样的一阶低通（每步 {@code k}）以后，乘多少方差回到 1：输出方差 k⁴(1 + r) / (1 − r)³，r = (1 − k)²。 */
    private static float twoPoleNorm(float k) {
        double r = (1.0 - k) * (1.0 - k);
        return (float) (1.0 / Math.sqrt(Math.pow(k, 4) * (1.0 + r) / Math.pow(1.0 - r, 3)));
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
