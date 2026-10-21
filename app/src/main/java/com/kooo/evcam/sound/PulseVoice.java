package com.kooo.evcam.sound;

/**
 * 「美式 V8」风格（{@link SoundStyle#AMERICAN}，第二版。项目所有者 2026-10-21：第一版太单薄，要机械增压交叉平面 V8 的取向）：
 * 照发动机的结构逐缸合成，立体声。
 * 纯计算（{@code PulseVoiceTest}），声音线程上一块一块地调，循环里不分配内存。数据在 {@link PulseProfile}，
 * 来历和量过的参考在 docs/local/drive-sound-plan.md §12。
 *
 * <ul>
 *   <li><b>八个缸</b>：曲轴转到每个缸的排气门打开时，往那一排的排气管里打一个压力脉冲（起得快、按曲轴角落下去）。
 *       强弱 = 缸的差别 × 同一排上一个脉冲留下的压力（交叉平面 V8 每排的点火间隔是 90/180/270°，「突突」就是这么来的）
 *       × 每一下的随机起伏；时机按歧管长短错开一点，再随机偏一点。</li>
 *   <li><b>气流噪声</b>：每一下点火把一段白噪声踢起来（随点火起伏，不是一下一下断开），和脉冲一起进排气管 ——
 *       负荷越大越沙哑。参考录音里带负荷时七八成的声音是这种随机成分，第一版只有正弦，所以薄。</li>
 *   <li><b>排气管</b>（左右各一根，长度略不同）：反馈梳状滤波器给出固定的共振峰，再加集气管、中段的共振和消声器的亥姆霍兹共振；
 *       消声器的低通随转速、负荷打开；压力大时轻微饱和。左排进左声道、右排进右声道，互相串一点；
 *       {@link PulseProfile#bassMonoHz} 以下左右一样。</li>
 *   <li><b>进气</b>：每个缸进气门关时一个脉冲进进气腔、进气道的共振；负荷大时进气的呼啸。</li>
 *   <li><b>机械增压</b>（双螺杆，曲轴用皮带带着转）：叶片每扫过出口一下挤出去一股高压气。啸叫就是这一下一下的
 *       （接近锯齿波，两个转子各自转一圈的边带，进气脉冲的调制，皮带的细小抖动）；这一股一股挤过窄的出口通道是气流声
 *       （左右两路不相关），进口吸气是带阵风的风声。都跟着转速、高转长得快；增压随负荷，松油门旁通阀打开，
 *       啸叫掉下来、「呼」一声。</li>
 *   <li><b>气门落座、燃烧</b>：每个缸的气门关上「嗒」一下、燃烧「咔」一下，各过几个高频共振，按左右排摆位置。</li>
 *   <li><b>车厢</b>：几个低频的车身、车厢共振（某个阶次扫过时「嗡」一下）和一个高 Q 的「和鸣」。</li>
 *   <li><b>传动</b>（D 挡、车在走）：变速箱齿轮的啸叫（每个挡阶次不同）、传动轴的二阶、差速器的啸叫（跟着车速）；
 *       倒挡齿轮的啸叫、起动机照经典风格（{@link EngineVoice}）。</li>
 *   <li><b>收油</b>（断油）：每一下只剩泵气、起伏更大，偶尔轻轻「突」一下，急松油门时多一点。不做像枪声的放炮。</li>
 *   <li><b>启动</b>：起动机带着转时只有压缩的「噗」；点着的头一下偶尔缺火。</li>
 * </ul>
 *
 * <p>电平、音量逐样本平滑，最后软限幅，峰值不超过 {@link EngineVoice#CEILING}。</p>
 */
final class PulseVoice implements Voice {

    /** 激励的环形缓冲（样本，2 的幂）：事件往后写（歧管、随机偏移），每个样本读走当前这一格。 */
    private static final int RING = 2048;
    private static final int MASK = RING - 1;
    /** 最多往后排多远（样本）。 */
    private static final float MAX_DELAY = RING - 64;
    /** 排气管的延迟线（样本，2 的幂，够最低的共振基频用）。 */
    private static final int PIPE = 1024;
    private static final int PIPE_MASK = PIPE - 1;

    private static final int EXHAUST = 0;
    private static final int EXHAUST_CLOSE = 1;
    private static final int INTAKE_CLOSE = 2;
    private static final int BURN = 3;

    /** 左排的缸在左声道占几成（气门、燃烧的声音）。 */
    static final float PAN = 0.8f;
    /** 进气、增压、车厢共振这些居中的声音进左右各多少。 */
    static final float CENTER = 0.7f;
    /** 起动机带着转时，每一下压缩是燃烧的几成。 */
    static final float CRANK_LEVEL = 0.15f;
    /** 点着那一刻起，头几圈缺火的机会（随 {@link #CATCH_SEC} 消失）。 */
    static final float MISFIRE = 0.3f;
    static final float CATCH_SEC = 0.25f;
    /** 进气脉冲落下去的曲轴角、起来的时间。 */
    static final float INTAKE_DECAY_DEG = 30f;
    static final float INTAKE_RISE_MS = 1f;
    /** 松油门：负荷比最近的峰值（{@link #LIFT_PEAK_SEC} 慢慢回落）掉了这么多，或者掉到一半以下。 */
    static final float LIFT_PEAK_SEC = 1.5f;
    static final float LIFT_DROP = 0.25f;
    /** 机械增压旁通阀打开的「呼」（dB、多久淡掉、带通）。 */
    static final float WHOOSH_DB = -20f;
    static final float WHOOSH_SEC = 0.3f;
    static final float WHOOSH_HZ = 1200f;
    /** 啸叫的每次谐波到 {@link #WHINE_TOP_HZ} − {@link #WHINE_FADE_HZ}（Hz）开始渐隐，到 {@link #WHINE_TOP_HZ} 没了。 */
    static final float WHINE_TOP_HZ = 8000f;
    static final float WHINE_FADE_HZ = 2000f;
    /** 阳转子那份边带的相位（周）：两个转子的差别对不齐。 */
    static final double MALE_PHASE = 1.3 / (2.0 * Math.PI);
    /** 不增压时出口的气流还剩几成；松开油门时进口还有几成流量。 */
    static final float JET_FREE = 0.15f;
    static final float INLET_FREE = 0.25f;
    /** 一股气的形状 (½ + ½ cos)³ 的平均值：减掉它，一股一股的起伏平均为 0。 */
    static final float PUFF_MEAN = 0.3125f;
    static final float GEAR_SEC = 0.05f;
    static final float CABIN_LOWPASS_HZ = 200f;
    /** 一个「突」在脉冲之后随机晚多少（样本以内）。 */
    static final float POP_SPREAD = 20f;

    private final PulseProfile p;
    private final float rate;

    // ---- 曲轴和事件（一圈 720° 里按角度排好）
    private final float[] evAngle;
    private final int[] evKind;
    private final int[] evCyl;
    private int nextEvent;
    private double theta;
    private double cycleBase;
    private double nextAngle;
    /** 每个缸（下标 = 缸号 − 1）在哪一排、和同一排上一个点火隔多少度、歧管多走多少样本。 */
    private final int[] bankOf;
    private final float[] gapDeg;
    private final float[] headerSamples;
    private final float[] bankSamples;

    // ---- 激励
    private final float[][] exc = new float[2][RING];
    private final float[][] kick = new float[2][RING];
    private final float[][] pop = new float[2][RING];
    private final float[][] tick = new float[2][RING];
    private final float[][] knock = new float[2][RING];
    private final float[] intake = new float[RING];
    private int cursor;

    // ---- 这一块里事件用的量（每块算一次）
    private float evCombustion;
    private float evSigma;
    private float evMisfire;
    private float evPop;
    private float evRecovery;
    private float evJitter;
    private float evSamplesPerDeg;
    private float evRasp;
    private float evTick;
    private float evKnock;
    private float evIntake;

    // ---- 排气（每排一套）
    private final float[] pulseDecay = new float[2];
    private final float[] pulseRise = new float[2];
    private final float[] popDecay = new float[2];
    private final float[] popRise = new float[2];
    private final float[] envelope = new float[2];
    private final float[] tilt = new float[2];
    private final Biquad[] noiseHighpass = new Biquad[2];
    private final Biquad[] noisePeak = new Biquad[2];
    private final float[][] pipe = new float[2][PIPE];
    private final int[] pipeDelay = new int[2];
    private final float[] pipeLoss = new float[2];
    private int pipeWrite;
    private final Biquad[][] formants = new Biquad[2][];
    private final Biquad[] muffler = new Biquad[2];
    private final Biquad[] radiation = new Biquad[2];
    private final float[] bankOut = new float[2];
    private final float tiltK;
    private final float tiltNorm;
    private final float pipeK;
    private final float popDecayA;
    private final float popRiseA;

    // ---- 进气
    private float intakeDecay;
    private float intakeRise;
    private final Biquad plenum;
    private final Biquad runner;
    private final Biquad roar;
    private float roarNow;

    // ---- 增压
    private final boolean supercharged;
    private final float[] harmonics;
    private double lobePhase;
    private double femalePhase;
    private double malePhase;
    /** 低通过的噪声：转速的抖动、进口的阵风。 */
    private float wobbleNoise;
    private float gustNoise;
    private final float wobbleK;
    private final float wobbleNorm;
    private final float gustK;
    private final float gustNorm;
    private float boost;
    private float whoosh;
    private float whineNow;
    private float jetNow;
    private float inletNow;
    private float whooshNow;
    private final Biquad[][] jetBands = new Biquad[2][];
    private final float[] jetGains;
    private final Biquad[] airLowpass = new Biquad[2];
    private final Biquad inletBand;
    private final Biquad whooshBand;
    private float loadPeak;

    // ---- 气门、缸体、车厢
    private final Biquad[][] tickModes = new Biquad[2][];
    private final Biquad[][] knockModes = new Biquad[2][];
    private final Biquad cabinLowpass;
    private final Biquad[] cabinModes;
    private final Biquad[] sympathetic;

    // ---- 传动、倒挡、起动机
    private double gearPhase;
    private double diffPhase;
    private double shaftPhase;
    private double reversePhase;
    private double starterPhase;
    private float gearNow;
    private float diffNow;
    private float shaftNow;
    private float reverseNow;
    private float starterNow;

    // ---- 输出
    private final Biquad sideHighpass;
    private final Biquad[] highpass = new Biquad[2];
    private final Biquad[] tone = new Biquad[2];
    private float toneSetHz = -1f;
    private final float ampK;
    private final float gainK;
    private final float gearK;
    private float rpmNow = -1f;
    private float levelNow;
    private float gainNow;
    private float catchEnv;
    private float starterPrev;
    private int noise = 0x2545F491;

    PulseVoice(PulseProfile profile, float sampleRate) {
        this.p = profile;
        this.rate = sampleRate;
        int n = profile.firing.length;
        int cylinders = profile.trim.length;
        bankOf = new int[cylinders];
        gapDeg = new float[cylinders];
        headerSamples = new float[cylinders];
        for (int c : profile.firing) {
            bankOf[c - 1] = c % 2 == 1 ? 0 : 1;
        }
        // 同一排上一个点火隔多少度：点火顺序定死的
        for (int k = 0; k < n; k++) {
            int c = profile.firing[k];
            int j = 1;
            while (bankOf[profile.firing[((k - j) % n + n) % n] - 1] != bankOf[c - 1]) {
                j++;
            }
            gapDeg[c - 1] = 720f * j / n;
            headerSamples[c - 1] = profile.headerMs[c - 1] * 1e-3f * sampleRate;
        }
        bankSamples = new float[] {profile.bankMs[0] * 1e-3f * sampleRate, profile.bankMs[1] * 1e-3f * sampleRate};
        // 事件表：每个缸四件事，按角度排好
        int count = 4 * n;
        evAngle = new float[count];
        evKind = new int[count];
        evCyl = new int[count];
        float[] offsets = {profile.exhaustOpenDeg, profile.exhaustCloseDeg, profile.intakeCloseDeg, profile.burnDeg};
        int[] kinds = {EXHAUST, EXHAUST_CLOSE, INTAKE_CLOSE, BURN};
        int e = 0;
        for (int k = 0; k < n; k++) {
            float tdc = 720f * k / n;
            for (int j = 0; j < 4; j++) {
                evAngle[e] = (tdc + offsets[j]) % 720f;
                evKind[e] = kinds[j];
                evCyl[e] = profile.firing[k];
                e++;
            }
        }
        sortEvents();
        nextEvent = 0;
        nextAngle = evAngle[0];

        tiltK = onePole(profile.raspTiltHz);
        tiltNorm = (float) (1.0 / Math.sqrt(tiltK / (2.0 - tiltK)));
        pipeK = onePole(profile.pipeLossHz);
        popDecayA = perSample(profile.popDecayMs * 1e-3f);
        popRiseA = perSample(profile.popRiseMs * 1e-3f);
        for (int b = 0; b < 2; b++) {
            noiseHighpass[b] = Biquad.highpass(profile.raspHighpassHz, 0.7071f, sampleRate);
            noisePeak[b] = Biquad.peaking(profile.raspPeakHz, profile.raspPeakDb, profile.raspPeakQ, sampleRate);
            pipeDelay[b] = Math.max(1, Math.min(PIPE - 1, Math.round(sampleRate / profile.pipeHz[b])));
            int f = profile.formantHz.length;
            formants[b] = new Biquad[f + 1];
            for (int k = 0; k < f; k++) {
                formants[b][k] = Biquad.peaking(profile.formantHz[k], profile.formantDb[k], profile.formantQ[k], sampleRate);
            }
            formants[b][f] = Biquad.peaking(profile.helmholtzHz[b], profile.helmholtzDb, profile.helmholtzQ, sampleRate);
            muffler[b] = Biquad.lowpass(profile.mufflerIdleHz, 0.6f, sampleRate);
            radiation[b] = Biquad.highpass(profile.radiationHz, 0.6f, sampleRate);
            tickModes[b] = bank(profile.tickHz, profile.tickQ);
            knockModes[b] = bank(profile.knockHz, profile.knockQ);
            highpass[b] = Biquad.highpass(EngineVoice.HIGHPASS_HZ, 0.7071f, sampleRate);
            tone[b] = new Biquad();
        }
        plenum = Biquad.bandpass(profile.plenumHz, profile.plenumQ, sampleRate);
        runner = Biquad.bandpass(profile.runnerHz, profile.runnerQ, sampleRate);
        roar = Biquad.bandpass(profile.roarHz, profile.roarQ, sampleRate);
        supercharged = profile.superchargerOrder > 0f;
        harmonics = new float[profile.superchargerHarmonicsDb.length];
        for (int k = 0; k < harmonics.length; k++) {
            harmonics[k] = EngineVoice.dbToLin(profile.superchargerHarmonicsDb[k]);
        }
        wobbleK = onePole(profile.superchargerWobbleHz);
        wobbleNorm = (float) (1.0 / Math.sqrt(wobbleK / (2.0 - wobbleK)));
        gustK = onePole(profile.gustHz);
        gustNorm = (float) (1.0 / Math.sqrt(gustK / (2.0 - gustK)));
        jetGains = new float[profile.jetHz.length];
        for (int k = 0; k < jetGains.length; k++) {
            jetGains[k] = EngineVoice.dbToLin(profile.jetGainDb[k]);
        }
        for (int b = 0; b < 2; b++) {
            jetBands[b] = bank(profile.jetHz, profile.jetQ);
            airLowpass[b] = Biquad.lowpass(profile.airLowpassHz, 0.7071f, sampleRate);
        }
        inletBand = Biquad.bandpass(profile.inletHz, profile.inletQ, sampleRate);
        whooshBand = Biquad.bandpass(WHOOSH_HZ, 0.9f, sampleRate);
        cabinLowpass = Biquad.lowpass(CABIN_LOWPASS_HZ, 0.7071f, sampleRate);
        cabinModes = bank(profile.cabinHz, profile.cabinQ);
        sympathetic = bank(profile.sympatheticHz, profile.sympatheticQ);
        sideHighpass = Biquad.highpass(profile.bassMonoHz, 0.7071f, sampleRate);
        ampK = 1f - (float) Math.exp(-1.0 / (EngineVoice.AMP_SMOOTH_SEC * sampleRate));
        gainK = 1f - (float) Math.exp(-1.0 / (EngineVoice.GAIN_SMOOTH_SEC * sampleRate));
        gearK = 1f - (float) Math.exp(-1.0 / (GEAR_SEC * sampleRate));
    }

    @Override
    public void renderStereo(float[] lr, int frames, VoiceParams params) {
        if (frames <= 0) {
            return;
        }
        float rpmTarget = params.rpm > 0f ? params.rpm : 0f;
        if (rpmNow < 0f) {
            rpmNow = rpmTarget;
        }
        if (params.gain <= 0f && gainNow < 1e-5f) {
            // 静音着：不算，曲轴停在原地（重新出声时从这里接着走）
            for (int i = 0; i < 2 * frames; i++) {
                lr[i] = 0f;
            }
            gainNow = 0f;
            rpmNow = rpmTarget;
            return;
        }
        PulseProfile p = this.p;
        float dt = frames / rate;
        float load = VirtualEngine.clamp01(params.load);
        float over = VirtualEngine.clamp01(params.overrun);
        float starter = VirtualEngine.clamp01(params.starter);
        if (starterPrev > 0.5f && starter <= 0.5f) {
            catchEnv = 1f;
        }
        starterPrev = starter;
        catchEnv *= VirtualEngine.decay(dt, CATCH_SEC);
        boolean cranking = starter > 0.5f;
        float rpmCalc = Math.max(rpmTarget, 100f);

        // ---- 这一块里的事件
        evCombustion = cranking ? CRANK_LEVEL : 1f - over * (1f - p.pump);
        evSigma = mix(p.sigmaIdle, p.sigmaLoad, load) + over * p.sigmaOverrun;
        evMisfire = MISFIRE * catchEnv;
        evPop = p.popMax * VirtualEngine.clamp01(0.1f * over + 0.6f * params.burble);
        evRecovery = mix(p.recoveryIdle, p.recoveryLoad, load);
        evJitter = mix(p.jitterIdleDeg, p.jitterLoadDeg, load);
        evSamplesPerDeg = rate / (6f * Math.max(rpmTarget, 50f));
        evRasp = EngineVoice.dbToLin(mix(p.raspIdleDb, p.raspLoadDb, load));
        evTick = EngineVoice.dbToLin(p.tickDb) * (0.4f + 0.6f * Math.min(rpmTarget / 6000f, 1f));
        evKnock = EngineVoice.dbToLin(p.knockDb) * (0.35f + 0.65f * load) * evCombustion;
        evIntake = EngineVoice.dbToLin(mix(p.intakeIdleDb, p.intakeLoadDb, (float) Math.pow(load, 1.2)));

        // ---- 排气的系数
        float decaySec = p.decayDeg / (6f * rpmCalc);
        float riseSec = mix(p.riseIdleMs, p.riseLoadMs, load) * 1e-3f * (1f + over);
        if (riseSec > decaySec * 0.5f) {
            riseSec = decaySec * 0.5f;
        }
        float ad = perSample(decaySec);
        float ar = perSample(riseSec);
        double tp = Math.log(decaySec / riseSec) * decaySec * riseSec / (decaySec - riseSec);
        float pulseNorm = (float) (1.0 / (Math.exp(-tp / decaySec) - Math.exp(-tp / riseSec)));
        float ae = perSample(p.raspDecayDeg / (6f * rpmCalc));
        float bright = 1f + params.brightDb / 6f;
        float mufflerHz = mix(p.mufflerIdleHz, p.mufflerOpenHz, VirtualEngine.clamp01((rpmTarget - 700f) / 2300f))
                * (1f + p.mufflerLoad * load) * bright;
        muffler[0].setLowpass(mufflerHz, 0.6f, rate);
        muffler[1].setLowpass(mufflerHz, 0.6f, rate);
        float drive = mix(p.driveIdle, p.driveLoad, load);
        float raspLevel = Math.max(evRasp, EngineVoice.dbToLin(p.raspOverrunDb) * over);
        float floorShare = Math.min(1f, p.raspFloor + 0.4f * over);
        float floor = floorShare * raspLevel * (cranking ? 0.2f : 1f);
        float kickShare = 1f - floorShare;

        // ---- 进气
        float aiD = perSample(INTAKE_DECAY_DEG / (6f * rpmCalc));
        float aiR = perSample(INTAKE_RISE_MS * 1e-3f);
        float roarTarget = EngineVoice.dbToLin(mix(p.roarIdleDb, p.roarLoadDb, (float) Math.pow(load, 1.5))
                + params.intakeBoostDb);

        // ---- 增压：松油门 = 负荷比最近的峰值掉了不少
        loadPeak = Math.max(load, loadPeak * VirtualEngine.decay(dt, LIFT_PEAK_SEC));
        float drop = loadPeak - load;
        boolean lift = drop > LIFT_DROP || (loadPeak > 0.3f && load < 0.5f * loadPeak);
        if (lift) {
            loadPeak = load;
        }
        float whineTarget = 0f;
        float jetTarget = 0f;
        float inletTarget = 0f;
        float whooshTarget = 0f;
        if (supercharged) {
            boost += (load - boost) * VirtualEngine.follow(dt, p.superchargerSec);
            if (lift && boost > 0.3f) {
                whoosh = Math.max(whoosh, boost * Math.min(1f, drop * 2f));
            }
            whoosh *= VirtualEngine.decay(dt, WHOOSH_SEC);
            float revs = Math.min(rpmTarget / 6000f, 1.2f);
            float speed = (float) Math.pow(revs, p.superchargerRpmPow);
            whineTarget = EngineVoice.dbToLin(p.superchargerDb)
                    * (p.superchargerFree + (1f - p.superchargerFree) * boost) * speed;
            jetTarget = EngineVoice.dbToLin(p.jetDb) * (JET_FREE + (1f - JET_FREE) * boost) * speed;
            float flow = revs * (INLET_FREE + (1f - INLET_FREE) * load);
            inletTarget = EngineVoice.dbToLin(p.inletDb) * flow * (float) Math.sqrt(flow);
            whooshTarget = EngineVoice.dbToLin(WHOOSH_DB) * whoosh;
        }

        // ---- 传动、倒挡、起动机
        boolean driving = params.drive && params.speedKmh > 1f;
        float torque = Math.max(load, 0.6f * over);
        float gearOrder = params.gearIndex >= 0
                ? p.gearOrders[Math.min(params.gearIndex, p.gearOrders.length - 1)] : p.cvtOrder;
        float v = Math.max(params.speedKmh, 0f);
        float gearTarget = driving ? EngineVoice.dbToLin(p.gearDb) * torque : 0f;
        float diffTarget = driving ? EngineVoice.dbToLin(p.diffDb) * (0.3f + 0.7f * torque) * VirtualEngine.clamp01(v / 20f) : 0f;
        float shaftTarget = driving ? EngineVoice.dbToLin(p.shaftDb) * (0.3f + 0.7f * torque) * VirtualEngine.clamp01(v / 30f) : 0f;
        float diffStep = p.diffHzPerKmh * v / rate;
        float shaftStep = p.shaftHzPerKmh * v / rate;
        float reverseTarget = EngineVoice.dbToLin(EngineVoice.WHINE_DB) * VirtualEngine.clamp01(params.whineAmp);
        float starterTarget = EngineVoice.dbToLin(EngineVoice.STARTER_DB) * starter;
        float starterStep = EngineVoice.STARTER_WHINE_HZ * (0.8f + 0.2f * starter) / rate;

        // ---- 输出
        float post = EngineVoice.dbToLin(p.rpmLoudDb * VirtualEngine.clamp01((rpmTarget - 800f) / 5200f)) * p.norm;
        boolean toneOn = params.toneHz < EngineVoice.TONE_BYPASS_HZ;
        if (toneOn && Math.abs(params.toneHz - toneSetHz) > 1f) {
            tone[0].setLowpass(params.toneHz, 0.7071f, rate);
            tone[1].setLowpass(params.toneHz, 0.7071f, rate);
            toneSetHz = params.toneHz;
        }
        float rpmStep = (rpmTarget - rpmNow) / frames;

        for (int i = 0; i < frames; i++) {
            float r = rpmNow + rpmStep * (i + 1);
            advance(6.0 * r / rate);
            int cur = cursor;

            // 排气：每排一套
            for (int b = 0; b < 2; b++) {
                float x = exc[b][cur];
                exc[b][cur] = 0f;
                pulseDecay[b] = ad * pulseDecay[b] + x;
                pulseRise[b] = ar * pulseRise[b] + x;
                float pulse = (pulseDecay[b] - pulseRise[b]) * pulseNorm;
                float xp = pop[b][cur];
                pop[b][cur] = 0f;
                popDecay[b] = popDecayA * popDecay[b] + xp;
                popRise[b] = popRiseA * popRise[b] + xp;
                float k = kick[b][cur];
                kick[b][cur] = 0f;
                envelope[b] = ae * envelope[b] + k;
                float w = white();
                tilt[b] += tiltK * (w - tilt[b]);
                float nz = noisePeak[b].process(noiseHighpass[b].process(tilt[b] * tiltNorm + p.raspAir * w));
                float u = saturate(pulse + (popDecay[b] - popRise[b]) + (envelope[b] * kickShare + floor) * nz, drive);
                float delayed = pipe[b][(pipeWrite - pipeDelay[b]) & PIPE_MASK];
                pipeLoss[b] += pipeK * (delayed - pipeLoss[b]);
                float y = u + p.pipeFeedback * pipeLoss[b];
                pipe[b][pipeWrite] = y;
                for (Biquad f : formants[b]) {
                    y = f.process(y);
                }
                y = muffler[b].process(y);
                bankOut[b] = radiation[b].process(y);
            }
            pipeWrite = (pipeWrite + 1) & PIPE_MASK;
            float left = bankOut[0];
            float right = bankOut[1];

            // 进气
            float xi = intake[cur];
            intake[cur] = 0f;
            intakeDecay = aiD * intakeDecay + xi;
            intakeRise = aiR * intakeRise + xi;
            float ip = intakeDecay - intakeRise;
            roarNow += (roarTarget - roarNow) * ampK;
            float center = plenum.process(ip) * 1.5f + runner.process(ip) + roar.process(white()) * roarNow;

            // 增压：叶片扫过出口的啸叫、挤过出口通道的一股一股气流（左右两路）、进口的风声、松油门的「呼」
            float jetL = 0f;
            float jetR = 0f;
            if (supercharged) {
                wobbleNoise += wobbleK * (white() - wobbleNoise);
                float hz = p.superchargerOrder * r / 60f * (1f + p.superchargerWobble * wobbleNorm * wobbleNoise);
                lobePhase += hz / rate;
                if (lobePhase >= 1.0) {
                    lobePhase -= 1.0;
                }
                femalePhase += hz / p.rotorLobes[0] / rate;
                if (femalePhase >= 1.0) {
                    femalePhase -= 1.0;
                }
                malePhase += hz / p.rotorLobes[1] / rate;
                if (malePhase >= 1.0) {
                    malePhase -= 1.0;
                }
                whineNow += (whineTarget - whineNow) * ampK;
                jetNow += (jetTarget - jetNow) * ampK;
                inletNow += (inletTarget - inletNow) * ampK;
                whooshNow += (whooshTarget - whooshNow) * ampK;
                float whine = 0f;
                for (int k = 0; k < harmonics.length; k++) {
                    float fade = (WHINE_TOP_HZ - (k + 1) * hz) / WHINE_FADE_HZ;
                    if (fade <= 0f) {
                        break;
                    }
                    whine += harmonics[k] * Math.min(fade, 1f) * EngineVoice.sin((k + 1) * lobePhase);
                }
                float rotor = 1f + p.rotorAm[0] * EngineVoice.sin(femalePhase)
                        + p.rotorAm[1] * EngineVoice.sin(malePhase + MALE_PHASE);
                float am = 1f + p.superchargerAm * EngineVoice.sin(theta / 90.0);
                float c = 0.5f + 0.5f * EngineVoice.sin(lobePhase + 0.25);
                float push = 1f + p.jetPulse * (c * c * c / PUFF_MEAN - 1f);
                jetL = airLowpass[0].process(jets(jetBands[0], white() * push)) * jetNow;
                jetR = airLowpass[1].process(jets(jetBands[1], white() * push)) * jetNow;
                gustNoise += gustK * (white() - gustNoise);
                center += whine * rotor * am * whineNow
                        + inletBand.process(white()) * inletNow * (1f + p.gust * gustNorm * gustNoise)
                        + whooshBand.process(white()) * whooshNow;
            }

            // 车厢共振、和鸣
            float both = left + right;
            float low = cabinLowpass.process(both);
            for (int k = 0; k < cabinModes.length; k++) {
                center += p.cabinGain[k] * cabinModes[k].process(low);
            }
            for (int k = 0; k < sympathetic.length; k++) {
                center += p.sympatheticGain[k] * sympathetic[k].process(both);
            }

            // 气门落座、燃烧
            float tickL = modes(tickModes[0], tick[0], cur);
            float tickR = modes(tickModes[1], tick[1], cur);
            float knockL = modes(knockModes[0], knock[0], cur);
            float knockR = modes(knockModes[1], knock[1], cur);

            float l = left + p.cross * right + CENTER * (center + jetL) + tickL + knockL;
            float rr = right + p.cross * left + CENTER * (center + jetR) + tickR + knockR;
            float mid = 0.5f * (l + rr);
            float side = sideHighpass.process(0.5f * (l - rr));
            levelNow += (params.level - levelNow) * gainK;
            float g = post * levelNow;
            l = (mid + side) * g;
            rr = (mid - side) * g;

            // 传动、倒挡、起动机（只跟总音量）
            float extra = 0f;
            gearNow += (gearTarget - gearNow) * gearK;
            diffNow += (diffTarget - diffNow) * ampK;
            shaftNow += (shaftTarget - shaftNow) * ampK;
            if (gearNow > 1e-6f) {
                gearPhase += gearOrder * r / 60f / rate;
                if (gearPhase >= 1.0) {
                    gearPhase -= 1.0;
                }
                extra += gearNow * (EngineVoice.sin(gearPhase) + 0.3f * EngineVoice.sin(2.0 * gearPhase));
            }
            if (diffNow > 1e-6f) {
                diffPhase += diffStep;
                if (diffPhase >= 1.0) {
                    diffPhase -= 1.0;
                }
                extra += diffNow * EngineVoice.sin(diffPhase);
            }
            if (shaftNow > 1e-6f) {
                shaftPhase += shaftStep;
                if (shaftPhase >= 1.0) {
                    shaftPhase -= 1.0;
                }
                extra += shaftNow * EngineVoice.sin(shaftPhase);
            }
            reverseNow += (reverseTarget - reverseNow) * ampK;
            if (reverseNow > 1e-5f && params.whineHz > 0f) {
                reversePhase += params.whineHz / rate;
                if (reversePhase >= 1.0) {
                    reversePhase -= 1.0;
                }
                extra += reverseNow * (EngineVoice.sin(reversePhase) + 0.3f * EngineVoice.sin(2.0 * reversePhase));
            }
            starterNow += (starterTarget - starterNow) * ampK;
            if (starterNow > 1e-5f) {
                starterPhase += starterStep;
                if (starterPhase >= 1.0) {
                    starterPhase -= 1.0;
                }
                float compression = 0.55f + 0.45f * EngineVoice.sin(theta / 90.0);
                float buzz = EngineVoice.sin(starterPhase) + 0.35f * EngineVoice.sin(2.0 * starterPhase) + 0.25f * nextNoise();
                extra += starterNow * compression * buzz;
            }
            l += extra;
            rr += extra;

            if (toneOn) {
                l = tone[0].process(l);
                rr = tone[1].process(rr);
            }
            l = highpass[0].process(l);
            rr = highpass[1].process(rr);
            gainNow += (params.gain - gainNow) * gainK;
            lr[2 * i] = EngineVoice.limit(l * gainNow);
            lr[2 * i + 1] = EngineVoice.limit(rr * gainNow);
            cursor = (cursor + 1) & MASK;
        }
        rpmNow = rpmTarget;
    }

    /** 缸号 {@code cylinder} 和同一排上一个点火隔多少曲轴角（点火顺序定死的；测试看它算对没有）。 */
    float sameBankGapDeg(int cylinder) {
        return gapDeg[cylinder - 1];
    }

    /** 曲轴往前转 {@code degrees}：经过的事件按发生的位置（样本以内的小数）触发。 */
    private void advance(double degrees) {
        if (!(degrees > 0.0)) {
            return;
        }
        double end = theta + degrees;
        while (nextAngle <= end) {
            float at = 1f - (float) ((end - nextAngle) / degrees);
            fire(evKind[nextEvent], evCyl[nextEvent], at);
            nextEvent++;
            if (nextEvent == evAngle.length) {
                nextEvent = 0;
                cycleBase += 720.0;
            }
            nextAngle = cycleBase + evAngle[nextEvent];
        }
        theta = end;
        if (theta >= 720.0) {
            theta -= 720.0;
            cycleBase -= 720.0;
            nextAngle -= 720.0;
        }
    }

    /** 一件事发生在当前样本往后 {@code at}（0–1）个样本处。 */
    private void fire(int kind, int cylinder, float at) {
        int c = cylinder - 1;
        int b = bankOf[c];
        float pan = b == 0 ? PAN : 1f - PAN;
        if (kind == EXHAUST) {
            float variation = Math.max(0f, 1f + evSigma * gauss());
            float level = evCombustion;
            if (evMisfire > 0f && random01() < evMisfire) {
                level = p.pump;
            }
            float recovery = Math.max(0.15f, 1f - evRecovery * (float) Math.exp(-gapDeg[c] / p.recoveryDeg));
            float a = p.trim[c] * recovery * variation * level;
            // 时机：歧管、排气管长短 + 随机偏移（N(0, 抖动) 度，围着固定的 3 倍抖动，保证不往回排）
            float delay = headerSamples[c] + bankSamples[b] + evJitter * (3f + gauss()) * evSamplesPerDeg;
            float q = at + Math.max(0f, Math.min(MAX_DELAY, delay));
            add(exc[b], q, a);
            add(kick[b], q, a * evRasp * (0.4f + 0.6f * level));
            if (evPop > 0f && random01() < evPop) {
                add(pop[b], q + random01() * POP_SPREAD, p.popGain * (0.6f + 0.8f * random01()));
                add(kick[b], q, 3f * evRasp);
            }
        } else if (kind == EXHAUST_CLOSE || kind == INTAKE_CLOSE) {
            float a = evTick * (0.6f + 0.4f * random01());
            add(tick[0], at, a * pan);
            add(tick[1], at, a * (1f - pan));
            if (kind == INTAKE_CLOSE) {
                add(intake, at, evIntake * (0.9f + 0.2f * random01()));
            }
        } else {
            float a = evKnock * (0.8f + 0.4f * random01());
            add(knock[0], at, a * pan);
            add(knock[1], at, a * (1f - pan));
        }
    }

    /** 在当前样本往后 {@code q} 个样本处加一下（落在两个样本之间就按距离分开）。 */
    private void add(float[] ring, float q, float amp) {
        int i = (int) q;
        float w = q - i;
        ring[(cursor + i) & MASK] += amp * (1f - w);
        ring[(cursor + i + 1) & MASK] += amp * w;
    }

    /** 出口的一路气流：过几个通道的共振，按各自的电平加起来。 */
    private float jets(Biquad[] bands, float x) {
        float y = 0f;
        for (int k = 0; k < bands.length; k++) {
            y += jetGains[k] * bands[k].process(x);
        }
        return y;
    }

    /** 读走这一格的激励，过一组共振，加起来。 */
    private static float modes(Biquad[] bank, float[] ring, int cur) {
        float x = ring[cur];
        ring[cur] = 0f;
        float y = 0f;
        for (Biquad m : bank) {
            y += m.process(x);
        }
        return y;
    }

    private Biquad[] bank(float[] hz, float[] q) {
        Biquad[] out = new Biquad[hz.length];
        for (int k = 0; k < hz.length; k++) {
            out[k] = Biquad.bandpass(hz[k], q[k], rate);
        }
        return out;
    }

    private void sortEvents() {
        for (int i = 1; i < evAngle.length; i++) {
            float a = evAngle[i];
            int k = evKind[i];
            int c = evCyl[i];
            int j = i - 1;
            while (j >= 0 && evAngle[j] > a) {
                evAngle[j + 1] = evAngle[j];
                evKind[j + 1] = evKind[j];
                evCyl[j + 1] = evCyl[j];
                j--;
            }
            evAngle[j + 1] = a;
            evKind[j + 1] = k;
            evCyl[j + 1] = c;
        }
    }

    /** 饱和：小信号原样，压力大时圆滑地压住（{@code drive} 越大越早压）。 */
    static float saturate(float x, float drive) {
        if (drive <= 1e-3f) {
            return x;
        }
        float y = x * drive;
        float t;
        if (y >= 3f) {
            t = 1f;
        } else if (y <= -3f) {
            t = -1f;
        } else {
            float y2 = y * y;
            t = y * (27f + y2) / (27f + 9f * y2);
        }
        return t / drive;
    }

    private float perSample(float tauSec) {
        return (float) Math.exp(-1.0 / (Math.max(tauSec, 1e-6f) * rate));
    }

    private float onePole(float hz) {
        return 1f - (float) Math.exp(-2.0 * Math.PI * hz / rate);
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** 白噪声，方差 1。 */
    private float white() {
        return nextNoise() * 1.7320508f;
    }

    /** 近似正态（四个均匀数相加），方差 1。 */
    private float gauss() {
        return (nextNoise() + nextNoise() + nextNoise() + nextNoise()) * 0.8660254f;
    }

    private float random01() {
        return (nextNoise() + 1f) * 0.5f;
    }

    /** xorshift32，[−1, 1)。可复现（测试靠它）。 */
    private float nextNoise() {
        int x = noise;
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        noise = x;
        return x * (1f / 2147483648f);
    }
}
