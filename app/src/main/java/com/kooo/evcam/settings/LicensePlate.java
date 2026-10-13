package com.kooo.evcam.settings;

/**
 * 车牌号的取值规则。
 *
 * <h3>为什么要清洗而不是拒绝</h3>
 *
 * <p>车机上是软键盘输入，敲进小写字母、空格、连字符都很正常。直接判「不合法」
 * 让人重打一遍很烦，而把它清洗成合法值、并且<b>把清洗后的结果显示出来</b>，
 * 用户一眼就知道最终录进画面的是什么。</p>
 *
 * <p>纯逻辑，可以单独测。</p>
 */
public final class LicensePlate {

    /** 最长十位。 */
    public static final int MAX_LENGTH = 10;

    /** 信息条前灯组那一格的车牌上最多显示几位数字（{@link #infoBarDigits}）。 */
    public static final int INFO_BAR_DIGITS = 4;

    private LicensePlate() {
    }

    /**
     * 清洗成可用的车牌号：只留大写字母和数字，最长十位。
     *
     * @return 清洗后的结果；没有任何可用字符时返回空串
     */
    public static String sanitize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < raw.length() && sb.length() < MAX_LENGTH; i++) {
            char c = Character.toUpperCase(raw.charAt(i));
            if ((c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 清洗之后还剩下东西才算填了。 */
    public static boolean isUsable(String raw) {
        return !sanitize(raw).isEmpty();
    }

    /**
     * 信息条前灯组那一格车牌上要显示的数字（彩蛋，项目所有者 2026-10-04）：只留数字（字母、汉字、空格一律去掉），
     * 超过四位只留最后 {@link #INFO_BAR_DIGITS} 位。例：「粤B12345」→「2345」，「SGX1234A」→「1234」，「WXY 89」→「89」。
     *
     * <p>只认 ASCII 的 0–9：传进来的是 {@code AppConfig.getLicensePlate()}，已经清洗过（只剩大写字母和数字），
     * 开关关着或者没填时是空串。</p>
     *
     * @return 要显示的数字；没填车牌、或者车牌里没有数字时返回空串（车牌照旧空着）
     */
    public static String infoBarDigits(String plate) {
        if (plate == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plate.length(); i++) {
            char c = plate.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            }
        }
        return sb.length() > INFO_BAR_DIGITS ? sb.substring(sb.length() - INFO_BAR_DIGITS) : sb.toString();
    }
}
