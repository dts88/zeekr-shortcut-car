package com.kooo.evcam.camera;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 相机步骤统计（项目所有者 2026-10-14：「识别前后座舱摄像头被卡住的原因或者统计」，2.11.4 R2）：调度每结束一步记一条
 * （{@link Entry}，一行文字，存在应用目录里，最近 {@link #MAX} 条，跨进程留着 —— {@code StepLog}），诊断报告
 * 「2.3.3 相机步骤统计」照它分组数（{@link #tally}、{@link #groupBy}、{@link #cabinClosesBySurround}）。
 * 2.11.5 起还记关座舱时环视那一条（{@link Move#COUPLE}，{@link #surroundAfterCloses}）、丢了之后那一次关和救援重开时关旧设备
 * （{@link CloseKind}）、环视那一台是车机重新初始化后第几台（{@link #lineageBucket}）。
 *
 * <h3>为什么</h3>
 *
 * <p>2.11 以来座舱开了 40 次、2 次 15 秒没画面（都是后座舱、都是哨兵开着），环视 18 次里 3 次（都是哨兵关着）。
 * 哨兵、主界面在不在前、离我们上一次关它多久、是不是车机刚放开它，样本太少又互相纠缠，每一次都是事后从几份黑匣子里拼出来的，
 * 拼错过（9 次「不知道」其实都是哨兵关）。这里每一步当场记下结果和那一刻的这几样，报告里直接分组数：哪一种情形下没画面多，
 * 一眼看得出来，样本攒够了再谈原因。备选方案「关座舱时先短暂关环视」要比的（座舱关的时候环视在不在出画面，关得慢不慢、
 * 之后那一次开有没有画面）也在这里（R3，只记录）。</p>
 *
 * <p>只用 ASCII 的「键=值」，空格分开；读不懂的键跳过、坏行整行跳过：以后加字段，旧的行照样读。纯 Java（{@code StepStatsTest}），
 * 不写中文 —— 句子在 DiagnosticsCollector 里拼。</p>
 */
public final class StepStats {

    /** 最多留几条。 */
    public static final int MAX = 200;

    /** 「关得慢」：超过这么久。 */
    public static final long SLOW_CLOSE_MS = 1_000L;

    /**
     * 关座舱之后环视「断了」：关完 30 秒里最长断帧到这么久（{@link Move#COUPLE}，2.11.5 第 D3 项）。平常关座舱时环视断帧不到 0.2 秒；
     * 断了的那几次（10-13 23:59、10-14 21:52）都是 8 秒以上、救援接手。1 秒在两者之间，断了多久照记。
     */
    public static final long SURROUND_STALL_MS = 1_000L;

    /** 行首的版本记号。 */
    static final String VERSION = "v1";

    private StepStats() {
    }

    /**
     * 哪一种动作：开、救援里的重开、救援里的重建、改输出、关；再加关座舱时环视那一条（{@link #COUPLE}：不是一步，
     * 是一次关座舱、环视开着，关完 30 秒量的环视；{@link Entry#key} 是环视，{@link Entry#ms} 是那 30 秒里环视最长断帧）。
     */
    public enum Move {
        OPEN,
        REOPEN,
        REBUILD,
        APPLY,
        CLOSE,
        COUPLE
    }

    /**
     * 关的是哪一种（{@link Entry#closeKind}，2.11.5 第 D3 项）：没有这一项的是计划里的关（没人要、保持到点、熄屏放开、熄屏、车机要睡、退出）。
     * 这两种以前不进统计 —— 救环视时关旧设备卡 4.5–5.8 秒、丢了之后那一次关卡 5–8 秒，统计里一次都看不到。
     */
    public enum CloseKind {
        /** 丢了之后那一次关（被断开 —— 车机拿走 —— 或者报错）。 */
        LOSS,
        /** 救援重开时关旧设备。 */
        REOPEN
    }

    /** 结果。 */
    public enum Result {
        /** 出了第一帧（{@link Entry#ms} 是用了多久）。 */
        FRAMED,
        /** 15 秒没出画面（到点）。 */
        NO_FRAMES,
        /** 这一步里丢了、开不起来（{@link Entry#error}）。 */
        ERROR,
        /** 关完了（{@link Entry#ms} 是关了多久）。 */
        CLOSED,
        /** 关到点还没关完。 */
        CLOSE_TIMED_OUT,
        /** 关座舱时环视那一条：关完 30 秒里环视没断（最长断帧不到 {@link #SURROUND_STALL_MS}）。 */
        STEADY,
        /** 关座舱时环视那一条：环视断了（{@link Entry#ms} 是断了多久；我们中途救了它的，量到救那一刻）。 */
        STALLED
    }

    /** 一步。数都是毫秒；-1 = 没有 / 不知道。 */
    public static final class Entry {
        /** 结束的时刻（墙上时钟）。 */
        public final long at;
        public final String key;
        public final String cameraId;
        public final Move move;
        public final Result result;
        /** 出画面用了多久、到点前等了多久、关了多久、出事前做了多久。 */
        public final long ms;
        /** 从这一步开始到会话配好；-1 = 这一步里没配好（关的那一步没有）。 */
        public final long configuredMs;
        /** 出事的原因记号（{@code DEVICE_ERROR:4}、{@code DISCONNECTED}）；没出事是 null。 */
        public final String error;
        /** 配好以来相机回了什么（{@link SessionCounts.Kind} 的名字）；没有是 null。 */
        public final String counts;
        public final long starts;
        public final long results;
        public final long failures;
        public final long lost;
        /** 重开：旧设备关了多久（这一步里关完的）。 */
        public final long oldCloseMs;
        /** 哨兵模式原值（0 关、1 开、2 布防）；-1 读不到。 */
        public final int sentry;
        /** 主界面在前：1 / 0；-1 不知道。 */
        public final int mainForeground;
        /** 我们进程的重要性（缓存的）；-1 不知道。 */
        public final int importance;
        /** 离我们上一次关完这一台多久、那一次关了多久。 */
        public final long sinceCloseMs;
        public final long closeMs;
        /** 这一路上一次被别的程序拿着：拿了多久、放开多久了。 */
        public final long heldMs;
        public final long releasedAgoMs;
        /** 相机服务对这一路最后一句是多久以前；是不是我们引起的（1 / 0 / -1）。 */
        public final long wordAgoMs;
        public final int wordOurs;
        /** 点火、主驾门、屏幕电源事件上一次变化是多久以前。 */
        public final long sinceIgnitionMs;
        public final long sinceDoorMs;
        public final long sinceScreenMs;
        /** 座舱这一步开始时环视怎样：{@code STREAMING}、{@code OPEN}（开着没画面）、{@code CLOSED}；环视自己那一步、没有环视是 null。 */
        public final String surround;
        /** 这一步里环视最长断帧、会话动没动（1 / 0 / -1）：关座舱那一步才量。 */
        public final long surroundGapMs;
        public final int surroundChanged;
        /** 关的是哪一种（{@link CloseKind}）；计划里的关、别的动作是 null。 */
        public final CloseKind closeKind;
        /**
         * 环视那一台是车机重新初始化后第几台（2.11.5 第 D1 项）：环视自己开的那一步是这一步开的那一台，座舱的一步、关座舱时环视那一条是
         * 那时开着的环视；-1 = 不知道。{@link #lineageReset} 是那一台开的时候上一次重置（{@code BOOT}、{@code TAKEN}、{@code CAME_BACK}），
         * null = 从记录开始数的（第几台不可信）。
         */
        public final int lineage;
        public final String lineageReset;
        /** 关座舱时环视那一条：关的几路（{@code left,back}）、为什么关（{@code DARK_RELEASE}，丢了的写丢的那一下）。 */
        public final String closedKeys;
        public final String closeWhy;
        /** 关座舱时环视那一条：环视最后一帧比关完晚多少毫秒（负数 = 关完之前就停了，量的是关完 30 秒那一刻）；{@link Long#MIN_VALUE} = 不知道。 */
        public final long lastResultMs;
        /** 关座舱时环视那一条：关完 1 秒那一次看，关完以后环视出没出过画面（1 / 0 / -1）。 */
        public final int deliveredAfter;
        /** 关座舱时环视那一条：那 30 秒里我们自己动了环视（{@code RESCUE}、{@code CLOSE}…），量到那一刻（开始关以后多少毫秒）；没有是 null / -1。 */
        public final String ours;
        public final long oursAtMs;
        /** 点火此刻的样子（{@code ACC}、{@code ON}、{@code DRIVING}，认不出的写十六进制）；读不到是 null。 */
        public final String ignition;

        public Entry(Builder b) {
            this.at = b.at;
            this.key = b.key;
            this.cameraId = b.cameraId;
            this.move = b.move;
            this.result = b.result;
            this.ms = b.ms;
            this.configuredMs = b.configuredMs;
            this.error = b.error;
            this.counts = b.counts;
            this.starts = b.starts;
            this.results = b.results;
            this.failures = b.failures;
            this.lost = b.lost;
            this.oldCloseMs = b.oldCloseMs;
            this.sentry = b.sentry;
            this.mainForeground = b.mainForeground;
            this.importance = b.importance;
            this.sinceCloseMs = b.sinceCloseMs;
            this.closeMs = b.closeMs;
            this.heldMs = b.heldMs;
            this.releasedAgoMs = b.releasedAgoMs;
            this.wordAgoMs = b.wordAgoMs;
            this.wordOurs = b.wordOurs;
            this.sinceIgnitionMs = b.sinceIgnitionMs;
            this.sinceDoorMs = b.sinceDoorMs;
            this.sinceScreenMs = b.sinceScreenMs;
            this.surround = b.surround;
            this.surroundGapMs = b.surroundGapMs;
            this.surroundChanged = b.surroundChanged;
            this.closeKind = b.closeKind;
            this.lineage = b.lineage;
            this.lineageReset = b.lineageReset;
            this.closedKeys = b.closedKeys;
            this.closeWhy = b.closeWhy;
            this.lastResultMs = b.lastResultMs;
            this.deliveredAfter = b.deliveredAfter;
            this.ours = b.ours;
            this.oursAtMs = b.oursAtMs;
            this.ignition = b.ignition;
        }

        /**
         * 配好以来相机回了什么算哪一种（{@link SessionCounts.Kind} 的名字）：照存下的数按今天的规矩重算（{@link SessionCounts#classify}，
         * 2.11.5 第 D0 项 —— 2.11.4 存下的「我们的输出接不住」有几条其实是 HAL 一帧没开始），存下的数不全的照存下的名字；没有是 null。
         */
        public String kind() {
            if (counts == null || "NOT_CONFIGURED".equals(counts)) {
                return counts;
            }
            if (starts < 0 || results < 0 || failures < 0 || lost < 0) {
                return counts;
            }
            return SessionCounts.classify(true, starts, results, failures, lost).name();
        }

        /** 是不是开设备的那一种（开、重开）。 */
        public boolean opens() {
            return move == Move.OPEN || move == Move.REOPEN;
        }

        /** 一行：{@code v1 t=… k=back c=1 m=OPEN r=NO_FRAMES ms=15000 …}，只写有的那几项。 */
        public String encode() {
            StringBuilder sb = new StringBuilder(VERSION);
            put(sb, "t", at);
            put(sb, "k", key);
            put(sb, "c", cameraId);
            put(sb, "m", move == null ? null : move.name());
            put(sb, "r", result == null ? null : result.name());
            put(sb, "ms", ms);
            put(sb, "cfg", configuredMs);
            put(sb, "err", error);
            put(sb, "cnt", counts);
            if (counts != null) {
                put(sb, "st", starts);
                put(sb, "res", results);
                put(sb, "fail", failures);
                put(sb, "lost", lost);
            }
            put(sb, "oc", oldCloseMs);
            put(sb, "sen", sentry);
            put(sb, "fg", mainForeground);
            put(sb, "imp", importance);
            put(sb, "lc", sinceCloseMs);
            put(sb, "lcd", closeMs);
            put(sb, "hu", heldMs);
            put(sb, "hua", releasedAgoMs);
            put(sb, "w", wordAgoMs);
            put(sb, "wo", wordOurs);
            put(sb, "ig", sinceIgnitionMs);
            put(sb, "dr", sinceDoorMs);
            put(sb, "sp", sinceScreenMs);
            put(sb, "sur", surround);
            put(sb, "sg", surroundGapMs);
            put(sb, "sc", surroundChanged);
            put(sb, "ck", closeKind == null ? null : closeKind.name());
            put(sb, "sl", lineage);
            put(sb, "slr", lineageReset);
            put(sb, "cc", closedKeys);
            put(sb, "cw", closeWhy);
            if (lastResultMs != Long.MIN_VALUE) {
                sb.append(" lr=").append(lastResultMs);
            }
            put(sb, "a1", deliveredAfter);
            put(sb, "our", ours);
            put(sb, "ourms", oursAtMs);
            put(sb, "ign", ignition);
            return sb.toString();
        }

        private static void put(StringBuilder sb, String name, long value) {
            if (value != -1) {
                sb.append(' ').append(name).append('=').append(value);
            }
        }

        private static void put(StringBuilder sb, String name, String value) {
            if (value != null && !value.isEmpty()) {
                sb.append(' ').append(name).append('=').append(token(value));
            }
        }
    }

    /** 拼一条：不填的都是「没有」。 */
    public static final class Builder {
        long at = -1;
        String key;
        String cameraId;
        Move move;
        Result result;
        long ms = -1;
        long configuredMs = -1;
        String error;
        String counts;
        long starts = -1;
        long results = -1;
        long failures = -1;
        long lost = -1;
        long oldCloseMs = -1;
        int sentry = -1;
        int mainForeground = -1;
        int importance = -1;
        long sinceCloseMs = -1;
        long closeMs = -1;
        long heldMs = -1;
        long releasedAgoMs = -1;
        long wordAgoMs = -1;
        int wordOurs = -1;
        long sinceIgnitionMs = -1;
        long sinceDoorMs = -1;
        long sinceScreenMs = -1;
        String surround;
        long surroundGapMs = -1;
        int surroundChanged = -1;
        CloseKind closeKind;
        int lineage = -1;
        String lineageReset;
        String closedKeys;
        String closeWhy;
        long lastResultMs = Long.MIN_VALUE;
        int deliveredAfter = -1;
        String ours;
        long oursAtMs = -1;
        String ignition;

        public Builder step(long at, String key, String cameraId, Move move, Result result, long ms) {
            this.at = at;
            this.key = key;
            this.cameraId = cameraId;
            this.move = move;
            this.result = result;
            this.ms = ms;
            return this;
        }

        public Builder configured(long configuredMs) {
            this.configuredMs = configuredMs;
            return this;
        }

        public Builder error(String error) {
            this.error = error;
            return this;
        }

        public Builder counts(SessionCounts.Snapshot c) {
            if (c != null) {
                counts = c.kind().name();
                starts = c.starts;
                results = c.results;
                failures = c.failures;
                lost = c.lostTotal();
            }
            return this;
        }

        public Builder oldClose(long ms) {
            this.oldCloseMs = ms;
            return this;
        }

        public Builder vehicle(Integer sentry, long sinceIgnitionMs, long sinceDoorMs, long sinceScreenMs,
                               int importance) {
            this.sentry = sentry == null ? -1 : sentry;
            this.sinceIgnitionMs = sinceIgnitionMs;
            this.sinceDoorMs = sinceDoorMs;
            this.sinceScreenMs = sinceScreenMs;
            this.importance = importance;
            return this;
        }

        public Builder main(boolean foreground) {
            this.mainForeground = foreground ? 1 : 0;
            return this;
        }

        public Builder lastClose(long sinceCloseMs, long closeMs) {
            this.sinceCloseMs = sinceCloseMs;
            this.closeMs = closeMs;
            return this;
        }

        public Builder released(long heldMs, long releasedAgoMs) {
            this.heldMs = heldMs;
            this.releasedAgoMs = releasedAgoMs;
            return this;
        }

        public Builder word(long agoMs, Boolean ours) {
            this.wordAgoMs = agoMs;
            this.wordOurs = ours == null ? -1 : ours ? 1 : 0;
            return this;
        }

        public Builder surround(String state, long gapMs, Boolean changed) {
            this.surround = state;
            this.surroundGapMs = gapMs;
            this.surroundChanged = changed == null ? -1 : changed ? 1 : 0;
            return this;
        }

        public Builder closeKind(CloseKind kind) {
            this.closeKind = kind;
            return this;
        }

        /** 环视那一台是第几台（0 及以下 = 不知道，不写）、那一台开的时候上一次重置（null = 从记录开始数的）。 */
        public Builder lineage(int ordinal, String reset) {
            this.lineage = ordinal > 0 ? ordinal : -1;
            this.lineageReset = ordinal > 0 ? reset : null;
            return this;
        }

        /**
         * 关座舱时环视那一条（{@link Move#COUPLE}）：关的几路、为什么关、环视最后一帧比关完晚多少（{@link Long#MIN_VALUE} 不知道）、
         * 关完 1 秒那一次看到的（1 / 0 / -1）、我们中途动了环视的那一步和它的时刻（没有是 null / -1）。
         */
        public Builder coupling(List<String> closed, String why, long lastResultMs, int deliveredAfter, String ours,
                                long oursAtMs) {
            this.closedKeys = closed == null || closed.isEmpty() ? null : String.join(",", closed);
            this.closeWhy = why;
            this.lastResultMs = lastResultMs;
            this.deliveredAfter = deliveredAfter;
            this.ours = ours;
            this.oursAtMs = ours == null ? -1 : oursAtMs;
            return this;
        }

        public Builder ignition(String ignition) {
            this.ignition = ignition;
            return this;
        }

        public Entry build() {
            return new Entry(this);
        }
    }

    /** 读一行；读不懂（不是这一版、少了必有的几项）是 null。 */
    public static Entry parse(String line) {
        if (line == null) {
            return null;
        }
        String[] parts = line.trim().split(" ");
        if (parts.length < 2 || !VERSION.equals(parts[0])) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) {
                values.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
            }
        }
        Builder b = new Builder();
        try {
            b.at = number(values, "t");
            b.key = values.get("k");
            b.cameraId = values.get("c");
            String move = values.get("m");
            String result = values.get("r");
            if (b.key == null || move == null || result == null) {
                return null;
            }
            b.move = Move.valueOf(move);
            b.result = Result.valueOf(result);
            b.ms = number(values, "ms");
            b.configuredMs = number(values, "cfg");
            b.error = values.get("err");
            b.counts = values.get("cnt");
            b.starts = number(values, "st");
            b.results = number(values, "res");
            b.failures = number(values, "fail");
            b.lost = number(values, "lost");
            b.oldCloseMs = number(values, "oc");
            b.sentry = (int) number(values, "sen");
            b.mainForeground = (int) number(values, "fg");
            b.importance = (int) number(values, "imp");
            b.sinceCloseMs = number(values, "lc");
            b.closeMs = number(values, "lcd");
            b.heldMs = number(values, "hu");
            b.releasedAgoMs = number(values, "hua");
            b.wordAgoMs = number(values, "w");
            b.wordOurs = (int) number(values, "wo");
            b.sinceIgnitionMs = number(values, "ig");
            b.sinceDoorMs = number(values, "dr");
            b.sinceScreenMs = number(values, "sp");
            b.surround = values.get("sur");
            b.surroundGapMs = number(values, "sg");
            b.surroundChanged = (int) number(values, "sc");
            String closeKind = values.get("ck");
            b.closeKind = closeKind == null ? null : CloseKind.valueOf(closeKind);
            b.lineage = (int) number(values, "sl");
            b.lineageReset = values.get("slr");
            b.closedKeys = values.get("cc");
            b.closeWhy = values.get("cw");
            String lastResult = values.get("lr");
            b.lastResultMs = lastResult == null ? Long.MIN_VALUE : Long.parseLong(lastResult);
            b.deliveredAfter = (int) number(values, "a1");
            b.ours = values.get("our");
            b.oursAtMs = number(values, "ourms");
            b.ignition = values.get("ign");
        } catch (IllegalArgumentException e) {
            return null;   // 名字认不出（以后的版本加了新的动作 / 结果）、数坏了：整行不要
        }
        return b.build();
    }

    /** 读几行：读不懂的跳过，只留最后 {@link #MAX} 条，按记下的先后。 */
    public static List<Entry> parseAll(List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        for (String line : lines) {
            Entry e = parse(line);
            if (e != null) {
                entries.add(e);
            }
        }
        return entries.size() <= MAX ? entries : new ArrayList<>(entries.subList(entries.size() - MAX, entries.size()));
    }

    // ================================================================= 分组数

    /** 一组：几次、出画面、没画面、出错；关的那几次：几次、用时中位数、最长、超过 1 秒的几次。 */
    public static final class Tally {
        public int count;
        public int framed;
        public int noFrames;
        public int errors;
        public int closes;
        public int slowCloses;
        public int closeTimedOut;
        /** 关的用时（关完了的那几次）中位数、最长；没有是 -1。 */
        public long closeMedianMs = -1;
        public long closeMaxMs = -1;
    }

    /** 数一组。 */
    public static Tally tally(List<Entry> entries) {
        Tally t = new Tally();
        List<Long> closeMs = new ArrayList<>();
        for (Entry e : entries) {
            t.count++;
            switch (e.result) {
                case FRAMED:
                    t.framed++;
                    break;
                case NO_FRAMES:
                    t.noFrames++;
                    break;
                case ERROR:
                    t.errors++;
                    break;
                case CLOSED:
                    t.closes++;
                    closeMs.add(e.ms);
                    if (e.ms > SLOW_CLOSE_MS) {
                        t.slowCloses++;
                    }
                    break;
                case CLOSE_TIMED_OUT:
                    t.closes++;
                    t.closeTimedOut++;
                    t.slowCloses++;
                    break;
                default:
                    break;
            }
        }
        if (!closeMs.isEmpty()) {
            Collections.sort(closeMs);
            t.closeMaxMs = closeMs.get(closeMs.size() - 1);
            t.closeMedianMs = closeMs.get((closeMs.size() - 1) / 2);
        }
        return t;
    }

    /** 按 {@code by} 分组（组的次序按第一次出现的先后；{@code by} 返回 null 的不算进任何一组）。 */
    public static Map<String, List<Entry>> groupBy(List<Entry> entries, Function<Entry, String> by) {
        Map<String, List<Entry>> groups = new LinkedHashMap<>();
        for (Entry e : entries) {
            String group = by.apply(e);
            if (group == null) {
                continue;
            }
            List<Entry> list = groups.get(group);
            if (list == null) {
                list = new ArrayList<>();
                groups.put(group, list);
            }
            list.add(e);
        }
        return groups;
    }

    /** 相机、动作：{@code back OPEN}；丢了之后那一次关、救援重开时关旧设备单算一组：{@code front CLOSE:REOPEN}。 */
    public static String laneMove(Entry e) {
        return e.key + " " + e.move.name() + (e.closeKind == null ? "" : ":" + e.closeKind.name());
    }

    /** 一步（开、救、改、关）；关座舱时环视那一条（{@link Move#COUPLE}）不是。 */
    public static boolean isStep(Entry e) {
        return e.move != Move.COUPLE;
    }

    /**
     * 环视那一台是不是车机重新初始化后的第一台（2.11.5 第 D1 项）：{@code lineage:first}、{@code lineage:later}；
     * 第几台是从记录开始数的（没见过重置）{@code lineage:?}；这一条没有环视那一台（环视关着、2.11.5 之前记的）是 null，不算进哪一组。
     */
    public static String lineageBucket(Entry e) {
        if (e.lineage <= 0) {
            return null;
        }
        if (e.lineageReset == null) {
            return "lineage:?";
        }
        return e.lineage == 1 ? "lineage:first" : "lineage:later";
    }

    /** 哨兵模式：{@code sentry:0}、{@code sentry:1}、{@code sentry:2}，读不到 {@code sentry:?}。 */
    public static String sentryBucket(Entry e) {
        return "sentry:" + (e.sentry < 0 ? "?" : String.valueOf(e.sentry));
    }

    /** 主界面在前：{@code main:yes}、{@code main:no}、{@code main:?}。 */
    public static String mainBucket(Entry e) {
        return "main:" + (e.mainForeground < 0 ? "?" : e.mainForeground == 1 ? "yes" : "no");
    }

    /**
     * 离我们上一次关完这一台多久：{@code close:<3}、{@code close:3-6}、{@code close:6-30}、{@code close:>30}（秒），
     * 这一次进程里没关过 {@code close:none}。
     */
    public static String sinceCloseBucket(Entry e) {
        // 2.11.5 起「我们上一次关它」跨进程算（同一次开机里，CameraHistory）：close:none 是没有关过它的记录
        long ms = e.sinceCloseMs;
        if (ms < 0) {
            return "close:none";
        }
        if (ms < 3_000L) {
            return "close:<3";
        }
        if (ms < 6_000L) {
            return "close:3-6";
        }
        if (ms <= 30_000L) {
            return "close:6-30";
        }
        return "close:>30";
    }

    /**
     * 是不是车机放开它之后的第一下：上一次被别的程序拿着、放开以后到这一步之间我们没关过它
     * （放开得比我们上一次关完还晚）—— {@code release:yes}；否则 {@code release:no}。
     */
    public static String releaseBucket(Entry e) {
        boolean after = e.releasedAgoMs >= 0 && (e.sinceCloseMs < 0 || e.releasedAgoMs <= e.sinceCloseMs);
        return after ? "release:yes" : "release:no";
    }

    /** 报告里的次序：环视、后座舱、前座舱、别的；同一路按动作的次序。 */
    public static int laneRank(Entry e) {
        if (CameraSlots.KEY_SURROUND.equals(e.key)) {
            return 0;
        }
        if (CameraSlots.KEY_CABIN_REAR.equals(e.key)) {
            return 1;
        }
        return CameraSlots.KEY_CABIN_FRONT.equals(e.key) ? 2 : 3;
    }

    /** 关座舱时环视那一组的比较（R3：关座舱时先短暂关环视，备选方案之一，只记录）。 */
    public static final class CloseComparison {
        /** 这一组座舱关的那几步。 */
        public final List<Entry> closes = new ArrayList<>();
        /** 每一次关之后这一路的下一次开（开、重开）：一共几次、没画面几次。 */
        public int nextOpens;
        public int nextNoFrames;
    }

    /**
     * 座舱的每一次关按关的时候环视怎样分组（{@link Entry#surround}：在出画面、开着没画面、关着），各组的关，加上关完之后那一路的下一次开：
     * 「环视在出画面时关座舱，关得更慢、之后更常没画面」才是备选方案「关座舱时先短暂关环视」的依据（项目所有者 2026-10-14，R3）。
     * 只看座舱（环视之外）、按记下的先后。
     */
    public static Map<String, CloseComparison> cabinClosesBySurround(List<Entry> entries) {
        Map<String, CloseComparison> groups = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            // 只比计划里的关：丢了之后那一次关、救援重开时关旧设备（2.11.5 起也记）不是这个方案会动的那一种
            if (e.move != Move.CLOSE || e.closeKind != null || CameraSlots.KEY_SURROUND.equals(e.key)) {
                continue;
            }
            String group = e.surround == null ? "?" : e.surround;
            CloseComparison c = groups.get(group);
            if (c == null) {
                c = new CloseComparison();
                groups.put(group, c);
            }
            c.closes.add(e);
            for (int j = i + 1; j < entries.size(); j++) {
                Entry next = entries.get(j);
                if (!next.key.equals(e.key)) {
                    continue;
                }
                if (next.move == Move.CLOSE) {
                    break;   // 又关了一次，中间没开：这一次关没有「下一次开」
                }
                if (next.opens()) {
                    c.nextOpens++;
                    if (next.result == Result.NO_FRAMES) {
                        c.nextNoFrames++;
                    }
                    break;
                }
            }
        }
        return groups;
    }

    /** 关座舱之后环视那一组（{@link #surroundAfterCloses}）：几次关座舱、环视断了几次、断得最长的那一次多久。 */
    public static final class SurroundAfterClose {
        public int closes;
        public int stalled;
        /** 断了的那几次里最长的；没断过是 -1。 */
        public long longestMs = -1;
    }

    /**
     * 关座舱时环视那几条（{@link Move#COUPLE}）数一组：几次、环视关完 30 秒里断了几次（{@link Result#STALLED}）、最长的那一次多久
     * （项目所有者 2026-10-15 批准，2.11.5 第 D3 项：备选方案「关座舱时先短暂关环视」要比的那一边 —— 以前只数座舱下一次开，看不见环视）。
     * 别的条跳过。
     */
    public static SurroundAfterClose surroundAfterCloses(List<Entry> entries) {
        SurroundAfterClose t = new SurroundAfterClose();
        for (Entry e : entries) {
            if (e.move != Move.COUPLE) {
                continue;
            }
            t.closes++;
            if (e.result == Result.STALLED) {
                t.stalled++;
                t.longestMs = Math.max(t.longestMs, e.ms);
            }
        }
        return t;
    }

    /** 值里不许有空格和等号（读的时候按它们切）：换成下划线。 */
    static String token(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(c <= ' ' || c == '=' ? '_' : c);
        }
        return sb.toString();
    }

    private static long number(Map<String, String> values, String name) {
        String v = values.get(name);
        return v == null ? -1 : Long.parseLong(v);
    }
}
