package com.kooo.evcam.settings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 一个「枚举型设置项」的完整定义：键名、合法取值、默认值、每个取值的显示名。
 *
 * <p>存在的理由是一个真实故障：设置页显示「四宫格」，录出来却是原始长条。根因不是
 * 界面怎么画，而是<b>同一个设置被声明了三遍</b> —— {@code AppConfig} 的 getter 里有一份
 * 默认值，{@code SettingsFragment} 里有一份选项数组，布局 XML 里还有一份显示文字，
 * 三处靠人工保持一致。任何一处对不上，界面就会显示一个程序不会遵守的值。</p>
 *
 * <p>声明在这里之后，选项列表、默认值、启动自检、显示名全部从同一份定义派生，
 * 「声明了两遍」在结构上不再可能发生。</p>
 *
 * <p>刻意不依赖任何 Android API —— 这样它能直接跑 JVM 单元测试。本机没有 JDK，
 * 编译只能靠 CI，能进测试的部分越多越好。</p>
 */
public final class SettingSpec {

    /** 一个取值及其显示名。 */
    public static final class Entry {
        public final String value;
        public final String displayName;

        public Entry(String value, String displayName) {
            this.value = value;
            this.displayName = displayName;
        }
    }

    /** SharedPreferences 键名。 */
    public final String key;
    /** 这个设置项的名字，用于日志与诊断报告。 */
    public final String label;
    /** 默认值，必定是 {@link #entries} 中某一项的 value。 */
    public final String defaultValue;

    private final List<Entry> entries;

    private SettingSpec(String key, String label, String defaultValue, List<Entry> entries) {
        this.key = key;
        this.label = label;
        this.defaultValue = defaultValue;
        this.entries = Collections.unmodifiableList(entries);
    }

    /** 便捷构造：value 与显示名成对写在一起，避免两个数组错位。 */
    public static Entry entry(String value, String displayName) {
        return new Entry(value, displayName);
    }

    /**
     * 定义一个枚举型设置项。
     *
     * @throws IllegalArgumentException 默认值不在取值列表里 —— 这属于写错了代码，
     *                                  应该当场炸掉而不是留到运行时变成一个诡异现象
     */
    public static SettingSpec of(String key, String label, String defaultValue, Entry... entries) {
        if (entries == null || entries.length == 0) {
            throw new IllegalArgumentException(key + ": 至少要有一个取值");
        }
        List<Entry> list = new ArrayList<>();
        boolean defaultIsValid = false;
        for (Entry e : entries) {
            if (e == null || e.value == null) {
                throw new IllegalArgumentException(key + ": 取值不能为 null");
            }
            for (Entry seen : list) {
                if (seen.value.equals(e.value)) {
                    throw new IllegalArgumentException(key + ": 取值重复 " + e.value);
                }
            }
            list.add(e);
            if (e.value.equals(defaultValue)) {
                defaultIsValid = true;
            }
        }
        if (!defaultIsValid) {
            throw new IllegalArgumentException(
                    key + ": 默认值 " + defaultValue + " 不在取值列表里");
        }
        return new SettingSpec(key, label, defaultValue, list);
    }

    /** 全部合法取值，顺序即界面上的显示顺序。 */
    public String[] values() {
        String[] out = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            out[i] = entries.get(i).value;
        }
        return out;
    }

    /** 全部显示名，与 {@link #values()} 一一对应。 */
    public String[] displayNames() {
        String[] out = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            out[i] = entries.get(i).displayName;
        }
        return out;
    }

    public int size() {
        return entries.size();
    }

    public boolean isValid(String value) {
        for (Entry e : entries) {
            if (e.value.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 取值 -&gt; 下标。
     *
     * <p>找不到返回 -1，<b>不</b>返回 0 —— 「没匹配上」和「匹配到第一项」必须能区分开。
     * 原来的代码把两者都当成 0，界面于是默默显示第一项，而配置里还是那个对不上的值。</p>
     */
    public int indexOf(String value) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).value.equals(value)) {
                return i;
            }
        }
        return -1;
    }

    /** 下标 -&gt; 取值；越界时返回默认值。 */
    public String valueAt(int index) {
        if (index < 0 || index >= entries.size()) {
            return defaultValue;
        }
        return entries.get(index).value;
    }

    /** 某个取值的显示名；不认识就把原值返回，至少不骗人。 */
    public String displayNameOf(String value) {
        for (Entry e : entries) {
            if (e.value.equals(value)) {
                return e.displayName;
            }
        }
        return String.valueOf(value);
    }

    /**
     * 读取时兜底：合法就原样返回，不合法一律回落到默认值。
     *
     * <p>getter 都经过这里，所以<b>不合法的值根本不可能被读出来</b> ——
     * 启动自检只是顺手把坏值从存储里清掉，不是唯一的防线。</p>
     */
    public String sanitize(String stored) {
        return isValid(stored) ? stored : defaultValue;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "SettingSpec[%s, %d 项, 默认 %s]",
                key, entries.size(), defaultValue);
    }
}
