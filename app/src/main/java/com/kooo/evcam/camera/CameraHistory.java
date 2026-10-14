package com.kooo.evcam.camera;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 每一台相机的来历（项目所有者 2026-10-15 批准，2.11.5 第 D1、D3 项；只记录，不改任何判断）：车机重新初始化它以来我们开过几台设备、
 * 上一次重置是什么、什么时候；我们最近开的那一台是第几台；我们最后一次关完它是什么时候、关了多久。跨进程、跨安装留着
 * （{@code StepLog} 存在应用目录 {@code channel/history.txt}），退出、装新版都不清。
 *
 * <h3>为什么</h3>
 *
 * <p>10-15 的统计（rel_final §2）：熄屏放开座舱时环视跟着断了的 8 次，环视都不是车机重新初始化之后我们开的第一台设备、主界面都在后台；
 * 第一台的 10 次一次都没断；不是第一台、主界面在前的 2 次也没断。「重新初始化」是这几样（都在我们下面，退出、装新版不清它们）：</p>
 *
 * <ul>
 *   <li>{@link Reset#BOOT} 车机开机（重启过：黑匣子的开机记录，或者存下的开机时长比此刻还长）；</li>
 *   <li>{@link Reset#TAKEN} 车机自己用了一下这台相机：相机服务说它被别的程序拿了、又空出来（「争用：…空出来了」，醒来时那 1 秒就是）；</li>
 *   <li>{@link Reset#CAME_BACK} 它从相机服务的缓存里没了、又回来了（「回来了」）。</li>
 * </ul>
 *
 * <p>以前要事后从几份黑匣子里一台一台数（数错过：A1、A6、A7 一开始数成第 3 / 4 / 3 台，按每一次开设备数、救援重开也算，其实都是第 5 台）。
 * 这里当场数：相机服务把设备交给我们一次（{@code onOpened}，开好之后马上又关掉的也算）就数一台。在我们下面没被看到的重新初始化
 * （进程没在跑的时候车机醒了又用了它）数不到 —— 「上次重置」那一刻照写，看的人自己判断。</p>
 *
 * <h3>钟</h3>
 *
 * <p>重置、关完的时刻存开机以来含深睡的毫秒（{@code elapsedRealtime}：同一次开机里各个进程对得上，「离上次关」跨进程算）和墙上时钟
 * （写给人看）。车机重启过（{@link #restore}）：每一台都从车机开机那一刻重新数，上一次开机里的关不算。</p>
 *
 * <p>纯 Java（{@code CameraHistoryTest}），时刻由调用方传入。方法都上锁：相机线程（开好、关完）、主线程（相机服务的话、调度读）都会来。
 * 存盘的那一份读回来之前（进程刚起来那几毫秒）先记在内存里，读回来时并进去（{@link #restore}）。</p>
 */
public final class CameraHistory {

    /** 文件第一行的版本记号。 */
    static final String VERSION = "h1";

    /** 车机重新初始化了一台相机：从这里重新数第几台。 */
    public enum Reset {
        /** 车机开机（重启）。 */
        BOOT,
        /** 车机自己用了一下：相机服务说它被别的程序拿了、又空出来。 */
        TAKEN,
        /** 它从相机服务的缓存里没了、又回来了。 */
        CAME_BACK
    }

    /** 一台相机此刻的来历（不可变）。没有的值见各项。 */
    public static final class Camera {
        public final String id;
        /** 上一次重置以来我们开过几台设备。 */
        public final int opens;
        /** 上一次重置：是什么、墙上时钟、开机以来（含深睡）；记录开始以来没见过重置是 null / 0 / -1。 */
        public final Reset reset;
        public final long resetWall;
        public final long resetElapsed;
        /** 这一台的记录从什么时候开始（墙上时钟）：没见过重置时，第几台从这里数。 */
        public final long sinceWall;
        /** 我们最近开的那一台是第几台（开的那一刻数的，之后再重置也不变）；还没开过是 0。 */
        public final int lastOrdinal;
        /** 那一台开的时候上一次重置是什么、什么时候（墙上时钟）；那时还没见过重置是 null / 0。 */
        public final Reset lastReset;
        public final long lastResetWall;
        /** 最后一次关完：开机以来（含深睡）的时刻、关了多久、为什么关（英文短语）；没有是 -1 / -1 / null。 */
        public final long closedElapsed;
        public final long closeMs;
        public final String closeWhy;

        Camera(State s) {
            this.id = s.id;
            this.opens = s.opens;
            this.reset = s.reset;
            this.resetWall = s.resetWall;
            this.resetElapsed = s.resetElapsed;
            this.sinceWall = s.sinceWall;
            this.lastOrdinal = s.lastOrdinal;
            this.lastReset = s.lastReset;
            this.lastResetWall = s.lastResetWall;
            this.closedElapsed = s.closedElapsed;
            this.closeMs = s.closeMs;
            this.closeWhy = s.closeWhy;
        }
    }

    /** 一台相机的账（锁里改）。 */
    private static final class State {
        final String id;
        int opens;
        Reset reset;
        long resetWall;
        long resetElapsed = -1;
        long sinceWall;
        int lastOrdinal;
        Reset lastReset;
        long lastResetWall;
        long closedElapsed = -1;
        long closeMs = -1;
        String closeWhy;

        State(String id) {
            this.id = id;
        }

        void resetAt(Reset why, long wall, long elapsed) {
            opens = 0;
            reset = why;
            resetWall = wall;
            resetElapsed = elapsed;
        }

        void closeFrom(State other) {
            closedElapsed = other.closedElapsed;
            closeMs = other.closeMs;
            closeWhy = other.closeWhy;
        }

        void forgetClose() {
            closedElapsed = -1;
            closeMs = -1;
            closeWhy = null;
        }
    }

    private final Map<String, State> cameras = new LinkedHashMap<>();
    /** 读回存盘的那一份了没有（{@link #restore}）。 */
    private boolean restored;
    /**
     * 这一次开机我们从头看着（{@link #restore} 判出车机重启过、或者同一次开机里早先的进程判出过）：之后第一次见到的相机
     * 也从开机那一刻数。墙上时钟；0 = 不知道。
     */
    private long bootWall;

    /**
     * 相机服务交给我们一台设备（开好了）：这一台是第几台。
     *
     * @return 这一台是第几台；没有编号是 0
     */
    public synchronized int opened(String id, long wallNow) {
        if (id == null) {
            return 0;
        }
        State s = state(id, wallNow);
        s.opens++;
        s.lastOrdinal = s.opens;
        s.lastReset = s.reset;
        s.lastResetWall = s.resetWall;
        return s.opens;
    }

    /** 车机重新初始化了这一台：从头数。 */
    public synchronized void reset(String id, Reset why, long wallNow, long elapsedNow) {
        if (id == null || why == null) {
            return;
        }
        state(id, wallNow).resetAt(why, wallNow, elapsedNow);
    }

    /** 我们关完了这一台的一个设备（任何一种关）。 */
    public synchronized void closed(String id, long wallNow, long elapsedNow, long ms, String why) {
        if (id == null) {
            return;
        }
        State s = state(id, wallNow);
        s.closedElapsed = elapsedNow;
        s.closeMs = ms;
        s.closeWhy = why;
    }

    /** 这一台此刻的来历；从没见过它是 null。 */
    public synchronized Camera camera(String id) {
        State s = id == null ? null : cameras.get(id);
        return s == null ? null : new Camera(s);
    }

    /** 读回存盘的那一份了没有。 */
    public synchronized boolean restored() {
        return restored;
    }

    /**
     * 读回存盘的那一份（进程起来时一次，在写盘的那条线程上），并上读回来之前已经记下的：
     *
     * <ul>
     *   <li>车机重启过（{@code rebooted}，或者存下的开机时长比此刻还长 —— 开机时长从头来过了）：存下的每一台从开机那一刻重新数，
     *       上一次开机里的关不算；之后第一次见到的相机也从开机那一刻数；</li>
     *   <li>读回来之前那几毫秒里重置过的：照它（比存下的新）；只开过、关过的：开的台数接在存下的后面，关照新的。</li>
     * </ul>
     *
     * @param text     存下的那一份（{@link #encode}）；没有是 null
     * @param rebooted 黑匣子的开机记录说这是车机重启后我们第一次运行
     */
    public synchronized void restore(String text, boolean rebooted, long wallNow, long elapsedNow) {
        Map<String, State> stored = new LinkedHashMap<>();
        long[] head = new long[]{-1, 0};
        decode(text, stored, head);
        boolean boot = rebooted || head[0] > elapsedNow;
        if (boot) {
            bootWall = wallNow - elapsedNow;
            for (State s : stored.values()) {
                s.resetAt(Reset.BOOT, bootWall, 0);
                s.forgetClose();
            }
        } else if (head[1] != 0 && bootWall == 0) {
            bootWall = head[1];   // 同一次开机里早先的进程判出过车机重启：之后第一次见到的相机照旧从开机数
        }
        for (State mem : cameras.values()) {
            State old = stored.get(mem.id);
            if (old == null) {
                if (boot && mem.reset == null) {
                    anchorAtBoot(mem);
                }
                stored.put(mem.id, mem);
                continue;
            }
            if (mem.reset == null) {
                // 读回来之前只开过、关过：台数接在存下的后面
                if (mem.lastOrdinal > 0) {
                    old.lastOrdinal = old.opens + mem.lastOrdinal;
                    old.lastReset = old.reset;
                    old.lastResetWall = old.resetWall;
                }
                old.opens += mem.opens;
            } else {
                // 读回来之前重置过：照它，存下的台数不算
                long sinceWall = old.sinceWall;
                old.resetAt(mem.reset, mem.resetWall, mem.resetElapsed);
                old.opens = mem.opens;
                old.sinceWall = sinceWall;
                if (mem.lastOrdinal > 0) {
                    old.lastOrdinal = mem.lastOrdinal;
                    old.lastReset = mem.lastReset;
                    old.lastResetWall = mem.lastResetWall;
                }
            }
            if (mem.closedElapsed >= 0) {
                old.closeFrom(mem);
            }
        }
        cameras.clear();
        cameras.putAll(stored);
        restored = true;
    }

    /** 存下来的样子：第一行版本和此刻的开机时长，之后一台一行；只用 ASCII 的「键=值」，读不懂的键跳过、坏行整行跳过。 */
    public synchronized String encode(long elapsedNow) {
        StringBuilder sb = new StringBuilder(VERSION).append(" e=").append(elapsedNow);
        put(sb, "bw", bootWall, 0);
        sb.append('\n');
        for (State s : cameras.values()) {
            sb.append("c=").append(StepStats.token(s.id));
            put(sb, "n", s.opens, 0);
            put(sb, "r", s.reset == null ? null : s.reset.name());
            put(sb, "rw", s.resetWall, 0);
            put(sb, "re", s.resetElapsed, -1);
            put(sb, "sw", s.sinceWall, 0);
            put(sb, "lo", s.lastOrdinal, 0);
            put(sb, "lr", s.lastReset == null ? null : s.lastReset.name());
            put(sb, "lw", s.lastResetWall, 0);
            put(sb, "ce", s.closedElapsed, -1);
            put(sb, "cm", s.closeMs, -1);
            put(sb, "cw", s.closeWhy == null ? null : StepStats.token(s.closeWhy));
            sb.append('\n');
        }
        return sb.toString();
    }

    /**
     * 读存下的那一份进 {@code out}；第一行的两项交回 {@code head}：存的时候的开机时长（读不出来是 -1）、
     * 早先判出的这一次开机的时刻（墙上时钟，0 = 没有）。读不懂的一台跳过（只是诊断，那一台从头数）。
     */
    private static void decode(String text, Map<String, State> out, long[] head) {
        if (text == null) {
            return;
        }
        String[] lines = text.split("\n");
        Map<String, String> first = lines.length == 0 ? null : fields(lines[0]);
        if (first == null || !first.containsKey(VERSION)) {
            return;
        }
        try {
            head[0] = number(first, "e", -1);
            head[1] = number(first, "bw", 0);
        } catch (IllegalArgumentException e) {
            head[0] = -1;
            head[1] = 0;
            return;
        }
        for (int i = 1; i < lines.length; i++) {
            Map<String, String> f = fields(lines[i]);
            String id = f == null ? null : f.get("c");
            if (id == null || id.isEmpty()) {
                continue;
            }
            try {
                State s = new State(id);
                s.opens = (int) number(f, "n", 0);
                s.reset = reset(f.get("r"));
                s.resetWall = number(f, "rw", 0);
                s.resetElapsed = number(f, "re", -1);
                s.sinceWall = number(f, "sw", 0);
                s.lastOrdinal = (int) number(f, "lo", 0);
                s.lastReset = reset(f.get("lr"));
                s.lastResetWall = number(f, "lw", 0);
                s.closedElapsed = number(f, "ce", -1);
                s.closeMs = number(f, "cm", -1);
                s.closeWhy = f.get("cw");
                out.put(id, s);
            } catch (IllegalArgumentException e) {
                // 数坏了、名字认不出：这一台不要
            }
        }
    }

    /** 这一台的账；第一次见到就建一份（这一次开机我们从头看着的，从开机那一刻数）。 */
    private State state(String id, long wallNow) {
        State s = cameras.get(id);
        if (s == null) {
            s = new State(id);
            s.sinceWall = wallNow;
            if (bootWall != 0) {
                anchorAtBoot(s);
            }
            cameras.put(id, s);
        }
        return s;
    }

    /** 从这一次开机那一刻数（开的台数不动：读回来之前开的都在开机之后）。 */
    private void anchorAtBoot(State s) {
        int opens = s.opens;
        s.resetAt(Reset.BOOT, bootWall, 0);
        s.opens = opens;
        if (s.lastOrdinal > 0 && s.lastReset == null) {
            s.lastReset = Reset.BOOT;
            s.lastResetWall = bootWall;
        }
    }

    /** 和读的时候的缺省值一样的不写。 */
    private static void put(StringBuilder sb, String name, long value, long fallback) {
        if (value != fallback) {
            sb.append(' ').append(name).append('=').append(value);
        }
    }

    private static void put(StringBuilder sb, String name, String value) {
        if (value != null && !value.isEmpty()) {
            sb.append(' ').append(name).append('=').append(value);
        }
    }

    /** 一行的键值；没有等号的（第一行的版本记号）记成键、值是空的。 */
    private static Map<String, String> fields(String line) {
        if (line == null || line.trim().isEmpty()) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String part : line.trim().split(" ")) {
            int eq = part.indexOf('=');
            if (eq > 0) {
                values.put(part.substring(0, eq), part.substring(eq + 1));
            } else if (!part.isEmpty()) {
                values.put(part, "");
            }
        }
        return values;
    }

    private static Reset reset(String name) {
        return name == null || name.isEmpty() ? null : Reset.valueOf(name);
    }

    private static long number(Map<String, String> values, String name, long fallback) {
        String v = values.get(name);
        return v == null ? fallback : Long.parseLong(v);
    }

    /** 测试用：记着的几台相机的编号，按第一次见到的先后。 */
    synchronized List<String> ids() {
        return new ArrayList<>(cameras.keySet());
    }
}
