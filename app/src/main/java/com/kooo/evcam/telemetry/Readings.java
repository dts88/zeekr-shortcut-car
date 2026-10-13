package com.kooo.evcam.telemetry;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 全部信号此刻的（归一后的）读数：一份不可变快照。没数据的信号不在表里。
 */
public final class Readings {

    private static final Readings EMPTY = new Readings(new EnumMap<>(Signal.class), 0);

    private final Map<Signal, Object> values;
    /** 每发布一份 +1。 */
    public final long version;

    Readings(Map<Signal, Object> values, long version) {
        this.values = Collections.unmodifiableMap(new EnumMap<>(values));
        this.version = version;
    }

    public static Readings empty() {
        return EMPTY;
    }

    /** 归一后的值（Boolean / Integer / String / Float），没数据为 null。 */
    public Object get(Signal signal) {
        return values.get(signal);
    }

    public Boolean bool(Signal signal) {
        Object v = values.get(signal);
        return v instanceof Boolean ? (Boolean) v : null;
    }

    public Float number(Signal signal) {
        Object v = values.get(signal);
        return v instanceof Float ? (Float) v : null;
    }

    public Integer code(Signal signal) {
        Object v = values.get(signal);
        return v instanceof Integer ? (Integer) v : null;
    }

    public String text(Signal signal) {
        Object v = values.get(signal);
        return v instanceof String ? (String) v : null;
    }

    /** 只留能用的信号（{@link Signal#usable()}；非开发者的信息条用它，猜的东西不画进录像）。版本号不变。 */
    public Readings usableOnly() {
        Map<Signal, Object> kept = new EnumMap<>(Signal.class);
        for (Map.Entry<Signal, Object> e : values.entrySet()) {
            if (e.getKey().usable()) {
                kept.put(e.getKey(), e.getValue());
            }
        }
        return new Readings(kept, version);
    }

    /**
     * 信息条那份快照用的读数：能用的，加上勾了的（没验证的只有开发者勾得了，见 {@link InfoBar#selection}）。
     * 开发者没勾的没验证信号照样不进 —— 不然车厢图会拿没验证的副驾安全带把坐了人的副驾画成红的。版本号不变。
     */
    public Readings usableOr(java.util.Set<String> ticked) {
        Map<Signal, Object> kept = new EnumMap<>(Signal.class);
        for (Map.Entry<Signal, Object> e : values.entrySet()) {
            if (e.getKey().usable() || ticked.contains(e.getKey().name())) {
                kept.put(e.getKey(), e.getValue());
            }
        }
        return new Readings(kept, version);
    }

    /** 有数据的信号个数。 */
    public int knownCount() {
        return values.size();
    }

    /** 日志用：每个信号一个短记号。 */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        for (Signal s : Signal.values()) {
            Object v = values.get(s);
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(s.name().toLowerCase(java.util.Locale.US)).append('=');
            if (v == null) {
                sb.append('?');
            } else if (v instanceof Boolean) {
                sb.append((Boolean) v ? '1' : '0');
            } else if (v instanceof Float) {
                sb.append(String.format(java.util.Locale.US, "%.2f", (Float) v));
            } else if (v instanceof Integer) {
                sb.append(Integer.toHexString((Integer) v));
            } else {
                sb.append(v);
            }
        }
        return sb.toString();
    }
}
