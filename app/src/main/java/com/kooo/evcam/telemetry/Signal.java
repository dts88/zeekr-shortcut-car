package com.kooo.evcam.telemetry;

import com.kooo.evcam.R;

/**
 * 车上能读到的信号，一张表：怎么读（功能号 / 带区域的功能号 / 传感器事件 / 传感器数值）、
 * 读到的数怎么解、在车上验证到什么程度。
 *
 * <p>信息条（{@link VehicleStateMapper}）和「系统信息」页都从这张表取，加一个信号就是加一行。
 * 号码和含义来自 zeekr-shortcut-lab 在 7X 上的实测：它的信号手册 {@code docs/signals.md} 是唯一依据
 * （分工：Lab 把信号找准，这里把找到的用准）。</p>
 *
 * <p>每条信号有一个可信程度（{@link Trust}）：实验中观察一致的、先用着的、没验证的。
 * 没验证的（{@linkplain #usable() 不能用}）只有开发者能勾上信息条，非开发者的信息条读数里没有它们（{@code InfoBar}）。</p>
 *
 * <p><b>全都是实验结论</b>：号码是在车外试出来的，一个操作常常让好几个号一起变（挂 R 一次动六七个），
 * 联动车外无从得知。所以「确认」只是「实验中观察一致」，Lab 每次运行都在交叉验证，对不上的在手册里降级，
 * 这里跟着改。用的时候只拿一个号当它本来的意思：踩没踩刹车看踏板不看刹车灯，倒没倒车看档位不看倒车灯。</p>
 *
 * <p>解码是纯函数（{@code SignalDecodeTest}）：占位值 255 / 254 / 253 / -1 / -65535，浮点 255 / -65535 和
 * 绝对值小于 1e-6 的非零数，都算「没数据」。</p>
 */
public enum Signal {

    // ---- 行驶
    GEAR(Group.DRIVE, Kind.SENSOR_EVENT, 0x00200200, 0, R.string.vi_gear, Trust.CONFIRMED, Format.GEAR),
    /**
     * 驾驶模式（Lab 手册「一」，2026-10-20 车主在车上来回切了 10 次对的）：切换那一刻推来。ECARX 的常量名和车机界面上的叫法对不上，
     * 按值用（{@link #DRIVE_MODE_COMFORT} 舒适、{@link #DRIVE_MODE_STANDARD} 标准、{@link #DRIVE_MODE_SPORT} 运动）；
     * 雪地、自定义这些还没对上，照原样写号码。
     */
    DRIVE_MODE(Group.DRIVE, Kind.FUNCTION, 0x22010100, 0, R.string.vi_drive_mode, Trust.CONFIRMED, Format.DRIVE_MODE),
    /**
     * 动力流（IHybrid.HYBRID_FUNC_POWER_FLOW，Lab 手册「一」，10-10 停车场逐段对过）：{@link #POWER_FLOW_DRIVE} 电机在出力
     * （D / R 不踩油门的蠕行也算）、{@link #POWER_FLOW_REGEN} 在回收、{@link #POWER_FLOW_IDLE} 不出力也不回收（车在走时也会是它：
     * 松油门先有 0.17–0.33 秒）、0 没就绪。回收只在 9–10 km/h 以上；单踏板时油门轻踩到 5–12 % 还在回收 —— 只看油门分不出来。
     */
    POWER_FLOW(Group.DRIVE, Kind.FUNCTION, 0x24010100, 0, R.string.vi_power_flow, Trust.CONFIRMED, Format.POWER_FLOW),
    SPEED(Group.DRIVE, Kind.SENSOR_VALUE, 0x00100100, 0, R.string.vi_speed, Trust.CONFIRMED, Format.MPS),
    IGNITION(Group.DRIVE, Kind.FUNCTION, 0x20259000, 0, R.string.vi_ignition, Trust.CONFIRMED, Format.IGNITION),
    BRAKE_PEDAL(Group.DRIVE, Kind.FUNCTION, 0x20317A00, 0, R.string.vi_brake_pedal, Trust.CONFIRMED, Format.ON_OFF),
    /** 停车踩到底 43.2–43.8，开车一般 10–20（Lab 0.19.0）；单位不明、不是 %，按踩到底 {@link #BRAKE_FULL} 换算成行程 %。 */
    BRAKE_DEPTH(Group.DRIVE, Kind.SENSOR_VALUE, 0x00101300, 0, R.string.vi_brake_depth, Trust.CONFIRMED, Format.BRAKE),
    /** 量程 0–100，单位 %（Lab 0.16.0 开车读到过 100.000）。 */
    THROTTLE_DEPTH(Group.DRIVE, Kind.SENSOR_VALUE, 0x00101400, 0, R.string.vi_throttle_depth, Trust.CONFIRMED, Format.PERCENT),
    /**
     * 单位是弧度，<b>左正右负</b>（ISO 8855 的习惯）：先左后右打到底，停在 8.789、-8.746（Lab 0.19.0）；
     * 打满约 ±8.75–8.95 ≈ ±501–513°，左满到右满约 2.8 圈，公开资料 2.7 圈。换算和方向在 {@link #STEERING_DEGREES_PER_UNIT}。
     */
    STEERING(Group.DRIVE, Kind.SENSOR_VALUE, 0x00101000, 0, R.string.vi_steering, Trust.CONFIRMED, Format.DEGREES),
    /** 自动驻车的功能开关（设置项），不是「正在驻车」——信息条不用它。 */
    AUTO_HOLD(Group.DRIVE, Kind.FUNCTION, 0x20060400, 0, R.string.vi_auto_hold, Trust.CONFIRMED, Format.ON_OFF),
    /** 自动驻车「正在驻车」（车辆保持时的灯光请求）：停下被接管时 1，起步回 0；中间踩放踏板不变（Lab 0.13.0）。 */
    AUTO_HOLD_ACTIVE(Group.DRIVE, Kind.FUNCTION, 0x20320600, 0, R.string.vi_auto_hold_active, Trust.CONFIRMED, Format.ON_OFF),

    // ---- 灯光
    INDICATOR(Group.LAMPS, Kind.FUNCTION, 0x2A091500, 0, R.string.vi_indicator, Trust.CONFIRMED, Format.INDICATOR),
    /** 转向指示显示：跟着灯闪（左 0 / 1、右 0 / 2、双闪 0 / 3）。信息条用不闪的 {@link #INDICATOR}。 */
    INDICATOR_DISPLAY(Group.LAMPS, Kind.FUNCTION, 0x2A091400, 0, R.string.vi_indicator_display, Trust.CONFIRMED, Format.INDICATOR),
    TURN_LEFT(Group.LAMPS, Kind.FUNCTION, 0x21051100, 0, R.string.vi_turn_left, Trust.CONFIRMED, Format.ON_OFF),
    TURN_RIGHT(Group.LAMPS, Kind.FUNCTION, 0x21051200, 0, R.string.vi_turn_right, Trust.CONFIRMED, Format.ON_OFF),
    LOW_BEAM(Group.LAMPS, Kind.FUNCTION, 0x21050100, 0, R.string.vi_low_beam, Trust.CONFIRMED, Format.ON_OFF),
    HIGH_BEAM(Group.LAMPS, Kind.FUNCTION, 0x21050200, 0, R.string.vi_high_beam, Trust.CONFIRMED, Format.ON_OFF),
    /** 闪远光：往前拨着拨杆时 1，松手回 0（轻拨也有 0.5 秒）；这时远光灯 0x21050200 一直是 0（Lab 0.16.0）。 */
    HIGH_BEAM_FLASH(Group.LAMPS, Kind.FUNCTION, 0x2A091300, 0, R.string.vi_high_beam_flash, Trust.CONFIRMED, Format.ON_OFF),
    /** 近光没亮时才是 1：切到位置灯档 0 → 1，关灯或近光一亮回 0；晚上前灯带亮着时报的是前位置灯（Lab 0.13.0，用户确认）。 */
    DRL(Group.LAMPS, Kind.FUNCTION, 0x21050900, 0, R.string.vi_drl, Trust.CONFIRMED, Format.ON_OFF),
    /** 一直 0，车机报 notavailable：这台车多半没装前雾灯。 */
    FRONT_FOG(Group.LAMPS, Kind.FUNCTION, 0x21050400, 0, R.string.vi_front_fog, Trust.UNVERIFIED, Format.ON_OFF),
    /** 0 关 / 1 开；要先开近光（Lab 0.13.0）。 */
    REAR_FOG(Group.LAMPS, Kind.FUNCTION, 0x21050500, 0, R.string.vi_rear_fog, Trust.CONFIRMED, Format.ON_OFF),
    FRONT_POSITION_LAMP(Group.LAMPS, Kind.FUNCTION, 0x21050800, 0, R.string.vi_front_position, Trust.CONFIRMED, Format.ON_OFF),
    REAR_POSITION_LAMP(Group.LAMPS, Kind.FUNCTION, 0x21050C00, 0, R.string.vi_rear_position, Trust.CONFIRMED, Format.ON_OFF),
    LIGHT_SWITCH(Group.LAMPS, Kind.FUNCTION, 0x20040E00, 0, R.string.vi_light_switch, Trust.CONFIRMED, Format.LIGHT_SWITCH),
    REVERSE_LAMP(Group.LAMPS, Kind.FUNCTION, 0x21050E00, 0, R.string.vi_reverse_lamp, Trust.CONFIRMED, Format.ON_OFF),
    STOP_LAMP(Group.LAMPS, Kind.FUNCTION, 0x21050D00, 0, R.string.vi_stop_lamp, Trust.CONFIRMED, Format.ON_OFF),

    // ---- 车身。区域值按 Lab 实测：0x1 = 主驾门（右舵车上是右前），0x4 = 副驾门，0x10 = 左后，0x40 = 右后，
    // 0x10000000 = 前备箱（ROOF_TOP），0x20000000 = 后备箱（VehicleZone 里没名字，直接传值）
    DOOR_DRIVER(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x1, R.string.vi_door_driver, Trust.CONFIRMED, Format.DOOR),
    DOOR_PASSENGER(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x4, R.string.vi_door_passenger, Trust.CONFIRMED, Format.DOOR),
    DOOR_REAR_LEFT(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x10, R.string.vi_door_rear_left, Trust.CONFIRMED, Format.DOOR),
    DOOR_REAR_RIGHT(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x40, R.string.vi_door_rear_right, Trust.CONFIRMED, Format.DOOR),
    DOOR_FRUNK(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x10000000, R.string.vi_door_frunk, Trust.CONFIRMED, Format.DOOR),
    DOOR_TRUNK(Group.BODY, Kind.FUNCTION_ZONE, 0x21020100, 0x20000000, R.string.vi_door_trunk, Trust.CONFIRMED, Format.DOOR),
    CHARGE_PORT(Group.BODY, Kind.FUNCTION, 0x21020500, 0, R.string.vi_charge_port, Trust.CONFIRMED, Format.DOOR),
    SUNROOF_SHADE(Group.BODY, Kind.FUNCTION_ZONE, 0x20080100, 0x8, R.string.vi_sunroof_shade, Trust.CONFIRMED, Format.RAW),
    /**
     * 主驾安全带：1 系着、0 没系。车刚唤醒那一刻会先读成 1（锁车 14 分钟后唤醒，没人坐、车门没开就 0 → 1，Lab 0.21.0，
     * 只见过一次）：唤醒后、有人坐下之前的读数不可信。信息条车厢图上系着优先（{@link VehicleStateMapper#seatState}）：
     * 这个假的 1 画出来是系着，不会误报红；红只在座椅有人、安全带读到没系时画。
     */
    BELT_DRIVER(Group.BODY, Kind.SENSOR_EVENT, 0x00201200, 0, R.string.vi_belt_driver, Trust.CONFIRMED, Format.BELT),
    BELT_PASSENGER(Group.BODY, Kind.SENSOR_EVENT, 0x00201300, 0, R.string.vi_belt_passenger, Trust.UNVERIFIED, Format.BELT),
    BELT_REAR_LEFT(Group.BODY, Kind.SENSOR_EVENT, 0x00201800, 0, R.string.vi_belt_rear_left, Trust.UNVERIFIED, Format.BELT),
    BELT_REAR_CENTER(Group.BODY, Kind.SENSOR_EVENT, 0x00201A00, 0, R.string.vi_belt_rear_center, Trust.UNVERIFIED, Format.BELT),
    BELT_REAR_RIGHT(Group.BODY, Kind.SENSOR_EVENT, 0x00201900, 0, R.string.vi_belt_rear_right, Trust.UNVERIFIED, Format.BELT),
    SEAT_DRIVER(Group.BODY, Kind.SENSOR_EVENT, 0x00203300, 0, R.string.vi_seat_driver, Trust.CONFIRMED, Format.SEAT),
    SEAT_PASSENGER(Group.BODY, Kind.SENSOR_EVENT, 0x00203400, 0, R.string.vi_seat_passenger, Trust.CONFIRMED, Format.SEAT),
    /** 后视镜倒车下翻：1 平常，4 正在下翻，2 翻下去了，3 正在回位（挂 R 1 → 4 → 2，出 R 2 → 3 → 1）。 */
    MIRROR_DIP_DRIVER(Group.BODY, Kind.FUNCTION_ZONE, 0x2031EF00, 0x1, R.string.vi_mirror_dip_driver, Trust.CONFIRMED, Format.MIRROR_DIP),
    MIRROR_DIP_PASSENGER(Group.BODY, Kind.FUNCTION_ZONE, 0x2031EF00, 0x4, R.string.vi_mirror_dip_passenger, Trust.CONFIRMED, Format.MIRROR_DIP),

    // ---- 原厂界面
    STOCK_360(Group.STOCK, Kind.FUNCTION, 0x2031FE00, 0, R.string.vi_stock_360, Trust.CONFIRMED, Format.SHOWN),
    /** 原厂画面弹出：原厂 360 或侧方小窗弹着时 1，都收起来回 0（Lab 0.13.0）。 */
    STOCK_POPUP(Group.STOCK, Kind.FUNCTION, 0x2031B200, 0, R.string.vi_stock_popup, Trust.CONFIRMED, Format.POPUP),
    PARK_ASSIST(Group.STOCK, Kind.FUNCTION, 0x23030100, 0, R.string.vi_park_assist, Trust.CONFIRMED, Format.ON_OFF),

    // ---- 环境 / 车辆
    ODOMETER(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100700, 0, R.string.vi_odometer, Trust.CONFIRMED, Format.KM),
    BATTERY(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00404000, 0, R.string.vi_battery, Trust.CONFIRMED, Format.PERCENT_RAW),
    RANGE(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100800, 0, R.string.vi_range, Trust.CONFIRMED, Format.KM),
    TEMP_OUTSIDE(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100B00, 0, R.string.vi_temp_outside, Trust.CONFIRMED, Format.CELSIUS),
    TEMP_INSIDE(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00100C00, 0, R.string.vi_temp_inside, Trust.CONFIRMED, Format.CELSIUS),
    BATTERY_TEMP(Group.VEHICLE, Kind.SENSOR_VALUE, 0x00102A00, 0, R.string.vi_battery_temp, Trust.CONFIRMED, Format.CELSIUS),
    DAY_NIGHT(Group.VEHICLE, Kind.SENSOR_EVENT, 0x00201000, 0, R.string.vi_day_night, Trust.CONFIRMED, Format.DAY_NIGHT),
    /**
     * 哨兵模式（SETTING_FUNC_VSTD_MODE_STS）：0 关，1 开，2 推测是锁车后布防（锁车一分半后 1 → 2，解锁回 1，挂 D 回 0；
     * Lab 0.18.0）。1 和 2 都算开着。Lab 还在对；用户 2026-10-02 点名先开放：熄屏后还能不能录，看的就是它。
     */
    SENTRY_MODE(Group.VEHICLE, Kind.FUNCTION, 0x20240100, 0, R.string.vi_sentry_mode, Trust.PROVISIONAL, Format.SENTRY),
    /**
     * 中控屏真的黑了没有（IVehicle.SETTING_FUNC_SCREEN_POWER_EVENT）：0 亮着，1 黑了，255 读不到。
     * 车主 2026-10-12 对着当天车上的熄屏、亮屏确认：屏幕真黑的那一刻它 0 → 1，和点火 ON → ACC 同一毫秒；
     * 开车门亮屏时 1 → 0，比点火 ACC → ON 晚不到 0.1 秒。安卓的熄屏广播要等车机下电才来（几分钟后），
     * 熄屏判断（{@code screen.ScreenState}）看的是它和点火。先用着：只在这台 7X 上看过那几次。
     */
    SCREEN_POWER_EVENT(Group.VEHICLE, Kind.FUNCTION, 0x20290300, 0, R.string.vi_screen_power_event, Trust.PROVISIONAL,
            Format.SCREEN),

    // ---- 安全辅助（读的是开关）
    AEB(Group.ASSIST, Kind.FUNCTION, 0x20070E00, 0, R.string.vi_aeb, Trust.CONFIRMED, Format.ON_OFF),
    /** 前碰预警的灵敏度：0 关，低 / 中 / 高是 0x200E0201–03（原来读的 0x200E0100 一直 255）。 */
    FCW(Group.ASSIST, Kind.FUNCTION, 0x200E0200, 0, R.string.vi_fcw, Trust.CONFIRMED, Format.LEVEL),
    LDW(Group.ASSIST, Kind.FUNCTION, 0x28084100, 0, R.string.vi_ldw, Trust.UNVERIFIED, Format.ON_OFF),
    LKA(Group.ASSIST, Kind.FUNCTION, 0x20070100, 0, R.string.vi_lka, Trust.CONFIRMED, Format.ON_OFF),
    RCW(Group.ASSIST, Kind.FUNCTION, 0x20071000, 0, R.string.vi_rcw, Trust.CONFIRMED, Format.ON_OFF),
    /**
     * 变道 / 盲区报警的开关（原车「变道辅助」）：Lab 从 0.9.0 起看到它跟着设置开关 0 / 1 变；信息条「安全辅助」那一格的 BSD 读它。
     * 以前 BSD 读的 0x28081600（BLIND_SPOT_ASSIST）一直 0、只有可用性随上电变，多半是别的功能，2026-10-16 拿掉。
     */
    LANE_CHANGE_ASSIST(Group.ASSIST, Kind.FUNCTION, 0x20070300, 0, R.string.vi_lane_change_assist, Trust.CONFIRMED, Format.ON_OFF),
    AUTO_LANE_CHANGE(Group.ASSIST, Kind.FUNCTION, 0x28040100, 0, R.string.vi_auto_lane_change, Trust.CONFIRMED, Format.ON_OFF),
    DOOR_OPEN_WARNING(Group.ASSIST, Kind.FUNCTION, 0x20120100, 0, R.string.vi_door_open_warning, Trust.CONFIRMED, Format.ON_OFF),
    LCC(Group.ASSIST, Kind.FUNCTION, 0x28085B00, 0, R.string.vi_lcc, Trust.UNVERIFIED, Format.ON_OFF);

    /** 可信到什么程度。 */
    public enum Trust {
        /** Lab 手册「实验中观察一致的」一节里有它，这里用到的含义、取值、量程都没挂着「待测 / 待定」 */
        CONFIRMED,
        /** 车上跟着变、能用，但含义 / 量程 / 某个场景还等 Lab 测；用户点名先开放的（现在是哨兵模式、屏幕电源事件） */
        PROVISIONAL,
        /** 读得到，含义没对上，或者还没专门测过 */
        UNVERIFIED
    }

    /** 怎么读。 */
    public enum Kind {
        /** {@code ICarFunction.getFunctionValue(id)} */
        FUNCTION,
        /** {@code ICarFunction.getFunctionValue(id, zone)} */
        FUNCTION_ZONE,
        /** {@code ISensor.getSensorEvent(type)}：枚举，值 = 类型 + 序号 */
        SENSOR_EVENT,
        /** {@code ISensor.getSensorLatestValue(type)}：浮点 */
        SENSOR_VALUE
    }

    /** 页面上的分组。 */
    public enum Group {
        DRIVE(R.string.vi_group_drive),
        LAMPS(R.string.vi_group_lamps),
        BODY(R.string.vi_group_body),
        STOCK(R.string.vi_group_stock),
        VEHICLE(R.string.vi_group_vehicle),
        ASSIST(R.string.vi_group_assist);

        public final int labelRes;

        Group(int labelRes) {
            this.labelRes = labelRes;
        }
    }

    /** 读到的数是什么意思；也决定页面上怎么写。 */
    public enum Format {
        /** 0 关 1 开 → Boolean */
        ON_OFF,
        /** 0 关，其余是档位（枚举）→ Boolean（不是 0 就算开） */
        LEVEL,
        /** 灯光开关的位置 → Integer（0 关、0x20040E01 位置灯、0x20040E03 自动） */
        LIGHT_SWITCH,
        /** 0 关着 1 开着 → Boolean（开着为 true）；开关过程中的 0x…01 算没数据 */
        DOOR,
        /** 1 系着 0 没系 → Boolean（系着为 true） */
        BELT,
        /** 低字节 1 没人 2 有人 → Boolean（有人为 true） */
        SEAT,
        /** 1 显示中 2 平时 → Boolean（显示中为 true） */
        SHOWN,
        /** 0 收起 1 弹着 → Boolean（弹着为 true） */
        POPUP,
        /** 后视镜下翻的四个状态 → Integer（1 平常、4 正在下翻、2 翻下去了、3 正在回位） */
        MIRROR_DIP,
        /** 白天 / 夜晚的枚举码 → Integer（0x00201001 白天、0x00201002 夜晚） */
        DAY_NIGHT,
        /** 哨兵模式 → Integer（0 关、1 开、2 布防） */
        SENTRY,
        /** 屏幕电源事件 0 亮着 1 黑了 → Boolean（黑了为 true）；页面上写熄屏 / 亮屏，不写开 / 关（{@link SignalText}） */
        SCREEN,
        /** 0 关 1 左 2 右 3 双闪 → Integer */
        INDICATOR,
        /** 点火状态的枚举码 → Integer（0x00200104 ACC、05 ON、07 DRIVING） */
        IGNITION,
        /** 档位枚举 → 字母 P / R / N / D */
        GEAR,
        /** 驾驶模式的枚举码 → Integer（{@link #DRIVE_MODE_COMFORT} / {@link #DRIVE_MODE_STANDARD} / {@link #DRIVE_MODE_SPORT}，别的照原样） */
        DRIVE_MODE,
        /** 动力流的枚举码 → Integer（{@link #POWER_FLOW_DRIVE} / {@link #POWER_FLOW_REGEN} / {@link #POWER_FLOW_IDLE} / 0 没就绪，别的照原样） */
        POWER_FLOW,
        /** 原样的整数（量程待定） → Integer */
        RAW,
        /** 传感器给的是 m/s（0.2778 = 1 km/h）→ 乘 3.6 记成 km/h（Float） */
        MPS,
        /** 浮点 → Float */
        KMH, KM, PERCENT, PERCENT_RAW, DEGREES, CELSIUS,
        /** 刹车深度读数 → 行程 %（读数 / {@link #BRAKE_FULL} × 100，Float），和油门一样是 0–100 */
        BRAKE
    }

    /**
     * 方向盘转角：读数（弧度）→ 方向盘转过的度数，正 = 向右（顺时针）。信息条上的数字、左黄右白、图标的转动都用它。
     * 车给的是左正右负（Lab 0.19.0），我们这边正 = 向右，所以带负号。
     */
    public static final float STEERING_DEGREES_PER_UNIT = (float) (-180.0 / Math.PI);

    /** 刹车深度踩到底的读数（Lab 0.19.0：43.2–43.8 两次）；读数 / 它 = 行程。 */
    public static final float BRAKE_FULL = 44f;

    public static final int LIGHT_SWITCH_POSITION = 0x20040E01;
    public static final int LIGHT_SWITCH_LOW_BEAM = 0x20040E02;
    public static final int LIGHT_SWITCH_AUTO = 0x20040E03;

    public static final int MIRROR_NORMAL = 1;
    public static final int MIRROR_DOWN = 2;
    public static final int MIRROR_RETURNING = 3;
    public static final int MIRROR_TILTING = 4;

    public static final int DAY = 0x00201001;
    public static final int NIGHT = 0x00201002;

    /** 驾驶模式：ECARX 叫 ECO，车机界面上是「舒适」（Lab 2026-10-20）。 */
    public static final int DRIVE_MODE_COMFORT = 0x22010101;
    /** 驾驶模式：ECARX 叫 COMFORT，车机界面上是「标准」。 */
    public static final int DRIVE_MODE_STANDARD = 0x22010102;
    /** 驾驶模式：ECARX 叫 DYNAMIC，车机界面上是「运动」。 */
    public static final int DRIVE_MODE_SPORT = 0x22010103;

    /** 动力流：电机在出力（REAR_ELE_DRIVE，604045587）。 */
    public static final int POWER_FLOW_DRIVE = 0x24010113;
    /** 动力流：不出力也不回收（STANDSTILL，604045588）。 */
    public static final int POWER_FLOW_IDLE = 0x24010114;
    /** 动力流：在回收（REGENERATION，604045589）。 */
    public static final int POWER_FLOW_REGEN = 0x24010115;
    /** 动力流：没就绪（NOT_READY，还没上高压）。 */
    public static final int POWER_FLOW_NOT_READY = 0;

    public static final int IGNITION_ACC = 0x00200104;
    public static final int IGNITION_ON = 0x00200105;
    public static final int IGNITION_DRIVING = 0x00200107;

    public final Group group;
    public final Kind kind;
    public final int id;
    /** 带区域读时的区域值；不带区域的为 0。 */
    public final int zone;
    public final int labelRes;
    public final Trust trust;
    public final Format format;

    Signal(Group group, Kind kind, int id, int zone, int labelRes, Trust trust, Format format) {
        this.group = group;
        this.kind = kind;
        this.id = id;
        this.zone = zone;
        this.labelRes = labelRes;
        this.trust = trust;
        this.format = format;
    }

    /** 能不能直接用：确认了的，或者先用着的。 */
    public boolean usable() {
        return trust != Trust.UNVERIFIED;
    }

    public boolean isSensor() {
        return kind == Kind.SENSOR_EVENT || kind == Kind.SENSOR_VALUE;
    }

    /** 浮点传感器：不订阅，按节拍读（变一点就推，订了只是噪声）。 */
    public boolean isFloat() {
        return kind == Kind.SENSOR_VALUE;
    }

    // ================================================================= 解码（纯函数）

    /**
     * 原始读数 → 按 {@link #format} 归一的值；没数据或解不开返回 null。
     *
     * @param raw Integer（功能号、传感器事件）或 Float（传感器数值）
     */
    public Object decode(Object raw) {
        if (raw == null) {
            return null;
        }
        if (format == Format.BRAKE) {
            Float depth = raw instanceof Number ? floatOrNull(((Number) raw).floatValue()) : null;
            return depth == null ? null : depth / BRAKE_FULL * 100f;
        }
        if (format == Format.DEGREES) {
            Float units = raw instanceof Number ? floatOrNull(((Number) raw).floatValue()) : null;
            return units == null ? null : units * STEERING_DEGREES_PER_UNIT;
        }
        if (format == Format.MPS) {
            Float mps = raw instanceof Number ? floatOrNull(((Number) raw).floatValue()) : null;
            return mps == null ? null : mps * 3.6f;
        }
        if (format == Format.KMH || format == Format.KM || format == Format.PERCENT
                || format == Format.PERCENT_RAW || format == Format.DEGREES || format == Format.CELSIUS) {
            return raw instanceof Number ? floatOrNull(((Number) raw).floatValue()) : null;
        }
        if (!(raw instanceof Number)) {
            return null;
        }
        Integer v = intOrNull(((Number) raw).intValue());
        if (v == null) {
            return null;
        }
        switch (format) {
            case ON_OFF:
            case DOOR:
                return onOff(v);
            case BELT:
                return onOff(v);
            case SEAT:
                switch (v & 0xFF) {
                    case 1: return Boolean.FALSE;
                    case 2: return Boolean.TRUE;
                    default: return null;
                }
            case SHOWN:
                // 1 原厂 360 画面；2 没显示；0 多半是打转向灯弹的侧方小窗（挂 D 那一刻也闪过 60 ms 的 0，Lab 0.16.0）——
                // 不拿 0 判断侧方画面，只认「1 = 360 在」，所以 0 和 2 都是不在
                return v == 1 ? Boolean.TRUE : (v == 2 || v == 0 ? Boolean.FALSE : null);
            case POPUP:
            case SCREEN:
                return onOff(v);
            case MIRROR_DIP:
                return v >= MIRROR_NORMAL && v <= MIRROR_TILTING ? v : null;
            case DAY_NIGHT:
                return v == DAY || v == NIGHT ? v : null;
            case SENTRY:
                return v >= 0 && v <= 2 ? v : null;
            case INDICATOR:
                return v >= 0 && v <= 3 ? v : null;
            case LEVEL:
                return v != 0;
            case IGNITION:
            case LIGHT_SWITCH:
            case DRIVE_MODE:
            case POWER_FLOW:
            case RAW:
                return v;
            case GEAR:
                return gearLetter(v);
            default:
                return null;
        }
    }

    /** ECARX 的整数占位值：255 未知、254 无、253 错误，车上还见过 -1 和 -65535。 */
    static Integer intOrNull(int v) {
        if (v == 255 || v == 254 || v == 253 || v == -1 || v == -65535) {
            return null;
        }
        return v;
    }

    /** 浮点占位值：255、-65535，以及「没数据」时那个极小的非零数。0 是真的 0。 */
    static Float floatOrNull(float v) {
        if (Float.isNaN(v) || v == 255f || v == -65535f) {
            return null;
        }
        if (v != 0f && Math.abs(v) < 1e-6f) {
            return null;
        }
        return v;
    }

    /** 0 关 / 1 开；别的（2 默认、开关过程中的中间态）算不知道。 */
    static Boolean onOff(Integer v) {
        if (v == null) {
            return null;
        }
        if (v == 0) {
            return Boolean.FALSE;
        }
        if (v == 1) {
            return Boolean.TRUE;
        }
        return null;
    }

    /** 档位传感器事件：P = 0x00200230、R = 0x00200240、N = 0x00200210、D = 0x00200220（车上实测）。 */
    static String gearLetter(int event) {
        switch (event) {
            case 0x00200230: return "P";
            case 0x00200240: return "R";
            case 0x00200210: return "N";
            case 0x00200220: return "D";
            default: return null;
        }
    }
}
