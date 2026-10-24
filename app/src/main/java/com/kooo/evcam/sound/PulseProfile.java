package com.kooo.evcam.sound;

/**
 * 逐缸合成（{@link PulseVoice}）的一种风格：缸怎么排、怎么点火，排气脉冲、气流噪声、排气管和消声器、进气、增压、气门、缸体、
 * 车厢、传动各是什么样。只是数据，再加一种风格 = 再加一份数据。
 *
 * <p>数值的来历在 docs/local/drive-sound-plan.md §12：结构照真机（交叉平面 V8 的点火顺序、左右两排、
 * 同一排点火间隔 90/180/270° 的遮蔽），频谱照一台机械增压交叉平面 V8 的录音量出来的（阶次、固定共振峰、
 * 随机成分占比、高频滚降）去对；上车按耳朵调。项目所有者 2026-10-21 偏好这一类 V8 的声音取向。</p>
 */
final class PulseProfile {

    // ---- 发动机
    /** 点火顺序（缸号，从 1 起）；单号缸在左排，双号缸在右排。 */
    final int[] firing;
    /** 每个缸的脉冲强弱（下标 = 缸号 − 1）：制造上的细小差别。 */
    final float[] trim;
    /** 每个缸的排气歧管比最短的那根多走多久（毫秒）。 */
    final float[] headerMs;
    /** 左、右两排的排气管多走多久（毫秒）。 */
    final float[] bankMs;
    /** 上止点之后多少度：排气门开（排气脉冲）、排气门关、进气门关（气门落座、进气脉冲）、燃烧（缸体的「咔」）。 */
    final float exhaustOpenDeg;
    final float exhaustCloseDeg;
    final float intakeCloseDeg;
    final float burnDeg;

    // ---- 排气脉冲：双指数（起得快、按曲轴角落下去）
    final float riseIdleMs;
    final float riseLoadMs;
    final float decayDeg;
    /** 同一排上一个脉冲留下的压力把这一个压低多少：1 − m × e^(−间隔 / {@link #recoveryDeg})。 */
    final float recoveryDeg;
    final float recoveryIdle;
    final float recoveryLoad;
    /** 每一下的随机起伏（标准差，怠速 / 满负荷 / 收油时另加）。 */
    final float sigmaIdle;
    final float sigmaLoad;
    final float sigmaOverrun;
    /** 每一下的时机随机偏多少（曲轴角的标准差，怠速 / 满负荷）。 */
    final float jitterIdleDeg;
    final float jitterLoadDeg;

    // ---- 气流噪声（「沙哑」）：白噪声 × 每一下点火踢起来的包络，进同一根排气管
    final float raspIdleDb;
    final float raspLoadDb;
    /**
     * 收油（断油、只泵气）时的气流噪声（dB），收油时沙哑从怠速的 {@link #raspIdleDb} 往它靠。项目所有者 2026-10-22：松油门后「呼」的
     * 声音有点大 —— 量下来松油门后几乎全是它（3500 rpm 时就是整个声音），旁通阀那一声「呼」反而低 20–30 dB。原来取怠速、收油两个
     * 里大的（收油那个 −18 dB 比怠速的 −16 小，等于没起作用）；改成往收油的靠，−21 dB：松到底时沙哑少 5 dB。
     */
    final float raspOverrunDb;
    /** 包络里不随点火起伏的那一份。 */
    final float raspFloor;
    final float raspDecayDeg;
    final float raspHighpassHz;
    /**
     * 这个频率以下平、以上 −6 dB/倍频程；再留一点原样的白噪声（{@link #raspAir}）。拐点随转速（流速）往上走：
     * × (rpm / 2000)^{@link #raspTiltRpmPow}。
     */
    final float raspTiltHz;
    final float raspTiltRpmPow;
    final float raspAir;
    /**
     * 沙哑随流量（转速）变：每翻一倍转速多几 dB（带负荷、收油各一个），以 {@link #raspRefRpm} 为准，最多多 {@link #raspRpmMaxDb}。
     * 参考车的录音：收油时低转很闷、高转一下亮起来（每翻一倍 +12 dB）；带负荷时变亮主要靠消声器随转速打开。
     * 封顶 +2 dB（原来 +6）：参考只录到 2850 rpm，再往上是把那一段变调放，不再变亮；项目所有者 2026-10-22 嫌松油门后的「呼」大。
     */
    final float[] raspRpmDb;
    final float raspRefRpm;
    final float raspRpmMaxDb;
    final float raspPeakHz;
    final float raspPeakDb;
    final float raspPeakQ;

    // ---- 排气管、消声器（每排一套）
    /** 排气管的共振基频（左、右排，Hz）：反馈梳状滤波器，环路里带低通（高频损耗大）。 */
    final float[] pipeHz;
    final float pipeFeedback;
    final float pipeLossHz;
    final float[] formantHz;
    final float[] formantDb;
    final float[] formantQ;
    /** 消声器加尾管的亥姆霍兹共振（左、右排，Hz）。 */
    final float[] helmholtzHz;
    final float helmholtzDb;
    final float helmholtzQ;
    /**
     * 消声器的低通（Hz）= {@link #mufflerHz} × (rpm / 1000)^{@link #mufflerPow}，到 {@link #mufflerTopHz} 为止，满负荷再开
     * {@link #mufflerLoad} 这么多：一直开到红线（项目所有者 2026-10-21），系数照参考车的录音拟合。
     */
    final float mufflerHz;
    final float mufflerPow;
    final float mufflerTopHz;
    final float mufflerLoad;
    final float radiationHz;
    /** 压力大时的饱和（0 = 不饱和；怠速 / 满负荷）。 */
    final float driveIdle;
    final float driveLoad;
    /**
     * 每排串到另一边的量；这个频率以下左右一样（车厢里低频本来就分不出左右）。2026-10-24 为了立体感试过少串（0.25 → 0.12）：
     * 两排点火不均匀，120–250 Hz 左右反相的成分更多（车内满油门左右相关 −0.26 → −0.53），左右相加少 1 dB —— 发虚、变薄，没用；
     * 立体感放在中高频（{@link #widthDb}）。
     */
    final float cross;
    final float bassMonoHz;
    /**
     * 立体感（项目所有者 2026-10-24）：齿轮声的几个来源摆开多远（第 1 个居中，后面的一左一右，等功率摆位的位置 −1…+1）——
     * 齿轮声从壳体四面八方出来，到车里经挡风玻璃、仪表台、A 柱几条路，是一片，不是一个点；几个来源频率差一点，左右相加还是原来的声音。
     */
    final float meshSpread;
    /** 两排的进气腔（各自一个中冷器，在增压器左右两边）摆开多远：每排进气门关上那一下的压力脉动从自己那一边出来。 */
    final float plenumPan;
    /**
     * 中高频左右的差（side）再放大多少 dB：{@link #widthHz} 以上（一阶搁架；头挡住的高频本来就左右差得多）。只放大本来就有的差，
     * 左右相加（单声道）不变；再往下不放大：两排点火不均匀，120–500 Hz 本来就有反相的成分，放大了发虚、相加变薄。
     */
    final float widthDb;
    final float widthHz;
    /** 怠速到 6000 rpm 多响多少（dB）。 */
    final float rpmLoudDb;
    /**
     * 虚拟发动机给的电平取这个次方（车外、车内听感各一个）：踩油门主要变音色、响度变化不大 —— 发动机自己越转越响的那些
     * （燃烧、沙哑、增压器、消声器打开）已经带出好几 dB。项目所有者 2026-10-21 实车：响度差太大，要以层次和丰富程度做真实感，
     * 高速、大油门稍响一点就够；2026-10-22：油门、车速都还原平滑了，误当成急加速、急刹车的少了，响度差可以略放大一些。
     * 车内听感把高频滤掉了，自己带出来的少，次方大一些。升挡断扭时再掉 {@link #cutDipDb}（满负荷）。
     */
    final float levelPow;
    final float cabinLevelPow;
    final float cutDipDb;
    /** 总的倍数：全油门时限幅只压两三 dB（{@link Limiter}）。 */
    final float norm;

    // ---- 进气
    final float intakeIdleDb;
    final float intakeLoadDb;
    final float plenumHz;
    final float plenumQ;
    final float runnerHz;
    final float runnerQ;
    final float roarIdleDb;
    final float roarLoadDb;
    final float roarHz;
    final float roarQ;
    /**
     * 节气门的气流声（项目所有者 2026-10-23：P 挡落到怠速、稳住以后节气门的声音相对更明显；转速高了不是变小，是更快更密、被差不多
     * 频率或更响的声音盖住）：节气门几乎关着时，后面的真空把空气从窄缝里吸进去，缝里的气流到了声速 ——「嘶」，过两级带通（Hz、Q，
     * 每级一样），4 kHz 以上很快落下去：原来一级宽带通（3 kHz、Q 0.6）一直拖到 12 kHz，车外听着是「沙沙」的（项目所有者 2026-10-24；
     * 车外怠速 2.5–12.5 kHz 线和线之间的底噪比没有它时高 5–18 dB）。
     * 每个缸吸一口气就涌一股（进气门关上那一下起，按 {@link #hissDecayMs} 落下去，时间是固定的：怠速时一股一股分得开，转速高了
     * 连成一片）；底下一直有的一层占 {@link #hissFloor}（2026-10-24 起没有：一股一股之间那层连续的嘶声也是「沙沙」）。
     * 缝里是声速的气流，流量只看缝多大、和转速无关：转速高了每一股分到的少
     * （按转速的平方根），总的差不多一样响。节气门开大了缝两边的压差没了，嘶声跟着没了（× (1 − 负荷)²）。参考视频（发动机舱，
     * 怠速 813 rpm）：2–9 kHz 比 200 Hz 以下低 11–16 dB，按点火频率起伏 80 % 以上；4500 rpm 收油时只起伏 30 % 上下。
     * 电平 {@link #hissDb}（dB，怠速、节气门全关，每一股的峰值）。
     */
    final float hissDb;
    final float hissHz;
    final float hissQ;
    final float hissDecayMs;
    final float hissFloor;

    // ---- 机械增压（阶次 0 = 没有）：双螺杆，曲轴用皮带带着转。啸叫的样子照车主偏好的那台的录音（EngineV1）：叶片扫过出口的
    //      基频不响，响的是同步齿轮的啮合声，很纯、只在增压时有；松油门旁通阀打开，齿轮声几乎没了。音高照参考视频那台（项目所有者
    //      2026-10-24 听了试听定的）：叶片 12.03 阶、齿轮声 24.06 阶（叶片频率的 2 倍）一串；原来照 EngineV1 那台是 10.24、30.72（3 倍）。
    //      底下一层挤过窄出口通道的气流声、进口吸气的风声
    /** 叶片扫过出口的阶次（曲轴每转一圈扫过几下；参考视频那台 12.03 = 阴转子 5 叶 × 曲轴的 2.41 倍）。 */
    final float superchargerOrder;
    /**
     * 叶片基频的电平（dB，3000 rpm，和负荷无关）。2026-10-24 换音高以后 −29 → −35：12.03 阶离发动机的 12 阶只差 0.03 阶，两个拍成
     * 一秒一两下的「呜哇」（项目所有者：压低一些）。
     */
    final float lobeDb;
    /**
     * 齿轮啮合是叶片频率的几倍（阴转子上的齿数 / 叶片数）；各次谐波的电平（dB，相对第 2 次），每一行对应 {@link #meshRpm} 的一个
     * 转速（按转速的对数插值，两头照最边上的）。照 EngineV1 那台四个转速的录音：低转 h1 最响、h4 / h5 还在（暗、厚），转速上去
     * h2、h3 上来、h4 / h5 很快没了（亮、利）。项目所有者 2026-10-22：实机不同转速音色略有变化，我们原来只是跟着转速变调。
     */
    final float meshMultiple;
    final float[][] meshHarmonicsDb;
    /**
     * 阴、阳转子各几个叶片（转一圈扫过几下）；叶片之间细小的差别让每个转子每转一圈调制一次啸叫（边带），满增压时调制多深 ——
     * 齿轮受力，转子的偏心、齿距的误差才显出来，不增压时剩 {@link #growlFree}。
     */
    final float[] rotorLobes;
    final float[] rotorAm;
    /** 满增压、3000 rpm 时齿轮声第 2 次的电平（dB）；几个音都随转速的 {@link #toneRpmPow} 次方变（怠速时很轻，和录音一样）。 */
    final float superchargerDb;
    final float toneRpmPow;
    /** 气流声随转速的这个次方变（以 6000 rpm 为准）：低转淡，高转长得快。 */
    final float superchargerRpmPow;
    /**
     * 齿轮声随转速另加的（dB，在 {@link #toneRpmPow} 那条律之上；按转速的对数插值，两头照最边上的）：照 EngineV1 那台带负荷的录音
     * （项目所有者 2026-10-22：起压时机械质感严重不足）—— 1300–2400 rpm 增压时齿轮声很响，比点火阶次只低几 dB；2850 rpm 以上
     * 和点火阶次的比例不再变。
     */
    final float[] meshRpm;
    final float[] meshRpmDb;
    /**
     * 曲轴的扭振带着增压器的转速一起抖（份额）：每次点火曲轴快一下，皮带把它传给增压器 —— 点火频率（4 阶）上一个周期性的、
     * 很小的调频（原厂皮带轮上还有解耦器压着）。原来是随机的抖动（0.6 %、30 Hz 低通）：高次谐波一抖，每根线两边一小片窄噪声，
     * 就是颗粒（项目所有者 2026-10-23：新版在产生颗粒状噪音的临界点上，要着重避免）。
     */
    final float torsion;
    /**
     * 齿轮声不是一个来源：同步齿轮的啮合、转子出口的压力脉动、壳体，各自被皮带、扭振、气流调得不一样 —— 每个来源相对的
     * 一点偏差（份额，个数就是来源数），各自慢慢游移（份额的标准差、低通 Hz），几个来源能量加起来和原来一个一样。
     * 参考那台每一刻都是一小团（±0.5–0.7 %，几个峰此起彼伏）；项目所有者 2026-10-22：一根主线太过突出，应该是层次更多的
     * 一组声音在同步变化。
     */
    final float[] meshVoiceDetune;
    final float meshVoiceWander;
    final float meshVoiceWanderHz;
    /** 旁通阀开着（不增压，齿轮不受力）时齿轮声还剩几成。 */
    final float superchargerFree;
    /**
     * 满增压时被发动机的点火、进气脉冲（4 阶）调制多深：增压器要吃掉一百多马力，皮带上的扭矩跟着每次点火一下一下地变，齿轮、
     * 出口的压力也跟着一下一下；不增压时剩 {@link #growlFree}。
     */
    final float superchargerAm;
    final float superchargerSec;
    /**
     * 负载时那层机械感（项目所有者 2026-10-24：负载和松开油门以后的音色要有明显差异，负载时有更重的机械感）：转子的边带
     * （{@link #rotorAm}）、4 阶的调制（{@link #superchargerAm}）在不增压时还剩几成。齿轮声、出口的压力脉动都跟着调，增压时
     * 粗、带着低吼；松开以后的啸叫（{@link #decelDb}）不调，干净。换成参考视频那台的音高以后，增压器的线都离发动机的整数阶只有
     * 0.25 %（12.03 对 12），光靠这几根线分不出来：调出来的边带落在整数阶之间（阴转子 2.41 阶），听得出来。
     */
    final float growlFree;
    /**
     * 负载时壳体那一路（项目所有者 2026-10-24：负载时要有更重的机械感）：增压时出口的压力一下一下顶着壳体，齿轮受力，壳体跟着振，
     * 经发动机、支座、车身传出去 —— 偏中低频（几个壳体的共振：Hz、Q；再过 {@link #bodyTopHz} 的二阶低通，高频走不了这条路），
     * 车内听感时不过防火墙那两级低通（结构传过来的）。
     * 进去的是调过的齿轮声、出口脉动（本来就跟着增压），松开以后的啸叫不进去：松开以后只剩空气里那根干净的啸叫。电平 {@link #bodyDb}
     * （dB，相对进去的音）。
     */
    final float[] bodyHz;
    final float bodyQ;
    final float bodyTopHz;
    final float bodyDb;
    /**
     * 增压器啸叫里不变的那部分再降多少 dB：齿轮声、出口的压力脉动、松开以后的啸叫，不管走空气还是壳体。项目所有者 2026-10-24：
     * 高频那部分响度减 25 %（人耳的响度每 10 dB 翻一倍：10 × log2 0.75 ≈ −4.2 dB），机械的质感留着、啸叫少一点 ——
     * 负载时调出来的那部分（齿轮受力、转子转一圈、每次点火调出来的边带，{@link #rotorAm} 等）不降，叶片基频不降。
     * 只降不变的那部分：几次谐波之间的比例（随转速变的音色）照旧；整个一起降的话，负载时 2–5 kHz 的粗糙度少 2 dB。
     */
    final float steadyWhineDb;
    /**
     * 踩下去起压那一下（参考视频：叶片基频猛一下，比松开时高约 15 dB）：负荷跳上去、增压还没跟上（旁通阀刚关、进口突然灌进
     * 空气），叶片基频 × (1 + {@link #pressBurst} × 2 × (负荷 − 增压))，按 {@link #pressBurstSec} 落下去。一下就过去，
     * 和发动机第 12 阶来不及拍出「呜哇」。
     */
    final float pressBurst;
    final float pressBurstSec;
    /**
     * 增压时进气腔里的压力脉动（项目所有者 2026-10-22：齿轮声太单一，参考那台像好几组齿轮的和弦）：每个进气门关上那一下，
     * 那一排的进气腔（两排各一个中冷器）压力抖几下。抖的样子跟着曲轴角走（气门按曲轴角开关，转速翻倍、抖得也快一倍）：
     * 几个在固定阶次上的共振（{@link #pulsationOrders}，Q {@link #pulsationQ}），谱峰一样高；最低那个起头的幅度
     * {@link #pulsationDb}（相对排气脉冲，满增压），随转速另加 {@link #pulsationRpmDb}（按转速的对数插值，两头照最边上的）。
     * 幅度跟进气腔的绝对压力走（增压的 1.5 次方）。传到外面要过进气腔、中冷器：{@link #pulsationTopHz} 以上落下去（二阶低通）——
     * 参考那台 1300、1900 rpm 时整数阶到 25 阶为止（按曲轴角的形状），2350、2850 rpm 时到 21–22 阶、900–1000 Hz 就收了
     * （固定的频率）：转速越高，截掉的阶次越多，音色跟着变。
     * 同一排上一下留下的压力还没恢复，这一下就弱一点（1 − {@link #pulsationRecovery} × e^(−间隔 / {@link #recoveryDeg})，
     * 交叉平面每排的间隔 90/180/270°）、晚一点（缺多少晚多少，缺满了晚 {@link #pulsationLagMs} 毫秒）。两排的样子一样、
     * 正好错开一圈，合起来每圈重复一次：出来的是发动机的整数阶（半阶抵掉），十几根一起随转速走，和齿轮声叠成和弦。
     * 照 EngineV1 那台带负荷的录音（转速跟踪后量的，2026-10-22）：增压时 10–34 阶几乎全是清楚的整数阶，比底噪高 15–30 dB，
     * 最响的在 14–25 阶、和齿轮声差不多响；我们原来这一段只有沙哑的噪声。2850 rpm 以下照参考放到和齿轮声差不多响
     * （项目所有者 2026-10-22：齿轮声一根主线太过突出，应该是层次更多的一组声音）；参考只录到 2850 rpm，再往上收一点
     * （4000 rpm −2、5000 rpm 以上 −3 dB；加上 1 kHz 的低通，全油门整体响度不比上一轮大）。旁通阀开着（不增压）时还剩
     * {@link #pulsationFree}。
     */
    final float pulsationDb;
    final float pulsationTopHz;
    final float[] pulsationRpm;
    final float[] pulsationRpmDb;
    final float[] pulsationOrders;
    final float pulsationQ;
    final float pulsationRecovery;
    final float pulsationLagMs;
    final float pulsationFree;
    /**
     * 出口的压力脉动 —— 「做功、压缩」那一层（项目所有者 2026-10-23：增压器有好几个出声的部件，位置、转速各不同，应该分开、更有层次，
     * 不是单纯的风声）。每扫过一个叶片，一腔压缩过的空气推开出口，出口那边的压力一下一下地跳：叶片频率和它的谐波
     * （{@link #dischargeHarmonics} 次，各自的 dB；偶数次和同步齿轮的啮合声（2 倍）同频，不放），幅度跟增压（压比）的 1.5 次方，
     * 旁通阀开着（不增压）时没有。经两个中冷器（左、右排各一个）进进气腔：中冷器的芯子压高频（{@link #intercoolerHz}），出口到
     * 中冷器那一段风道有自己的共振（左、右各一个，Hz；dB、Q）；压缩把空气加热（满增压时温度是进口的 {@link #chargeHeat} 倍），
     * 声速跟着变快，风道的共振随增压往上走。满增压、3000 rpm 时最响那一次（第 3 次，36.09 阶）的电平 {@link #dischargeDb}（和齿轮声
     * 同一个基准）。第 1 次（12.03 阶）离发动机的 12 阶只差 0.03 阶，满增压时两个拍成一秒一两下的「呜哇」：压低 14 dB（项目所有者
     * 2026-10-24：压低一些）。
     */
    final float dischargeDb;
    final int[] dischargeHarmonics;
    final float[] dischargeHarmonicsDb;
    final float intercoolerHz;
    final float[] ductHz;
    final float ductDb;
    final float ductQ;
    final float chargeHeat;
    /**
     * 挤过出口的气流（满增压、6000 rpm，dB）：白噪声每扫过一个叶片涌一下（{@link #jetPulse}），过出口通道、进气腔、
     * 中冷器的几个共振（Hz、Q、dB），再低通；左右两路不相关（两边各一个中冷器）。2026-10-23 −12 → −17 dB：增压器的声音不是
     * 单纯的风声（项目所有者），出口那一层主要靠压力脉动（{@link #dischargeDb}）。
     */
    final float jetDb;
    final float[] jetHz;
    final float[] jetQ;
    final float[] jetGainDb;
    final float jetPulse;
    final float airLowpassHz;
    /** 进口吸气的风声（满流量时的 dB、带通 Hz、Q）。2026-10-23 去掉了随机的阵风（一闪一闪的风声），−13 → −17 dB。 */
    final float inletDb;
    final float inletHz;
    final float inletQ;
    /** 车速 200 km/h 时进气的风声（进口风声、进气呼啸）多几成（× (1 + 它)，按车速的平方长）：越开越快的感觉。 */
    final float inletRam;
    /**
     * 松开油门以后的啸叫 ——「放开」那一层（项目所有者 2026-10-23 给的参考视频，发动机舱里停着轰油门：踩下油门、发动机出力时
     * 增压器承受着压力，声音和放开油门后的声调明显不同；EngineV1 那台的素材基本是踩着油门录的，缺这一段）。节气门在增压器前面，
     * 一关上，增压器进口那边抽成很低的真空、旁通阀打开，转子照样被皮带带着高速转、在旁通回路里搬着稀薄的空气：燃烧、压缩那几层
     * 没了，剩下干净的啸叫 —— 叶片频率的 {@link #decelHarmonics} 次（各自的 dB，第 2 次最响），各自固定的相位。参考：松开那一下
     * 最响（同一转速、同一根线比踩着时高约 8 dB），随转速落下去很快变淡（4600 → 3300 rpm 约 −7 dB，3000 rpm 以下几乎听不见）；
     * 节气门开着没有。电平：{@link #decelDb}（dB，4500 rpm、节气门全关，和齿轮声同一个基准）× (转速 / 4500)^{@link #decelRpmPow}
     * × ((1 − 负荷)² + {@link #decelBurst} × 刚才的增压)：刚在增压就松开，那一下多响，节气门还没全关时就起，按
     * {@link #decelBurstSec} 落下去。
     */
    final float decelDb;
    final float decelRpmPow;
    final int[] decelHarmonics;
    final float[] decelHarmonicsDb;
    final float decelBurst;
    final float decelBurstSec;

    // ---- 声音从哪来（驾驶座看；立体声 −1 左 … +1 右）。2026-10-24 摆开一些（项目所有者：声音汇聚在一起、要立体感）：
    //      缸 0.45 → 0.6，中冷器出口的气流 0.5 → 0.7，进气口 −0.3 → −0.45
    /** 每个缸气门、燃烧的声音按排摆在左右多远；最靠后的缸离驾驶座多远（米）、缸距（米，1、2 缸在最前面）。 */
    final float cylinderPan;
    final float cylinderNearM;
    final float cylinderPitchM;
    /** 排气（尾管在车后两角）比发动机舱的声音晚到多久（毫秒）：管子里走一段、再从车后绕过来。 */
    final float exhaustLagMs;
    /** 两边中冷器出口的气流摆多开；进气口（左前）在哪。 */
    final float jetPan;
    final float inletPan;

    // ---- 换挡：传动的「咚」（地板中间，几个衰减的低频）+ 挂挡的「咔」（一小段带通噪声）
    final float[] thunkHz;
    final float[] thunkMs;
    final float[] thunkGainDb;
    /** 满负荷升挡时「咚」多响（dB，只跟总音量）；降挡七成。 */
    final float thunkDb;
    final float clickHz;
    final float clickQ;
    /** 「咔」相对「咚」（dB）。 */
    final float clickDb;
    /**
     * 点着、熄火停住那一刻发动机在支座上晃一下（项目所有者 2026-10-22：点火的体感再强些；熄火照车主偏好的那台）：
     * 一个衰减的低频（Hz、多快落下去 ms、多响 dB，只跟总音量）。
     */
    final float rockHz;
    final float rockMs;
    final float rockDb;

    // ---- 车内听感（「模拟声浪」页的「听感」选「车内」）：发动机舱的声音隔着防火墙、排气隔着车身，各过两个一阶低通；
    //      低频多一点（车身传进来的）；总的再补一点，日常驾驶和车外差不多响
    final float[] cabinFrontHz;
    final float[] cabinRearHz;
    final float cabinLowHz;
    final float cabinLowDb;
    final float cabinGainDb;


    // ---- 气门、缸体、车厢
    final float tickDb;
    final float[] tickHz;
    final float[] tickQ;
    final float knockDb;
    final float[] knockHz;
    final float[] knockQ;
    final float[] cabinHz;
    final float[] cabinQ;
    final float[] cabinGain;
    /** 高 Q 的「和鸣」：某个阶次扫过它时响一下。 */
    final float[] sympatheticHz;
    final float[] sympatheticQ;
    final float[] sympatheticGain;

    // ---- 传动
    /** 变速箱齿轮啸叫的阶次（每个挡一个，避开发动机的整数、半阶次；连续走法没有挡位用 {@link #cvtOrder}）。 */
    final float[] gearOrders;
    final float cvtOrder;
    final float gearDb;
    final float diffHzPerKmh;
    final float diffDb;
    final float shaftHzPerKmh;
    final float shaftDb;

    // ---- 收油
    /** 断油时每一下只剩泵气，是燃烧时的几成。 */
    final float pump;
    final float popMax;
    final float popGain;
    final float popRiseMs;
    final float popDecayMs;
    /**
     * 收油时电平少掉多少 dB（乘 {@link #levelPow} 次方之前）。项目所有者 2026-10-23：松开油门后引擎声音太小，踩和不踩的落差太大 ——
     * 不踩油门时发动机仍被车拖着、和变速箱、车连成一体在转，每个缸照样吸气、压缩、排气。虚拟发动机的电平按负荷算，断油时一下掉
     * 19 dB（车内听感的次方 0.42 → −8 dB），合成器里每一下脉冲又只剩泵气（{@link #pump}）—— 「没出力」算了两遍。
     */
    final float overrunLevelDb;
    /**
     * 松油门时燃烧不是一下断掉：电控先推迟点火、把扭矩慢慢收掉（免得传动「咣」一下），之后才断油（减速断油）—— 燃烧按这个时间
     * （秒）收到泵气；踩下去马上回来。
     */
    final float fuelOutSec;
    /**
     * 排气管里的气体温度（K：冷、热；下面排气管、共振峰、亥姆霍兹的 Hz 是 {@link #gasRefK} 时的，约等于巡航时）：声速跟着温度的
     * 平方根走，管子、消声器的共振一起往上移。温度跟着发动机的输出功率（负荷 × 转速 / {@link #heatFullRpm}），管壁有热惯性：
     * 升温、降温都要几秒。项目所有者 2026-10-23：低转速下引擎的轰鸣不错，转速上去之后厚度、力量感不足 —— 共振峰固定不动时，
     * 低转时它们正好托住 2.5–3.5 阶，高转时只托得住 1.5–2 阶（参考车车内高转是 1–4.5 阶一整排）。按功率、带惯性：巡航、低转加速
     * 基本不动，持续高转全油门时才一起往上移。
     */
    final float gasColdK;
    final float gasHotK;
    final float gasRefK;
    final float heatFullRpm;
    final float heatUpSec;
    final float heatDownSec;
    /**
     * 车内听感时排气的气流噪声（沙哑）随转速少多少 dB（按转速的对数插值，两头照最边上的）：参考车车内高转全油门时线和线之间的底子
     * 比 4 阶低 26–50 dB，我们原来只低 11–26 dB；低转全油门的轰鸣里有一大块是这层随点火起伏的沙哑（项目所有者喜欢的低转），不动。
     * 沙哑原来照车外的录音（EngineV1 那台，只录到 2850 rpm）定的，再往上是外推的。
     */
    final float[] cabinRaspRpm;
    final float[] cabinRaspRpmDb;
    /**
     * 车内听感时进气腔的和弦（{@link #pulsationDb}）随转速另加的 dB（按转速的对数插值，两头照最边上的）：参考车车内高转全油门时
     * 10–13 阶比 4 阶低 31–37 dB，我们原来只低 13–14 dB —— 高转时上面一层细的整数阶盖过了下面的厚度。
     */
    final float[] cabinChordRpm;
    final float[] cabinChordRpmDb;

    private PulseProfile(Builder b) {
        firing = b.firing.clone();
        trim = b.trim.clone();
        headerMs = b.headerMs.clone();
        bankMs = b.bankMs.clone();
        exhaustOpenDeg = b.exhaustOpenDeg;
        exhaustCloseDeg = b.exhaustCloseDeg;
        intakeCloseDeg = b.intakeCloseDeg;
        burnDeg = b.burnDeg;
        riseIdleMs = b.riseIdleMs;
        riseLoadMs = b.riseLoadMs;
        decayDeg = b.decayDeg;
        recoveryDeg = b.recoveryDeg;
        recoveryIdle = b.recoveryIdle;
        recoveryLoad = b.recoveryLoad;
        sigmaIdle = b.sigmaIdle;
        sigmaLoad = b.sigmaLoad;
        sigmaOverrun = b.sigmaOverrun;
        jitterIdleDeg = b.jitterIdleDeg;
        jitterLoadDeg = b.jitterLoadDeg;
        raspIdleDb = b.raspIdleDb;
        raspLoadDb = b.raspLoadDb;
        raspOverrunDb = b.raspOverrunDb;
        raspFloor = b.raspFloor;
        raspDecayDeg = b.raspDecayDeg;
        raspHighpassHz = b.raspHighpassHz;
        raspTiltHz = b.raspTiltHz;
        raspTiltRpmPow = b.raspTiltRpmPow;
        raspAir = b.raspAir;
        raspRpmDb = b.raspRpmDb.clone();
        raspRefRpm = b.raspRefRpm;
        raspRpmMaxDb = b.raspRpmMaxDb;
        raspPeakHz = b.raspPeakHz;
        raspPeakDb = b.raspPeakDb;
        raspPeakQ = b.raspPeakQ;
        pipeHz = b.pipeHz.clone();
        pipeFeedback = b.pipeFeedback;
        pipeLossHz = b.pipeLossHz;
        formantHz = b.formantHz.clone();
        formantDb = b.formantDb.clone();
        formantQ = b.formantQ.clone();
        helmholtzHz = b.helmholtzHz.clone();
        helmholtzDb = b.helmholtzDb;
        helmholtzQ = b.helmholtzQ;
        mufflerHz = b.mufflerHz;
        mufflerPow = b.mufflerPow;
        mufflerTopHz = b.mufflerTopHz;
        mufflerLoad = b.mufflerLoad;
        radiationHz = b.radiationHz;
        driveIdle = b.driveIdle;
        driveLoad = b.driveLoad;
        cross = b.cross;
        meshSpread = b.meshSpread;
        plenumPan = b.plenumPan;
        widthDb = b.widthDb;
        widthHz = b.widthHz;
        bassMonoHz = b.bassMonoHz;
        rpmLoudDb = b.rpmLoudDb;
        levelPow = b.levelPow;
        cabinLevelPow = b.cabinLevelPow;
        cutDipDb = b.cutDipDb;
        norm = b.norm;
        intakeIdleDb = b.intakeIdleDb;
        intakeLoadDb = b.intakeLoadDb;
        plenumHz = b.plenumHz;
        plenumQ = b.plenumQ;
        runnerHz = b.runnerHz;
        runnerQ = b.runnerQ;
        roarIdleDb = b.roarIdleDb;
        roarLoadDb = b.roarLoadDb;
        roarHz = b.roarHz;
        roarQ = b.roarQ;
        hissDb = b.hissDb;
        hissHz = b.hissHz;
        hissQ = b.hissQ;
        hissDecayMs = b.hissDecayMs;
        hissFloor = b.hissFloor;
        superchargerOrder = b.superchargerOrder;
        lobeDb = b.lobeDb;
        meshMultiple = b.meshMultiple;
        meshHarmonicsDb = new float[b.meshHarmonicsDb.length][];
        for (int i = 0; i < meshHarmonicsDb.length; i++) {
            meshHarmonicsDb[i] = b.meshHarmonicsDb[i].clone();
        }
        rotorLobes = b.rotorLobes.clone();
        rotorAm = b.rotorAm.clone();
        growlFree = b.growlFree;
        bodyHz = b.bodyHz.clone();
        bodyQ = b.bodyQ;
        bodyTopHz = b.bodyTopHz;
        bodyDb = b.bodyDb;
        steadyWhineDb = b.steadyWhineDb;
        pressBurst = b.pressBurst;
        pressBurstSec = b.pressBurstSec;
        superchargerDb = b.superchargerDb;
        toneRpmPow = b.toneRpmPow;
        superchargerRpmPow = b.superchargerRpmPow;
        meshRpm = b.meshRpm.clone();
        meshRpmDb = b.meshRpmDb.clone();
        torsion = b.torsion;
        meshVoiceDetune = b.meshVoiceDetune.clone();
        meshVoiceWander = b.meshVoiceWander;
        meshVoiceWanderHz = b.meshVoiceWanderHz;
        superchargerFree = b.superchargerFree;
        superchargerAm = b.superchargerAm;
        superchargerSec = b.superchargerSec;
        pulsationDb = b.pulsationDb;
        pulsationTopHz = b.pulsationTopHz;
        pulsationRpm = b.pulsationRpm.clone();
        pulsationRpmDb = b.pulsationRpmDb.clone();
        pulsationOrders = b.pulsationOrders.clone();
        pulsationQ = b.pulsationQ;
        pulsationRecovery = b.pulsationRecovery;
        pulsationLagMs = b.pulsationLagMs;
        pulsationFree = b.pulsationFree;
        dischargeDb = b.dischargeDb;
        dischargeHarmonics = b.dischargeHarmonics.clone();
        dischargeHarmonicsDb = b.dischargeHarmonicsDb.clone();
        intercoolerHz = b.intercoolerHz;
        ductHz = b.ductHz.clone();
        ductDb = b.ductDb;
        ductQ = b.ductQ;
        chargeHeat = b.chargeHeat;
        jetDb = b.jetDb;
        jetHz = b.jetHz.clone();
        jetQ = b.jetQ.clone();
        jetGainDb = b.jetGainDb.clone();
        jetPulse = b.jetPulse;
        airLowpassHz = b.airLowpassHz;
        inletDb = b.inletDb;
        inletHz = b.inletHz;
        inletQ = b.inletQ;
        inletRam = b.inletRam;
        decelDb = b.decelDb;
        decelRpmPow = b.decelRpmPow;
        decelHarmonics = b.decelHarmonics.clone();
        decelHarmonicsDb = b.decelHarmonicsDb.clone();
        decelBurst = b.decelBurst;
        decelBurstSec = b.decelBurstSec;
        cylinderPan = b.cylinderPan;
        cylinderNearM = b.cylinderNearM;
        cylinderPitchM = b.cylinderPitchM;
        exhaustLagMs = b.exhaustLagMs;
        jetPan = b.jetPan;
        inletPan = b.inletPan;
        thunkHz = b.thunkHz.clone();
        thunkMs = b.thunkMs.clone();
        thunkGainDb = b.thunkGainDb.clone();
        thunkDb = b.thunkDb;
        clickHz = b.clickHz;
        clickQ = b.clickQ;
        clickDb = b.clickDb;
        rockHz = b.rockHz;
        rockMs = b.rockMs;
        rockDb = b.rockDb;
        cabinFrontHz = b.cabinFrontHz.clone();
        cabinRearHz = b.cabinRearHz.clone();
        cabinLowHz = b.cabinLowHz;
        cabinLowDb = b.cabinLowDb;
        cabinGainDb = b.cabinGainDb;
        tickDb = b.tickDb;
        tickHz = b.tickHz.clone();
        tickQ = b.tickQ.clone();
        knockDb = b.knockDb;
        knockHz = b.knockHz.clone();
        knockQ = b.knockQ.clone();
        cabinHz = b.cabinHz.clone();
        cabinQ = b.cabinQ.clone();
        cabinGain = b.cabinGain.clone();
        sympatheticHz = b.sympatheticHz.clone();
        sympatheticQ = b.sympatheticQ.clone();
        sympatheticGain = b.sympatheticGain.clone();
        gearOrders = b.gearOrders.clone();
        cvtOrder = b.cvtOrder;
        gearDb = b.gearDb;
        diffHzPerKmh = b.diffHzPerKmh;
        diffDb = b.diffDb;
        shaftHzPerKmh = b.shaftHzPerKmh;
        shaftDb = b.shaftDb;
        pump = b.pump;
        popMax = b.popMax;
        popGain = b.popGain;
        popRiseMs = b.popRiseMs;
        popDecayMs = b.popDecayMs;
        overrunLevelDb = b.overrunLevelDb;
        fuelOutSec = b.fuelOutSec;
        gasColdK = b.gasColdK;
        gasHotK = b.gasHotK;
        gasRefK = b.gasRefK;
        heatFullRpm = b.heatFullRpm;
        heatUpSec = b.heatUpSec;
        heatDownSec = b.heatDownSec;
        cabinRaspRpm = b.cabinRaspRpm.clone();
        cabinRaspRpmDb = b.cabinRaspRpmDb.clone();
        cabinChordRpm = b.cabinChordRpm.clone();
        cabinChordRpmDb = b.cabinChordRpmDb.clone();
    }

    /**
     * 机械增压交叉平面 V8（「美式 V8」，{@link SoundStyle#AMERICAN}）：排气脉冲尖、沙哑足；双螺杆增压器的啸叫（齿轮声）和气流。
     * 增压器的样子照车主偏好的那台的录音量（项目所有者 2026-10-21：增压声要和它比），音高照参考视频那台（叶片 12.03 阶、齿轮 24.06 阶；
     * 项目所有者 2026-10-24 听了试听定的，原来是 10.24、30.72）。
     * 齿轮声 −20 → −20.7 dB（项目所有者 2026-10-22：响度调低 5 %，人耳的响度每 10 dB 翻一倍）。
     */
    static final PulseProfile SUPERCHARGED_V8 = new Builder()
            .supercharger(12.03f, -20.7f)
            .loudness(0.15f, 0.42f, 0.5f, 0.56f)
            .build();

    /** 写风格用：默认值是机械增压那一份的发动机、排气和增压器（增压器要 {@link #supercharger} 才装上），一项一项改，最后 {@link #build}。 */
    static final class Builder {
        private int[] firing = {1, 8, 4, 3, 6, 5, 7, 2};
        private float[] trim = {1.03f, 0.97f, 1.00f, 0.98f, 1.02f, 0.96f, 1.04f, 0.99f};
        private float[] headerMs = {0.10f, 0.29f, 0.24f, 0.14f, 0.17f, 0.26f, 0.31f, 0.11f};
        private float[] bankMs = {0f, 0.35f};
        private float exhaustOpenDeg = 130f;
        private float exhaustCloseDeg = 370f;
        private float intakeCloseDeg = 590f;
        private float burnDeg = 8f;
        private float riseIdleMs = 0.40f;
        private float riseLoadMs = 0.15f;
        private float decayDeg = 18f;
        private float recoveryDeg = 107f;
        private float recoveryIdle = 1.37f;
        private float recoveryLoad = 1.37f;
        private float sigmaIdle = 0.08f;
        private float sigmaLoad = 0.05f;
        private float sigmaOverrun = 0.30f;
        private float jitterIdleDeg = 1f;
        private float jitterLoadDeg = 0.5f;
        private float raspIdleDb = -16f;
        private float raspLoadDb = -1.5f;
        private float raspOverrunDb = -21f;
        private float raspFloor = 0.15f;
        private float raspDecayDeg = 45f;
        private float raspHighpassHz = 90f;
        private float raspTiltHz = 600f;
        private float raspTiltRpmPow = 0.5f;
        private float raspAir = 0.12f;
        private float[] raspRpmDb = {0f, 12f};
        private float raspRefRpm = 2850f;
        private float raspRpmMaxDb = 2f;
        private float raspPeakHz = 1200f;
        private float raspPeakDb = 3f;
        private float raspPeakQ = 0.7f;
        private float[] pipeHz = {172f, 164f};
        private float pipeFeedback = 0.5f;
        private float pipeLossHz = 1500f;
        private float[] formantHz = {150f, 330f, 680f};
        private float[] formantDb = {8f, 2f, 2f};
        private float[] formantQ = {1.4f, 2f, 2.5f};
        private float[] helmholtzHz = {56f, 61f};
        private float helmholtzDb = 10f;
        private float helmholtzQ = 3.5f;
        private float mufflerHz = 2100f;
        private float mufflerPow = 0.9f;
        private float mufflerTopHz = 12000f;
        private float mufflerLoad = 0.35f;
        private float radiationHz = 45f;
        private float driveIdle = 0.3f;
        private float driveLoad = 1.5f;
        private float cross = 0.25f;
        private float meshSpread = 0.6f;
        private float plenumPan = 0.35f;
        private float widthDb = 3f;
        private float widthHz = 700f;
        private float bassMonoHz = 120f;
        private float rpmLoudDb = 5f;
        private float levelPow = 0.5f;
        private float cabinLevelPow = 0.5f;
        private float cutDipDb = -4.5f;
        private float norm = 0.79f;
        private float intakeIdleDb = -30f;
        private float intakeLoadDb = -12f;
        private float plenumHz = 95f;
        private float plenumQ = 2f;
        private float runnerHz = 420f;
        private float runnerQ = 4f;
        private float roarIdleDb = -40f;
        private float roarLoadDb = -14f;
        private float roarHz = 1800f;
        private float roarQ = 0.7f;
        private float hissDb = -22f;
        private float hissHz = 2000f;
        private float hissQ = 1f;
        private float hissDecayMs = 4f;
        private float hissFloor = 0f;
        private float superchargerOrder;
        private float lobeDb = -35f;
        private float meshMultiple = 2f;
        private float[][] meshHarmonicsDb = {
                {1.5f, 0f, -9.5f, -9.5f, -12f},
                {-0.5f, 0f, -6f, -9f, -12f},
                {-2.5f, 0f, -1.5f, -9.5f, -10f},
                {-3.5f, 0f, -3f, -14f, -19f},
                {-3.5f, 0f, -3f, -14f, -19f}};
        private float toneRpmPow = 0.8f;
        private float[] rotorLobes = {5f, 3f};
        private float[] rotorAm = {0.7f, 0.45f};
        private float growlFree = 0.15f;
        private float[] bodyHz = {550f, 950f};
        private float bodyQ = 2.5f;
        private float bodyTopHz = 1400f;
        private float bodyDb = 0f;
        private float steadyWhineDb = -4.2f;
        private float pressBurst = 4f;
        private float pressBurstSec = 0.25f;
        private float superchargerDb = -100f;
        private float superchargerRpmPow = 1.7f;
        private float[] meshRpm = {1300f, 1900f, 2350f, 2850f, 4000f};
        private float[] meshRpmDb = {9f, 14f, 8f, 0f, -4f};
        private float torsion = 0.003f;
        private float[] meshVoiceDetune = {0f, -0.0018f, 0.0022f};
        private float meshVoiceWander = 0.0045f;
        private float meshVoiceWanderHz = 2.5f;
        private float superchargerFree = 0.05f;
        private float superchargerAm = 0.6f;
        private float superchargerSec = 0.12f;
        private float pulsationDb = 1f;
        private float pulsationTopHz = 1000f;
        private float[] pulsationRpm = {2850f, 4000f, 5000f};
        private float[] pulsationRpmDb = {0f, -2f, -3f};
        private float[] pulsationOrders = {12f, 15.5f, 19.5f, 24.5f};
        private float pulsationQ = 4f;
        private float pulsationRecovery = 1.37f;
        private float pulsationLagMs = 2f;
        private float pulsationFree = 0.15f;
        private float dischargeDb = -23f;
        private int[] dischargeHarmonics = {1, 3, 5};
        private float[] dischargeHarmonicsDb = {-14f, 0f, -9f};
        private float intercoolerHz = 2600f;
        private float[] ductHz = {820f, 960f};
        private float ductDb = 6f;
        private float ductQ = 2f;
        private float chargeHeat = 1.27f;
        private float jetDb = -17f;
        private float[] jetHz = {1800f, 3200f, 5500f};
        private float[] jetQ = {1.2f, 1.4f, 1.6f};
        private float[] jetGainDb = {0f, -1f, -6f};
        private float jetPulse = 0.8f;
        private float airLowpassHz = 7000f;
        private float inletDb = -17f;
        private float inletHz = 1600f;
        private float inletQ = 0.7f;
        private float inletRam = 3f;
        private float decelDb = -21f;
        private float decelRpmPow = 2.4f;
        private int[] decelHarmonics = {2, 3, 4};
        private float[] decelHarmonicsDb = {0f, -7f, -9f};
        private float decelBurst = 1.5f;
        private float decelBurstSec = 0.15f;
        private float cylinderPan = 0.6f;
        private float cylinderNearM = 1.7f;
        private float cylinderPitchM = 0.12f;
        private float exhaustLagMs = 10f;
        private float jetPan = 0.7f;
        private float inletPan = -0.45f;
        private float[] thunkHz = {55f, 140f};
        private float[] thunkMs = {35f, 12f};
        private float[] thunkGainDb = {0f, -8f};
        private float thunkDb = -11f;
        private float rockHz = 40f;
        private float rockMs = 90f;
        private float rockDb = -13f;
        private float clickHz = 2200f;
        private float clickQ = 1.5f;
        private float clickDb = -14f;
        private float[] cabinFrontHz = {600f, 3500f};
        private float[] cabinRearHz = {350f, 2000f};
        private float cabinLowHz = 100f;
        private float cabinLowDb = 4f;
        private float cabinGainDb = 3.5f;
        private float tickDb = -34f;
        private float[] tickHz = {3100f, 4800f};
        private float[] tickQ = {14f, 10f};
        private float knockDb = -26f;
        private float[] knockHz = {820f, 1450f, 2300f};
        private float[] knockQ = {6f, 7f, 8f};
        private float[] cabinHz = {42f, 63f, 96f, 135f};
        private float[] cabinQ = {5f, 6f, 5f, 4f};
        private float[] cabinGain = {0.8f, 0.7f, 0.5f, 0.4f};
        private float[] sympatheticHz = {333f};
        private float[] sympatheticQ = {30f};
        private float[] sympatheticGain = {0.15f};
        private float[] gearOrders = {35.3f, 30.2f, 27.4f, 25.1f, 23.3f, 21.8f, 20.6f, 19.7f};
        private float cvtOrder = 26.3f;
        private float gearDb = -36f;
        private float diffHzPerKmh = 4.16f;
        private float diffDb = -40f;
        private float shaftHzPerKmh = 0.69f;
        private float shaftDb = -36f;
        private float pump = 0.45f;
        private float popMax = 0.05f;
        private float popGain = 1.2f;
        private float popRiseMs = 0.3f;
        private float popDecayMs = 5f;
        private float overrunLevelDb = 14f;
        private float fuelOutSec = 0.35f;
        private float gasColdK = 450f;
        private float gasHotK = 1000f;
        private float gasRefK = 495f;
        private float heatFullRpm = 6500f;
        private float heatUpSec = 3f;
        private float heatDownSec = 5f;
        private float[] cabinRaspRpm = {3000f, 5500f};
        private float[] cabinRaspRpmDb = {0f, -8f};
        private float[] cabinChordRpm = {2500f, 3500f, 6000f};
        private float[] cabinChordRpmDb = {3f, 0f, -8f};

        /** 每一下的随机起伏（标准差：怠速、满负荷、收油时另加）和时机的随机偏移（曲轴角：怠速、满负荷）。 */
        Builder variation(float sigmaIdle, float sigmaLoad, float sigmaOverrun, float jitterIdleDeg, float jitterLoadDeg) {
            this.sigmaIdle = sigmaIdle;
            this.sigmaLoad = sigmaLoad;
            this.sigmaOverrun = sigmaOverrun;
            this.jitterIdleDeg = jitterIdleDeg;
            this.jitterLoadDeg = jitterLoadDeg;
            return this;
        }

        /** 同一排上一个脉冲压低下一个：1 − m × e^(−间隔 / {@code deg})，m 怠速、满负荷各一个（0 = 不压）。 */
        Builder recovery(float deg, float idle, float load) {
            recoveryDeg = deg;
            recoveryIdle = idle;
            recoveryLoad = load;
            return this;
        }

        /** 装一台机械增压：叶片扫过出口的阶次、满增压 3000 rpm 时齿轮声第 2 次的电平（dB）；基频、齿轮、转子、气流、进口用默认的那一份。 */
        /** 电平取的次方（车外、车内听感）、怠速到 6000 rpm 多响多少（dB）、总的倍数。 */
        Builder loudness(float levelPow, float cabinLevelPow, float rpmLoudDb, float norm) {
            this.levelPow = levelPow;
            this.cabinLevelPow = cabinLevelPow;
            this.rpmLoudDb = rpmLoudDb;
            this.norm = norm;
            return this;
        }

        Builder supercharger(float order, float db) {
            superchargerOrder = order;
            superchargerDb = db;
            return this;
        }

        /** 出口压力脉动那一层最响那一次的电平（dB，{@link PulseProfile#dischargeDb}）；很低（−100）就是没有这一层。 */
        Builder discharge(float db) {
            dischargeDb = db;
            return this;
        }

        /** 松开油门以后的啸叫那一层的电平（dB，{@link PulseProfile#decelDb}）；很低（−100）就是没有这一层。 */
        Builder decel(float db) {
            decelDb = db;
            return this;
        }

        /** 节气门气流声的电平（dB，{@link PulseProfile#hissDb}）；很低（−100）就是没有这一层。 */
        Builder hiss(float db) {
            hissDb = db;
            return this;
        }

        /** 负载时壳体那一路的电平（dB，{@link PulseProfile#bodyDb}）；很低（−100）就是没有这一路。 */
        Builder body(float db) {
            bodyDb = db;
            return this;
        }

        /** 踩下去起压那一下叶片基频多冲几倍（{@link PulseProfile#pressBurst}）；0 就是不冲。 */
        Builder press(float burst) {
            pressBurst = burst;
            return this;
        }

        /** 增压器啸叫里不变的那部分降多少 dB（{@link PulseProfile#steadyWhineDb}）；0 就是不降。 */
        Builder steadyWhine(float db) {
            steadyWhineDb = db;
            return this;
        }

        /** 中高频左右的差再放大多少 dB（{@link PulseProfile#widthDb}）；0 就是不放大。 */
        Builder width(float db) {
            widthDb = db;
            return this;
        }

        PulseProfile build() {
            if (firing.length == 0 || trim.length < firing.length || headerMs.length < firing.length
                    || pipeHz.length != 2 || helmholtzHz.length != 2 || bankMs.length != 2
                    || rotorLobes.length != 2 || rotorAm.length != 2
                    || jetQ.length != jetHz.length || jetGainDb.length != jetHz.length || raspRpmDb.length != 2
                    || thunkMs.length != thunkHz.length || thunkGainDb.length != thunkHz.length
                    || cabinFrontHz.length != 2 || cabinRearHz.length != 2
                    || meshHarmonicsDb.length != meshRpm.length || meshRpmDb.length != meshRpm.length || meshVoiceDetune.length == 0
                    || pulsationRpmDb.length != pulsationRpm.length || cabinChordRpmDb.length != cabinChordRpm.length
                    || cabinRaspRpmDb.length != cabinRaspRpm.length
                    || dischargeHarmonicsDb.length != dischargeHarmonics.length || ductHz.length != 2
                    || decelHarmonicsDb.length != decelHarmonics.length) {
                throw new IllegalStateException("pulse profile incomplete");
            }
            for (int c : firing) {
                if (c < 1 || c > trim.length) {
                    throw new IllegalStateException("cylinder " + c);
                }
            }
            return new PulseProfile(this);
        }
    }
}
