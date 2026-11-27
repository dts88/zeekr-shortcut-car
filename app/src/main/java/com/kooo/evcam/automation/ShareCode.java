/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 分享码 v1（验证期，可以随时改、不留兼容）：一条自动化 ⇄ 一串码。码就是规则本身：设置一样码就一样；不带名字、开关、id，
 * 地点只记编号 1–9（地点的名称、坐标、半径都不进码）。zeekr.autos 照同一份规格，两边都过测试资源里的
 * {@code sharecode/vectors.json}。
 *
 * <pre>
 * 版本（1）＋ 当（1 个记号）＋ 而且（0 到几个）＋ 就（C 一个）＋ 恢复（R，可有可无）＋ 长度位 ＋ 两位校验
 * 1A1G0C580963 = 1 | A1 到达地点 1 | G0 挡位是 P | C580 尾门开启上限 80 | 9 正文 9 个字符 | 63 MOD 97-10
 * </pre>
 *
 * <p>码本身是连着的一整串；给人看时每 4 个一组（{@link #display}），输入时空格、连字符都忽略（{@link #normalize}）。</p>
 */
public final class ShareCode {

    /** 不能分享、不能添加的几种原因（用例里的错误种类）。 */
    public enum Problem {
        /** 有不用的字符，或者太短。 */
        FORMAT,
        /** 长度位或两位校验对不上：多半是打错了。 */
        CHECKSUM,
        /** 不认识的版本：要更新应用。 */
        VERSION,
        /** 拆不成「当、而且、就、恢复」。 */
        SYNTAX,
        /** 数超出范围，或者设置用在了它不能用的地方。 */
        VALUE,
        /** 地点：超过 9 个、编号不连着、「在…」「不在…」不止一个。 */
        PLACES,
        /** 跟存规则的要求一样：至少一个动作；关掉安全功能的，当必须会结束。 */
        RULE,
        /** 不是规范的写法（同一条设置只有一个码）。 */
        NONCANONICAL
    }

    /** 不能分享、不能添加。 */
    public static final class Rejected extends Exception {

        public final Problem problem;

        Rejected(Problem problem, String detail) {
            super(problem.name().toLowerCase(Locale.ROOT) + (detail.isEmpty() ? "" : ": " + detail));
            this.problem = problem;
        }
    }

    static final char VERSION = '1';
    /** 码里只用数字和这 13 个大写字母（不用 O、I，免得跟 0、1 混）。 */
    public static final String LETTERS = "ACEGLMPRTUVWX";
    /** 显示时几个一组（项目所有者 2026-11-27）。 */
    public static final int GROUP = 4;
    /** 最短的码：1 E C01，再加长度位、两位校验。打满这么多才验（输入框）。 */
    public static final int MIN_LENGTH = 8;
    /** 一条能分享的规则最多用到几个地点（编号只有一位）。 */
    public static final int MAX_PLACES = 9;

    /** 设置编号（规格 3.1）：由分享码自己定，不跟着 {@link Setting} 的先后变。位置就是编号。 */
    static final Setting[] SETTINGS = {Setting.SENTRY, Setting.DRIVE_MODE, Setting.CIRCULATION, Setting.REAR_CLIMATE,
            Setting.HILL_DESCENT, Setting.TAILGATE_LIMIT, Setting.LOW_SPEED_BRAKING, Setting.FRONT_CLIMATE};
    /** 每一样的值：位置就是码里的那一位数。一段整数的（尾门开启上限）是 null，码里写两位。 */
    static final String[][] VALUES = {{"off", "on"}, {"comfort", "standard", "sport"}, {"auto", "outer", "inner"},
            {"off", "on"}, {"off", "on"}, null, {"off", "on"}, {"off", "on"}};
    static final String[] GEARS = {"P", "R", "N", "D"};

    private ShareCode() {
    }

    // ---------- 编码 ----------

    /**
     * 一条自动化 → 分享码。名字、开关、id 不进码；地点按规格第 6 节编号（当里的地点；在「在…」也在「不在…」的；
     * 只在「在…」的；只在「不在…」的）。
     *
     * @throws Rejected 地点超过 9 个、「在…」「不在…」不止一个（{@link Problem#PLACES}），或者规则本身不完整
     */
    public static String encode(Rule rule) throws Rejected {
        if (rule == null || rule.trigger == null) {
            throw new Rejected(Problem.RULE, "no trigger");
        }
        List<String> places = placesOf(rule);
        if (places.size() > MAX_PLACES) {
            throw new Rejected(Problem.PLACES, "more than " + MAX_PLACES + " places");
        }
        if (placeSets(rule, Condition.Kind.IN_PLACES).size() > 1
                || placeSets(rule, Condition.Kind.NOT_IN_PLACES).size() > 1) {
            throw new Rejected(Problem.PLACES, "more than one in / not-in condition");
        }
        String tail = tail(rule);
        Map<String, Integer> slot = numbering(rule, places);
        StringBuilder body = new StringBuilder().append(VERSION).append(trigger(rule.trigger, slot));
        TreeSet<String> conditions = new TreeSet<>(); // 一模一样的只留一个，按字符顺序排
        for (Condition c : rule.conditions) {
            conditions.add(condition(c, slot));
        }
        for (String c : conditions) {
            body.append(c);
        }
        body.append(tail);
        return body + checkDigits(body.toString());
    }

    /** 用到的地点，照出现的先后（当里的，再是条件里的），重复的只算一次。 */
    private static List<String> placesOf(Rule rule) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (rule.trigger.place != null) {
            seen.add(rule.trigger.place);
        }
        for (Condition c : rule.conditions) {
            seen.addAll(c.places);
        }
        return new ArrayList<>(seen);
    }

    /** 某一种地点条件里不同的地点集合；一模一样的算一个。 */
    private static Set<Set<String>> placeSets(Rule rule, Condition.Kind kind) {
        Set<Set<String>> sets = new HashSet<>();
        for (Condition c : rule.conditions) {
            if (c.kind == kind) {
                sets.add(new HashSet<>(c.places));
            }
        }
        return sets;
    }

    /** 地点编号（规格第 6 节）。同一类里谁先谁后码都一样，所以按出现的先后排（排序是稳定的）。 */
    private static Map<String, Integer> numbering(Rule rule, List<String> places) {
        String trigger = rule.trigger.place;
        Set<String> inside = new HashSet<>();
        Set<String> outside = new HashSet<>();
        for (Condition c : rule.conditions) {
            if (c.kind == Condition.Kind.IN_PLACES) {
                inside.addAll(c.places);
            } else if (c.kind == Condition.Kind.NOT_IN_PLACES) {
                outside.addAll(c.places);
            }
        }
        List<String> order = new ArrayList<>(places);
        Collections.sort(order, (a, b) -> Integer.compare(group(a, trigger, inside, outside),
                group(b, trigger, inside, outside)));
        Map<String, Integer> slot = new HashMap<>();
        for (int i = 0; i < order.size(); i++) {
            slot.put(order.get(i), i + 1);
        }
        return slot;
    }

    private static int group(String place, String trigger, Set<String> inside, Set<String> outside) {
        if (place.equals(trigger)) {
            return 0;
        }
        if (inside.contains(place)) {
            return outside.contains(place) ? 1 : 2;
        }
        return 3;
    }

    /** 「就」和「恢复」：同一项设置只留最后一个，按设置编号排；该恢复的写 R（规格 5.4）。 */
    private static String tail(Rule rule) throws Rejected {
        if (rule.changes.isEmpty()) {
            throw new Rejected(Problem.RULE, "no change");
        }
        TreeMap<Integer, Change> last = new TreeMap<>();
        for (Change c : rule.changes) {
            int no = number(c.setting);
            if (c.setting.readOnly()) {
                throw new Rejected(Problem.VALUE, c.setting.key + " is read-only");
            }
            last.put(no, c);
        }
        boolean ends = rule.trigger.endsWhileLit();
        if (!ends && rule.hasSafetyOff()) {
            throw new Rejected(Problem.RULE, "turning a safety function off needs a trigger that ends");
        }
        StringBuilder out = new StringBuilder("C");
        boolean safetyOff = false;
        boolean restorable = false;
        for (Map.Entry<Integer, Change> e : last.entrySet()) {
            Change c = e.getValue();
            out.append(e.getKey()).append(value(e.getKey(), c.value));
            safetyOff |= c.safetyOff();
            restorable |= c.setting.restorable();
        }
        if (ends && restorable && (rule.restore || safetyOff)) {
            out.append('R');
        }
        return out.toString();
    }

    private static String trigger(Trigger t, Map<String, Integer> slot) throws Rejected {
        switch (t.kind) {
            case GET_IN:
                return "E";
            case GEAR:
                return "G" + gear(t.gear);
            case STATE:
                return "V" + state(t.setting, t.value);
            case ARRIVE:
                return "A" + slot.get(t.place);
            case LEAVE:
                return "L" + slot.get(t.place);
            default:
                // 码里还没有的触发：不能分享（规格先加记号）
                throw new Rejected(Problem.VALUE, "trigger " + t.kind);
        }
    }

    private static String condition(Condition c, Map<String, Integer> slot) throws Rejected {
        switch (c.kind) {
            case GEAR_IS:
                return "G" + gear(c.gear);
            case STATE_IS:
                return "V" + state(c.setting, c.value);
            case BATTERY_AT_LEAST:
                return "M" + digits(c.number, 3);
            case BATTERY_BELOW:
                return "U" + digits(c.number, 3);
            case TIME_BETWEEN:
                return "T" + digits(c.from / 60, 2) + digits(c.from % 60, 2) + digits(c.to / 60, 2)
                        + digits(c.to % 60, 2);
            case WEEKDAYS: {
                StringBuilder out = new StringBuilder("W");
                for (int i = 0; i < 7; i++) {
                    if ((c.days >> i & 1) != 0) {
                        out.append(i + 1);
                    }
                }
                return out.toString();
            }
            case IN_PLACES:
            case NOT_IN_PLACES: {
                TreeSet<Integer> numbers = new TreeSet<>();
                for (String p : c.places) {
                    numbers.add(slot.get(p));
                }
                StringBuilder out = new StringBuilder(c.kind == Condition.Kind.IN_PLACES ? "P" : "X");
                for (int n : numbers) {
                    out.append(n);
                }
                return out.toString();
            }
            default:
                throw new Rejected(Problem.VALUE, "condition " + c.kind);
        }
    }

    private static int number(Setting setting) throws Rejected {
        for (int i = 0; i < SETTINGS.length; i++) {
            if (SETTINGS[i] == setting) {
                return i;
            }
        }
        throw new Rejected(Problem.VALUE, "setting " + setting);
    }

    private static String gear(String gear) throws Rejected {
        int i = indexOf(GEARS, gear);
        if (i < 0) {
            throw new Rejected(Problem.VALUE, "gear " + gear);
        }
        return String.valueOf(i);
    }

    /** 「变为」「是」：设置编号 + 值的那一位；一段整数的不行。 */
    private static String state(Setting setting, String value) throws Rejected {
        int no = number(setting);
        if (VALUES[no] == null) {
            throw new Rejected(Problem.VALUE, setting.key + " cannot be a state");
        }
        return no + value(no, value);
    }

    /** 动作的值：几种值里的第几种（一位），一段整数的写两位。 */
    private static String value(int no, String value) throws Rejected {
        String[] names = VALUES[no];
        if (names == null) {
            Integer n = SETTINGS[no].code(value);
            if (n == null) {
                throw new Rejected(Problem.VALUE, SETTINGS[no].key + "=" + value);
            }
            return digits(n, 2);
        }
        int i = indexOf(names, value);
        if (i < 0) {
            throw new Rejected(Problem.VALUE, SETTINGS[no].key + "=" + value);
        }
        return String.valueOf(i);
    }

    private static String digits(int n, int width) {
        StringBuilder s = new StringBuilder(String.valueOf(n));
        while (s.length() < width) {
            s.insert(0, '0');
        }
        return s.toString();
    }

    private static int indexOf(String[] names, String value) {
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(value)) {
                return i;
            }
        }
        return -1;
    }

    // ---------- 校验：长度位 + 两位 ISO 7064 MOD 97-10（规格第 7 节） ----------

    /** 跟在正文后面的三位：长度位（正文字符数的个位）+ 两位校验（算正文和长度位）。 */
    static String checkDigits(String body) {
        char length = (char) ('0' + body.length() % 10);
        int check = 98 - mod97(body + length + "00");
        return "" + length + digits(check, 2);
    }

    private static boolean checkOk(String body, String tail) {
        if (tail.length() != 3) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            if (!isDigit(tail.charAt(i))) {
                return false;
            }
        }
        return tail.charAt(0) == '0' + body.length() % 10 && mod97(body + tail) == 1;
    }

    /** 字母换成两位数（A=10 …… Z=35）、数字照旧，接成一个大整数，取 mod 97（逐位算，不用大数）。 */
    private static int mod97(String s) {
        int r = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            r = isDigit(c) ? (r * 10 + (c - '0')) % 97 : (r * 100 + (c - 'A' + 10)) % 97;
        }
        return r;
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    // ---------- 显示与输入 ----------

    /** 每 4 个一组（最后一组可以不满 4 个）。 */
    public static List<String> groups(String code) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < code.length(); i += GROUP) {
            out.add(code.substring(i, Math.min(code.length(), i + GROUP)));
        }
        return out;
    }

    /** 给人看的样子：从左起每 4 个一组，组间空一格。复制、比对用码本身（不带空格）。 */
    public static String display(String code) {
        return String.join(" ", groups(code));
    }

    /**
     * 整理手打的码：全角转半角（NFKC）、转大写，去掉空格、制表符、换行和连字符，O 当 0、I 当 1。
     * 要去掉的就是这几个字符（不用 {@code \s}：Java、JS、Python 各不一样；Java 正则的 {@code \v} 还不只一个字符）。
     */
    public static String normalize(String text) {
        if (text == null) {
            return "";
        }
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case ' ':
                case '\t':
                case '\n':
                case '\r':
                case '\f':
                case '\u000B':
                case '-':
                    break;
                case 'O':
                    out.append('0');
                    break;
                case 'I':
                    out.append('1');
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    // ---------- 解码与验证（规格第 9 节） ----------

    /**
     * 分享码（可以是手打的样子）→ 一条自动化。地点是 "1"、"2"……（{@link #places}），添加前换成用户选的地点
     * （{@link #adopt}）。解出来的没有 id、名字，开关是关的。
     *
     * @throws Rejected 不能添加的码；对用户只分「需要更新应用」（{@link Problem#VERSION}）和「分享码不对」（其余）
     */
    public static Rule decode(String text) throws Rejected {
        String code = normalize(text);
        if (code.length() < MIN_LENGTH) {
            throw new Rejected(Problem.FORMAT, "too short");
        }
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (!isDigit(c) && LETTERS.indexOf(c) < 0) {
                throw new Rejected(Problem.FORMAT, "character at " + i);
            }
        }
        String body = code.substring(0, code.length() - 3);
        if (!checkOk(body, code.substring(code.length() - 3))) {
            throw new Rejected(Problem.CHECKSUM, "");
        }
        if (!isDigit(body.charAt(0))) {
            throw new Rejected(Problem.SYNTAX, "no version");
        }
        if (body.charAt(0) != VERSION) {
            throw new Rejected(Problem.VERSION, String.valueOf(body.charAt(0)));
        }
        List<String[]> tokens = tokens(body);
        Set<Integer> used = new HashSet<>();
        Trigger trigger = parseTrigger(tokens.get(0), used);
        int i = 1;
        List<Condition> conditions = new ArrayList<>();
        while (i < tokens.size() && !"C".equals(tokens.get(i)[0])) {
            conditions.add(parseCondition(tokens.get(i), used));
            i++;
        }
        if (i >= tokens.size()) {
            throw new Rejected(Problem.SYNTAX, "no C");
        }
        List<Change> changes = parseChanges(tokens.get(i)[1]);
        i++;
        boolean restore = false;
        if (i < tokens.size() && "R".equals(tokens.get(i)[0]) && tokens.get(i)[1].isEmpty()) {
            restore = true;
            i++;
        }
        if (i != tokens.size()) {
            throw new Rejected(Problem.SYNTAX, "after C");
        }
        for (int n = 1; n <= used.size(); n++) {
            if (!used.contains(n)) {
                throw new Rejected(Problem.PLACES, "numbers " + new TreeSet<>(used));
            }
        }
        Rule rule = new Rule(null, null, false, trigger, conditions, changes, restore);
        if (placeSets(rule, Condition.Kind.IN_PLACES).size() > 1
                || placeSets(rule, Condition.Kind.NOT_IN_PLACES).size() > 1) {
            throw new Rejected(Problem.PLACES, "more than one in / not-in condition");
        }
        String again = encode(rule); // 规则不完整的在这里抛 RULE
        if (!again.equals(code)) {
            throw new Rejected(Problem.NONCANONICAL, "canonical form is " + again);
        }
        return rule;
    }

    /** 正文（版本位以后）拆成「一个字母 + 几位数字」的记号。 */
    private static List<String[]> tokens(String body) throws Rejected {
        List<String[]> out = new ArrayList<>();
        int pos = 1;
        while (pos < body.length()) {
            char letter = body.charAt(pos);
            if (letter < 'A' || letter > 'Z') {
                throw new Rejected(Problem.SYNTAX, "at " + pos);
            }
            int end = pos + 1;
            while (end < body.length() && isDigit(body.charAt(end))) {
                end++;
            }
            out.add(new String[]{String.valueOf(letter), body.substring(pos + 1, end)});
            pos = end;
        }
        if (out.isEmpty()) {
            throw new Rejected(Problem.SYNTAX, "empty");
        }
        return out;
    }

    private static Trigger parseTrigger(String[] token, Set<Integer> used) throws Rejected {
        String letter = token[0];
        String d = token[1];
        if ("E".equals(letter) && d.isEmpty()) {
            return Trigger.getIn();
        }
        if ("G".equals(letter) && d.length() == 1) {
            return Trigger.gear(gearOf(d));
        }
        if ("V".equals(letter) && d.length() == 2) {
            int s = settingOf(d.charAt(0));
            return Trigger.state(SETTINGS[s], stateValue(s, d.charAt(1)));
        }
        if (("A".equals(letter) || "L".equals(letter)) && d.length() == 1) {
            String place = slot(d.charAt(0), used);
            return "A".equals(letter) ? Trigger.arrive(place) : Trigger.leave(place);
        }
        throw new Rejected(Problem.SYNTAX, "trigger " + letter + d);
    }

    private static Condition parseCondition(String[] token, Set<Integer> used) throws Rejected {
        String letter = token[0];
        String d = token[1];
        switch (letter) {
            case "G":
                if (d.length() == 1) {
                    return Condition.gearIs(gearOf(d));
                }
                break;
            case "V":
                if (d.length() == 2) {
                    int s = settingOf(d.charAt(0));
                    return Condition.stateIs(SETTINGS[s], stateValue(s, d.charAt(1)));
                }
                break;
            case "M":
            case "U":
                if (d.length() == 3) {
                    int n = Integer.parseInt(d);
                    if (n > 100) {
                        throw new Rejected(Problem.VALUE, "battery " + n);
                    }
                    return "M".equals(letter) ? Condition.batteryAtLeast(n) : Condition.batteryBelow(n);
                }
                break;
            case "T":
                if (d.length() == 8) {
                    int from = minute(d.substring(0, 4));
                    int to = minute(d.substring(4));
                    if (from == to) {
                        throw new Rejected(Problem.VALUE, "empty time range");
                    }
                    return Condition.timeBetween(from, to);
                }
                break;
            case "W":
                if (d.length() >= 1 && d.length() <= 7) {
                    int days = 0;
                    for (int i = 0; i < d.length(); i++) {
                        char c = d.charAt(i);
                        if (c < '1' || c > '7') {
                            throw new Rejected(Problem.VALUE, "weekday " + c);
                        }
                        days |= 1 << (c - '1');
                    }
                    return Condition.weekdays(days);
                }
                break;
            case "P":
            case "X":
                if (d.length() >= 1 && d.length() <= MAX_PLACES) {
                    TreeSet<Integer> numbers = new TreeSet<>();
                    for (int i = 0; i < d.length(); i++) {
                        numbers.add(Integer.parseInt(slot(d.charAt(i), used)));
                    }
                    List<String> places = new ArrayList<>();
                    for (int n : numbers) {
                        places.add(String.valueOf(n));
                    }
                    return "P".equals(letter) ? Condition.inPlaces(places) : Condition.notInPlaces(places);
                }
                break;
            default:
                break;
        }
        throw new Rejected(Problem.SYNTAX, "condition " + letter + d);
    }

    private static List<Change> parseChanges(String d) throws Rejected {
        if (d.isEmpty()) {
            throw new Rejected(Problem.SYNTAX, "empty C");
        }
        List<Change> out = new ArrayList<>();
        int pos = 0;
        while (pos < d.length()) {
            int s = settingOf(d.charAt(pos));
            pos++;
            Setting setting = SETTINGS[s];
            if (setting.readOnly()) {
                throw new Rejected(Problem.VALUE, setting.key + " is read-only");
            }
            int width = VALUES[s] == null ? 2 : 1;
            if (pos + width > d.length()) {
                throw new Rejected(Problem.SYNTAX, "short value");
            }
            int n = Integer.parseInt(d.substring(pos, pos + width));
            pos += width;
            String value;
            if (VALUES[s] == null) {
                if (n < setting.min() || n > setting.max()) {
                    throw new Rejected(Problem.VALUE, setting.key + "=" + n);
                }
                value = String.valueOf(n);
            } else {
                if (n >= VALUES[s].length) {
                    throw new Rejected(Problem.VALUE, setting.key + " value " + n);
                }
                value = VALUES[s][n];
            }
            out.add(Change.of(setting, value));
        }
        return out;
    }

    private static String gearOf(String d) throws Rejected {
        int n = d.charAt(0) - '0';
        if (n >= GEARS.length) {
            throw new Rejected(Problem.VALUE, "gear " + d);
        }
        return GEARS[n];
    }

    private static int settingOf(char d) throws Rejected {
        int s = d - '0';
        if (s >= SETTINGS.length) {
            throw new Rejected(Problem.VALUE, "setting " + s);
        }
        return s;
    }

    /** 「变为」「是」的值：一段整数的设置不能用。 */
    private static String stateValue(int s, char d) throws Rejected {
        if (VALUES[s] == null) {
            throw new Rejected(Problem.VALUE, SETTINGS[s].key + " cannot be a state");
        }
        int v = d - '0';
        if (v >= VALUES[s].length) {
            throw new Rejected(Problem.VALUE, SETTINGS[s].key + " value " + v);
        }
        return VALUES[s][v];
    }

    private static String slot(char d, Set<Integer> used) throws Rejected {
        int n = d - '0';
        if (n == 0) {
            throw new Rejected(Problem.VALUE, "place 0");
        }
        used.add(n);
        return String.valueOf(n);
    }

    private static int minute(String hhmm) throws Rejected {
        int h = Integer.parseInt(hhmm.substring(0, 2));
        int m = Integer.parseInt(hhmm.substring(2));
        if (h > 23 || m > 59) {
            throw new Rejected(Problem.VALUE, "time " + hhmm);
        }
        return h * 60 + m;
    }

    // ---------- 导入 ----------

    /** 解出来的规则用到几个地点（编号 1……n）。 */
    public static int places(Rule shared) {
        return placesOf(shared).size();
    }

    /**
     * 解出来的规则（地点是编号 "1"……"n"）换成用户选的地点，成为一条新的自动化（开着、没有名字，同「添加」）。
     *
     * @param places 第 i 个是编号 i+1 的地点（{@link Place#id}），个数正好是 {@link #places}，不能重
     * @return 地点个数不对、有重的是 null
     */
    public static Rule adopt(Rule shared, String id, List<String> places) {
        int n = places(shared);
        if (places == null || places.size() != n || new HashSet<>(places).size() != n) {
            return null;
        }
        Map<String, String> mine = new HashMap<>();
        for (int i = 0; i < n; i++) {
            mine.put(String.valueOf(i + 1), places.get(i));
        }
        Trigger trigger = shared.trigger;
        if (trigger.kind == Trigger.Kind.ARRIVE || trigger.kind == Trigger.Kind.LEAVE) {
            String place = mine.get(trigger.place);
            if (place == null) {
                return null;
            }
            trigger = trigger.kind == Trigger.Kind.ARRIVE ? Trigger.arrive(place) : Trigger.leave(place);
        }
        List<Condition> conditions = new ArrayList<>();
        for (Condition c : shared.conditions) {
            if (c.places.isEmpty()) {
                conditions.add(c);
                continue;
            }
            List<String> ids = new ArrayList<>();
            for (String p : c.places) {
                String place = mine.get(p);
                if (place == null) {
                    return null;
                }
                ids.add(place);
            }
            conditions.add(c.kind == Condition.Kind.IN_PLACES ? Condition.inPlaces(ids) : Condition.notInPlaces(ids));
        }
        return new Rule(id, "", true, trigger, conditions, shared.changes, shared.restore);
    }
}
