package com.kooo.evcam.telemetry;

/**
 * 车此刻的状态：一份不可变的快照。
 *
 * <h3>为什么每一项都可以是「不知道」</h3>
 *
 * <p>信号从哪来、来不来，由各个来源决定（{@link Telemetry}）：这台车机的容器里车辆属性
 * 多半读不到（平台笔记 §「能拿到什么信号」），定位要看有没有给权限。画信息条的人
 * 只看这份快照，不知道就画成「没数据」—— 而不是画成「关」。「没数据」和「关」
 * 在画面上必须分得开，否则转向灯亮着而我们读不到，画出来就是「没打灯」。</p>
 *
 * <p>所以每个字段都是可空的包装类型：{@code null} = 没数据。座位例外：五个座位各自有一个状态，
 * 「没数据」是其中之一（{@link #SEAT_UNKNOWN}，{@link #seat}）。</p>
 *
 * <p>纯数据，不碰 Android，{@code VehicleStateTest} 里测。</p>
 */
public final class VehicleState {

    public static final int TURN_NONE = 0;
    public static final int TURN_LEFT = 1;
    public static final int TURN_RIGHT = 2;

    /** 车门 / 座位的位置位：位 0 左前、位 1 右前、位 2 左后、位 3 右后、位 4 后排中间（只有座位有）。 */
    public static final int FRONT_LEFT = 1;
    public static final int FRONT_RIGHT = 1 << 1;
    public static final int REAR_LEFT = 1 << 2;
    public static final int REAR_RIGHT = 1 << 3;
    public static final int REAR_CENTER = 1 << 4;

    /**
     * 一个座位的状态（{@link #seat}）：没数据、没人、系着、有人没系。
     * 怎么从座椅和安全带的读数算出来，看 {@link VehicleStateMapper#seatState}。
     */
    public static final int SEAT_UNKNOWN = 0;
    public static final int SEAT_EMPTY = 1;
    public static final int SEAT_BELTED = 2;
    public static final int SEAT_UNBELTED = 3;

    /** 每发布一份就 +1，画的人据此知道要不要重画。 */
    public final long version;

    /** {@link #TURN_NONE} / {@link #TURN_LEFT} / {@link #TURN_RIGHT}。 */
    public final Integer turnSignal;
    public final Boolean hazard;
    /** 方向盘转角（度），顺时针为正。 */
    public final Float steeringDegrees;
    /** 档位字母：P R N D S 等。 */
    public final String gear;
    /** 驾驶模式的号（{@link Signal#DRIVE_MODE_COMFORT} …，没对上的照原样）：信息条上是挡位框的颜色。 */
    public final Integer driveMode;
    /** 油门 / 刹车开度 0..1。 */
    public final Float throttle;
    public final Float brake;
    public final Float speedKmh;
    public final Boolean autoHold;
    /** 原厂 360 画面此刻显示着（倒车时车机自己的环视；它开着时占着相机）。 */
    public final Boolean stockSurroundShown;
    /** 六项安全辅助的开关：自动紧急制动、前碰预警、车道偏离预警、车道保持、盲区辅助、后碰预警。 */
    public final Boolean aeb;
    public final Boolean forwardCollisionWarning;
    public final Boolean laneDepartureWarning;
    public final Boolean laneKeepingAid;
    public final Boolean blindSpotAssist;
    public final Boolean rearCollisionWarning;
    /** 开着的门（位掩码）。 */
    public final Integer doorsOpen;
    /**
     * 五个座位各自的状态（{@link #SEAT_UNKNOWN} … {@link #SEAT_UNBELTED}），打包在一个 int 里：每个座位 2 位，
     * 按位置位的序号排（左前 0、右前 1、左后 2、右后 3、后排中间 4，见 {@link #seatShift}）；全 0 = 都没数据。
     * 不用可空的包装类型：每个座位自己就有「没数据」这个状态。读的时候用 {@link #seat}。
     */
    private final int seats;
    /** 前灯带亮着（日行灯，或者开着灯时以位置灯身份亮着）—— 和车外看到的一样。 */
    public final Boolean daytimeRunningLights;
    public final Boolean lowBeam;
    public final Boolean highBeam;
    /** 后雾灯（前雾灯这台车多半没装，不画）。 */
    public final Boolean fogLights;
    /** 后位置灯、刹车灯、倒车灯：都按车外看到的亮灭（刹车灯也会被自动驻车、动能回收点亮）。 */
    public final Boolean rearPositionLamps;
    public final Boolean stopLamps;
    public final Boolean reverseLamps;
    /** 正在按喇叭（Lab 还没找到读数，现在总是 null）。 */
    public final Boolean horn;
    /** 哨兵模式：0 关、1 开、2 布防（锁车后）。熄屏后环视录到什么时候（车机要不要睡），看的就是它（规格 2.4）。 */
    public final Integer sentry;
    public final Float odometerKm;
    public final Double latitude;
    public final Double longitude;
    /** 这份快照用的读数（非开发者已滤掉没验证的）：信息条的文字格从这里取值。 */
    public final Readings readings;

    private VehicleState(Builder b) {
        this.version = b.version;
        this.turnSignal = b.turnSignal;
        this.hazard = b.hazard;
        this.steeringDegrees = b.steeringDegrees;
        this.gear = b.gear;
        this.driveMode = b.driveMode;
        this.throttle = b.throttle;
        this.brake = b.brake;
        this.speedKmh = b.speedKmh;
        this.autoHold = b.autoHold;
        this.stockSurroundShown = b.stockSurroundShown;
        this.aeb = b.aeb;
        this.forwardCollisionWarning = b.forwardCollisionWarning;
        this.laneDepartureWarning = b.laneDepartureWarning;
        this.laneKeepingAid = b.laneKeepingAid;
        this.blindSpotAssist = b.blindSpotAssist;
        this.rearCollisionWarning = b.rearCollisionWarning;
        this.doorsOpen = b.doorsOpen;
        this.seats = b.seats;
        this.daytimeRunningLights = b.daytimeRunningLights;
        this.lowBeam = b.lowBeam;
        this.highBeam = b.highBeam;
        this.fogLights = b.fogLights;
        this.rearPositionLamps = b.rearPositionLamps;
        this.stopLamps = b.stopLamps;
        this.reverseLamps = b.reverseLamps;
        this.horn = b.horn;
        this.sentry = b.sentry;
        this.odometerKm = b.odometerKm;
        this.latitude = b.latitude;
        this.longitude = b.longitude;
        this.readings = b.readings;
    }

    /** 什么都不知道。 */
    public static VehicleState empty() {
        return new Builder().build();
    }

    /** 在这份之上改几项，得到新的一份（版本号 +1）。 */
    public Builder edit() {
        return new Builder(this);
    }

    /**
     * 这个座位（{@link #FRONT_LEFT} / {@link #FRONT_RIGHT} / {@link #REAR_LEFT} / {@link #REAR_CENTER} /
     * {@link #REAR_RIGHT}，一次一个）的状态；没数据 = {@link #SEAT_UNKNOWN}。
     */
    public int seat(int bit) {
        return (seats >>> seatShift(bit)) & 3;
    }

    /** 座位在 {@link #seats} 里的位移；只收一个位置位。 */
    static int seatShift(int bit) {
        if (bit <= 0 || bit > REAR_CENTER || Integer.bitCount(bit) != 1) {
            throw new IllegalArgumentException("seat bit " + bit);
        }
        return 2 * Integer.numberOfTrailingZeros(bit);
    }

    public static final class Builder {
        private long version;
        private Integer turnSignal;
        private Boolean hazard;
        private Float steeringDegrees;
        private String gear;
        private Integer driveMode;
        private Float throttle;
        private Float brake;
        private Float speedKmh;
        private Boolean autoHold;
        private Boolean stockSurroundShown;
        private Boolean aeb;
        private Boolean forwardCollisionWarning;
        private Boolean laneDepartureWarning;
        private Boolean laneKeepingAid;
        private Boolean blindSpotAssist;
        private Boolean rearCollisionWarning;
        private Integer doorsOpen;
        private int seats;
        private Boolean daytimeRunningLights;
        private Boolean lowBeam;
        private Boolean highBeam;
        private Boolean fogLights;
        private Boolean rearPositionLamps;
        private Boolean stopLamps;
        private Boolean reverseLamps;
        private Boolean horn;
        private Integer sentry;
        private Float odometerKm;
        private Double latitude;
        private Double longitude;
        private Readings readings;

        public Builder() {
        }

        private Builder(VehicleState s) {
            version = s.version + 1;
            turnSignal = s.turnSignal;
            hazard = s.hazard;
            steeringDegrees = s.steeringDegrees;
            gear = s.gear;
            driveMode = s.driveMode;
            throttle = s.throttle;
            brake = s.brake;
            speedKmh = s.speedKmh;
            autoHold = s.autoHold;
            stockSurroundShown = s.stockSurroundShown;
            aeb = s.aeb;
            forwardCollisionWarning = s.forwardCollisionWarning;
            laneDepartureWarning = s.laneDepartureWarning;
            laneKeepingAid = s.laneKeepingAid;
            blindSpotAssist = s.blindSpotAssist;
            rearCollisionWarning = s.rearCollisionWarning;
            doorsOpen = s.doorsOpen;
            seats = s.seats;
            daytimeRunningLights = s.daytimeRunningLights;
            lowBeam = s.lowBeam;
            highBeam = s.highBeam;
            fogLights = s.fogLights;
            rearPositionLamps = s.rearPositionLamps;
            stopLamps = s.stopLamps;
            reverseLamps = s.reverseLamps;
            horn = s.horn;
            sentry = s.sentry;
            odometerKm = s.odometerKm;
            latitude = s.latitude;
            longitude = s.longitude;
            readings = s.readings;
        }

        public Builder turnSignal(Integer v) { turnSignal = v; return this; }
        public Builder hazard(Boolean v) { hazard = v; return this; }
        public Builder steeringDegrees(Float v) { steeringDegrees = v; return this; }
        public Builder gear(String v) { gear = v; return this; }
        public Builder driveMode(Integer v) { driveMode = v; return this; }
        public Builder throttle(Float v) { throttle = clamp01(v); return this; }
        public Builder brake(Float v) { brake = clamp01(v); return this; }
        public Builder speedKmh(Float v) { speedKmh = v; return this; }
        public Builder autoHold(Boolean v) { autoHold = v; return this; }
        public Builder stockSurroundShown(Boolean v) { stockSurroundShown = v; return this; }
        public Builder aeb(Boolean v) { aeb = v; return this; }
        public Builder forwardCollisionWarning(Boolean v) { forwardCollisionWarning = v; return this; }
        public Builder laneDepartureWarning(Boolean v) { laneDepartureWarning = v; return this; }
        public Builder laneKeepingAid(Boolean v) { laneKeepingAid = v; return this; }
        public Builder blindSpotAssist(Boolean v) { blindSpotAssist = v; return this; }
        public Builder rearCollisionWarning(Boolean v) { rearCollisionWarning = v; return this; }
        public Builder doorsOpen(Integer v) { doorsOpen = v; return this; }
        public Builder daytimeRunningLights(Boolean v) { daytimeRunningLights = v; return this; }
        public Builder lowBeam(Boolean v) { lowBeam = v; return this; }
        public Builder highBeam(Boolean v) { highBeam = v; return this; }
        public Builder fogLights(Boolean v) { fogLights = v; return this; }
        public Builder rearPositionLamps(Boolean v) { rearPositionLamps = v; return this; }
        public Builder stopLamps(Boolean v) { stopLamps = v; return this; }
        public Builder reverseLamps(Boolean v) { reverseLamps = v; return this; }
        public Builder horn(Boolean v) { horn = v; return this; }
        public Builder readings(Readings v) { readings = v; return this; }
        public Builder sentry(Integer v) { sentry = v; return this; }
        public Builder odometerKm(Float v) { odometerKm = v; return this; }
        public Builder position(Double lat, Double lon) { latitude = lat; longitude = lon; return this; }

        /** 改一扇门的那一位，其余位不动；之前不知道就从 0 起。 */
        public Builder door(int bit, boolean open) {
            int mask = doorsOpen == null ? 0 : doorsOpen;
            doorsOpen = open ? (mask | bit) : (mask & ~bit);
            return this;
        }

        /** 一个座位（一次一个位置位）的状态（{@link #SEAT_UNKNOWN} … {@link #SEAT_UNBELTED}），其余座位不动。 */
        public Builder seat(int bit, int state) {
            if (state < SEAT_UNKNOWN || state > SEAT_UNBELTED) {
                throw new IllegalArgumentException("seat state " + state);
            }
            int shift = seatShift(bit);
            seats = (seats & ~(3 << shift)) | (state << shift);
            return this;
        }

        public VehicleState build() {
            return new VehicleState(this);
        }

        private static Float clamp01(Float v) {
            if (v == null) {
                return null;
            }
            return Math.max(0f, Math.min(1f, v));
        }
    }
}
