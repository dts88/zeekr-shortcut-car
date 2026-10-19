package com.kooo.evcam.telemetry;

import com.kooo.evcam.R;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 信息条上放哪几格、各放在哪。
 *
 * <h3>放哪几格：系统信息里勾的（项目所有者 2026-10-03）</h3>
 *
 * <p>设置 → 系统 → 系统信息，每个信号前面一个勾，勾上的上信息条（{@link InfoBar#selection}）。
 * 有图标的信号带出它所在的那一格 —— 一格管几个信号的，勾其中任意一个就带出整格，数据照旧全用；
 * 没有图标的暂时画成文字格（名称 + 值，写法和系统信息页一样，见 {@link SignalText}）。一格列哪几个信号、
 * 什么才算「有图标」见 {@link Cell}：图标画出来的是同一个信息才算（项目所有者 2026-10-04）。
 * 勾上显示成哪几格，系统信息页每一行下面都写着（{@link #cellsFor}）。
 * 经纬度不是车辆信号，单独一项 {@link #POSITION_ITEM}。默认勾的是 2.0.10 信息条上在用的那些（{@link #defaultSelection}）。</p>
 *
 * <h3>放在哪</h3>
 *
 * <p>图标格按声明的顺序在前，文字格按信号表的顺序跟在后面，从左往右排；放不下的那一格和它后面的都不放 ——
 * 超出信息条长度的部分不显示，不再按优先级挑。每一格宽度固定，高都是 {@link InfoBar#HEIGHT}。</p>
 *
 * <p>纯函数，{@code InfoBarLayoutTest} 里测。</p>
 */
public final class InfoBarLayout {

    /** 勾选清单里经纬度那一项：系统定位，不是车辆信号。 */
    public static final String POSITION_ITEM = "POSITION";

    /**
     * 有图标的格：宽度（逻辑像素）、名字（系统信息页上说「勾上显示成哪一格」用）、表示哪几个信号。
     * 声明的顺序就是从左到右的顺序。
     *
     * <p>一格列的信号 = 这一格画出来的信息：画的时候读的，加上和画出来的是同一个信息的（转向指示显示、刹车踏板）。
     * 只是相关、不是同一个信息的不列（项目所有者 2026-10-04：勾上时有图标表示这个信息点就显示图标，没有才用文字）——
     * 自动驻车开关 ≠ 正在驻车，原厂画面弹出 / 泊车影像 ≠ 原厂 360 画面，灯光开关位置 ≠ 灯亮没亮，
     * 前 / 后备箱、充电口盖车厢图上没画，另外几项辅助开关不在那六块里 —— 它们照旧是文字格。</p>
     */
    public enum Cell {
        /**
         * 转向灯和双闪合成一格（项目所有者 2026-10-04，原来是左转、双闪、右转三格）：左箭头 | 双层嵌套三角 | 右箭头。
         * 勾转向指示状态、转向指示显示或左 / 右转向灯任意一个就上。转向指示显示（跟着闪的那个号）不拿来画，
         * 但它说的就是这一格画的「左 / 右 / 双闪」，所以也列在这里。
         */
        TURN(184, R.string.vi_cell_turn, Signal.INDICATOR, Signal.INDICATOR_DISPLAY, Signal.TURN_LEFT, Signal.TURN_RIGHT),
        /**
         * 方向盘随读数转；先用着，满舵几圈、最大读数多少等 Lab（{@link Signal#STEERING_DEGREES_PER_UNIT}）。
         * 78 宽，比自动驻车宽一点：三位数的角度数字要放在圈里（项目所有者 2026-10-04）；数字和度数符号压在方向盘上，描底色边。
         */
        STEERING(78, R.string.vi_cell_steering, Signal.STEERING),
        GEAR(70, R.string.vi_cell_gear, Signal.GEAR),
        /**
         * 刹车（上）和油门（下）两根横条，左边带深度数字。刹车踏板（踩没踩）不拿来画，
         * 但踩没踩在刹车条上看得见，所以也列在这里。
         */
        PEDALS(170, R.string.vi_cell_pedals, Signal.BRAKE_DEPTH, Signal.THROTTLE_DEPTH, Signal.BRAKE_PEDAL),
        /** 车速：数字靠右贴着 km/h，两位数时左边空着（项目所有者 2026-10-04 同意）。 */
        SPEED(172, R.string.vi_cell_speed, Signal.SPEED),
        /** 自动驻车「正在驻车」：停下被接管时亮，起步灭（不是功能开关 0x20060400，那个开车全程都亮）。 */
        AUTO_HOLD(70, R.string.vi_cell_auto_hold, Signal.AUTO_HOLD_ACTIVE),
        /** 原厂 360 画面显示中：它占着相机，我们的录像会断。 */
        STOCK_360(80, R.string.vi_cell_stock_360, Signal.STOCK_360),
        /** 哨兵模式：关 / 开 / 布防。放在原厂 360 右边（项目所有者 2026-10-03）；熄屏后环视录到什么时候看的就是它（规格 2.4）。 */
        SENTRY(80, R.string.vi_cell_sentry, Signal.SENTRY_MODE),
        /**
         * 俯视的车：四扇门 + 五个座位，每个座位没人 / 系着 / 有人没系 / 没数据（{@link VehicleState#seat}，
         * 项目所有者 2026-10-04）。前排看座椅有没有人加安全带；后排没有座椅传感器，只看安全带。
         * 副驾、后排安全带没验证：非开发者拿不到，副驾有人时那个座位画成没数据，后排画成没数据。
         */
        CABIN(110, R.string.vi_cell_cabin, Signal.DOOR_DRIVER, Signal.DOOR_PASSENGER, Signal.DOOR_REAR_LEFT,
                Signal.DOOR_REAR_RIGHT, Signal.BELT_DRIVER, Signal.BELT_PASSENGER,
                Signal.BELT_REAR_LEFT, Signal.BELT_REAR_CENTER, Signal.BELT_REAR_RIGHT,
                Signal.SEAT_DRIVER, Signal.SEAT_PASSENGER),
        /**
         * 前灯组：7X 车头（项目所有者 2026-10-04 定稿），和后灯组一样宽。日行灯或前位置灯亮，灯带下沿两条日行灯条就亮；
         * 下面两块大灯模块画近光 / 远光：远光（含闪远光）亮就按远光画 —— 一圈把整块灯包住的圆形光晕，否则近光亮按近光。
         * 所以近光、远光、闪远光也列在这一格：勾其中任意一个，前灯组和近光 / 远光那一格一起带出来。
         */
        DRL(106, R.string.vi_cell_front_view, Signal.DRL, Signal.FRONT_POSITION_LAMP, Signal.LOW_BEAM,
                Signal.HIGH_BEAM, Signal.HIGH_BEAM_FLASH),
        /**
         * 近光 + 远光一格：四道光线的方向说明开的是哪个 —— 都不亮灰色全斜向下，只近光全斜向下，
         * 只远光（含闪远光）全平直，同时开上两道平直、下两道斜向下。
         * 前灯组也画了近远光，这一格先留着（项目所有者 2026-10-04：还要看它好不好认）。
         */
        BEAMS(90, R.string.vi_cell_beams, Signal.LOW_BEAM, Signal.HIGH_BEAM, Signal.HIGH_BEAM_FLASH),
        /**
         * 后灯组：7X 车尾。贯穿尾灯暗红细条 = 后位置灯，亮红粗条 + 高位刹车灯 = 刹车灯；
         * 保险杠两侧各一盏两色灯，外侧亮红 = 后雾灯，内侧白 = 倒车灯，紧挨着，可以同时亮。
         * 车尾原来的比例不变，只去掉两边的空白（项目所有者 2026-10-04：压窄了就不像实车了）。
         */
        REAR_LAMPS(106, R.string.vi_cell_rear_lamps, Signal.REAR_POSITION_LAMP, Signal.STOP_LAMP, Signal.REAR_FOG,
                Signal.REVERSE_LAMP),
        /**
         * 六项安全辅助的开关：AEB、前碰预警、车道偏离、车道保持、盲区（读变道辅助那个开关，{@link Signal#LANE_CHANGE_ASSIST}）、后碰预警，
         * 各自一块，没数据的各自划掉。
         */
        ASSIST(200, R.string.vi_cell_assist, Signal.AEB, Signal.FCW, Signal.LDW, Signal.LKA, Signal.LANE_CHANGE_ASSIST, Signal.RCW),
        ODOMETER(160, R.string.vi_cell_odometer, Signal.ODOMETER),
        /** 经纬度（系统定位）：勾的是 {@link #POSITION_ITEM}。 */
        POSITION(170, R.string.vi_cell_position),
        /** 按喇叭（Lab 0.23.0：多半拿不到，五次测试都没有号跟着变）。只在车辆状态面板上。 */
        HORN(80, 0, false),
        /** 闪远光：只在车辆状态面板上（信息条上它算进前灯组和近光 / 远光那一格）。 */
        FLASH(90, 0, false, Signal.HIGH_BEAM_FLASH),
        /**
         * 双闪：只在车辆状态面板上（信息条上它算进转向灯那一格），和那一格中间的双层三角同一个画法。
         * 90 宽：亮时的光晕不出格。
         */
        HAZARD(90, 0, false, Signal.INDICATOR, Signal.TURN_LEFT, Signal.TURN_RIGHT);

        public final int width;
        /** 这一格的名字（字串资源）：系统信息页每一行下面说「勾上显示成哪一格」。只在车辆状态面板上的格没有名字（0）。 */
        public final int labelRes;
        /** 信息条上放不放（不放的只在车辆状态面板上用）。 */
        public final boolean onStrip;
        private final Signal[] signals;

        Cell(int width, int labelRes, Signal... signals) {
            this(width, labelRes, true, signals);
        }

        Cell(int width, int labelRes, boolean onStrip, Signal... signals) {
            this.width = width;
            this.labelRes = labelRes;
            this.onStrip = onStrip;
            this.signals = signals;
        }

        /** 这一格表示的信号（见 {@link Cell} 开头：画的时候读的，加上同一个信息的）。 */
        public List<Signal> signals() {
            return Collections.unmodifiableList(Arrays.asList(signals));
        }

        /** 勾了这些项，这一格上不上：用到的信号勾了任意一个就上（经纬度看它自己那一项）。 */
        boolean selectedBy(Set<String> selection) {
            if (this == POSITION) {
                return selection.contains(POSITION_ITEM);
            }
            for (Signal s : signals) {
                if (selection.contains(s.name())) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 左右留白。 */
    public static final int MARGIN = 16;
    /** 格与格之间。 */
    public static final int GAP = 12;
    /** 文字格的宽：上面名称、下面值，宽度固定，值变了格子不跳。 */
    public static final int TEXT_WIDTH = 170;

    /** 放好的一格：图标格（{@link #cell}）或文字格（{@link #text}），左上角在哪，多宽。 */
    public static final class Placed {
        /** 图标格；文字格是 null。 */
        public final Cell cell;
        /** 文字格画的信号；图标格是 null。 */
        public final Signal text;
        public final int x;
        public final int y;
        public final int width;

        Placed(Cell cell, Signal text, int x, int y, int width) {
            this.cell = cell;
            this.text = text;
            this.x = x;
            this.y = y;
            this.width = width;
        }
    }

    /** 一种摆法：哪几格放在哪，整块多大（逻辑像素，格子高都是 {@link InfoBar#HEIGHT}）。 */
    public static final class Arrangement {
        public final List<Placed> cells;
        public final int width;
        public final int height;

        Arrangement(List<Placed> cells, int width, int height) {
            this.cells = cells;
            this.width = width;
            this.height = height;
        }
    }

    /**
     * 车辆状态面板放哪几格，一行一组：喇叭、闪远光、双闪。
     * 用户 2026-10-03 定：只留这三项（哨兵模式在「熄屏持续录制」那一行、录制键上和信息条上）。
     */
    static final Cell[][] PANEL_ROWS = {
            {Cell.HORN, Cell.FLASH, Cell.HAZARD},
    };

    private InfoBarLayout() {
    }

    /** 车辆状态面板的摆法：每行居中，行与行之间、格与格之间都隔 {@link #GAP}。 */
    public static Arrangement panel() {
        int widest = 0;
        for (Cell[] row : PANEL_ROWS) {
            widest = Math.max(widest, rowWidth(row));
        }
        int width = widest + 2 * MARGIN;
        List<Placed> placed = new ArrayList<>();
        int y = 0;
        for (Cell[] row : PANEL_ROWS) {
            int x = (width - rowWidth(row)) / 2;
            for (Cell cell : row) {
                placed.add(new Placed(cell, null, x, y, cell.width));
                x += cell.width + GAP;
            }
            y += InfoBar.HEIGHT + GAP;
        }
        return new Arrangement(placed, width, y - GAP);
    }

    private static int rowWidth(Cell[] row) {
        int w = 0;
        for (Cell cell : row) {
            w += (w == 0 ? 0 : GAP) + cell.width;
        }
        return w;
    }

    /**
     * 勾上这一项（信号名，或者 {@link #POSITION_ITEM}），信息条上带出哪几格，按从左到右的顺序；
     * 空的 = 没有图标，画成文字格。{@link #fit} 和系统信息页（每一行下面那句「勾上显示成什么」）都看它。
     */
    public static List<Cell> cellsFor(String item) {
        Set<String> one = Collections.singleton(item);
        List<Cell> cells = new ArrayList<>();
        for (Cell cell : Cell.values()) {
            if (cell.onStrip && cell.selectedBy(one)) {
                cells.add(cell);
            }
        }
        return cells;
    }

    /** 这个信号在信息条上有图标（属于信息条上的某一格，{@link #cellsFor}）；没有的画成文字格。 */
    public static boolean hasIcon(Signal signal) {
        return !cellsFor(signal.name()).isEmpty();
    }

    /**
     * 默认勾哪些：2.0.10 信息条上在用的那几格（驾驶辅助那格当时整格划掉，不算）里能用的信号，加上经纬度。
     * 后来列进这几格的信号（座椅、转向指示显示、刹车踏板）跟着进默认，带出来的格不变。
     */
    public static Set<String> defaultSelection() {
        Set<String> names = new LinkedHashSet<>();
        for (Cell cell : Cell.values()) {
            if (!cell.onStrip || cell == Cell.ASSIST) {
                continue;
            }
            for (Signal s : cell.signals) {
                if (s.usable()) {
                    names.add(s.name());
                }
            }
        }
        names.add(POSITION_ITEM);
        return names;
    }

    /**
     * @param width     信息条（= 视频）的宽度
     * @param selection 勾了哪些（信号名，加上 {@link #POSITION_ITEM}）
     * @return 放得下的格，按显示顺序，带位置
     */
    public static List<Placed> fit(int width, Set<String> selection) {
        List<Placed> placed = new ArrayList<>();
        int right = width - MARGIN;
        int x = MARGIN;
        for (Cell cell : Cell.values()) {
            if (!cell.onStrip || !cell.selectedBy(selection)) {
                continue;
            }
            if (x + cell.width > right) {
                return placed;
            }
            placed.add(new Placed(cell, null, x, 0, cell.width));
            x += cell.width + GAP;
        }
        for (Signal signal : Signal.values()) {
            if (!selection.contains(signal.name()) || hasIcon(signal)) {
                continue;
            }
            if (x + TEXT_WIDTH > right) {
                return placed;
            }
            placed.add(new Placed(null, signal, x, 0, TEXT_WIDTH));
            x += TEXT_WIDTH + GAP;
        }
        return placed;
    }
}
