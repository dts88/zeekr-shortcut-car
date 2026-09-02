package com.kooo.evcam.update;

/**
 * 版本号比较。
 *
 * <h3>为什么不直接比字符串</h3>
 *
 * <p>本项目的 tag 长这样：{@code v0.25.2-alpha}，而应用里的 {@code versionName}
 * 是 {@code 0.25.2-alpha} —— 前缀差一个 v。而且字符串比大小会得出
 * {@code "0.9.0" > "0.25.0"}（因为 '9' > '2'），正好是「有更新却说没有」。</p>
 *
 * <p>所以按段拆开、按数字比。这部分完全不碰网络和 Android，可以单独测。</p>
 */
public final class VersionName {

    private VersionName() {
    }

    /**
     * 比较两个版本号。
     *
     * @return a 比 b 新返回正数，旧返回负数，一样返回 0
     */
    public static int compare(String a, String b) {
        String[] coreA = splitCore(a);
        String[] coreB = splitCore(b);

        int[] numsA = numbers(coreA[0]);
        int[] numsB = numbers(coreB[0]);
        int len = Math.max(numsA.length, numsB.length);
        for (int i = 0; i < len; i++) {
            // 位数不一样时按 0 补齐：0.25 和 0.25.0 是同一个版本
            int x = i < numsA.length ? numsA[i] : 0;
            int y = i < numsB.length ? numsB[i] : 0;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }

        // 数字段一样，再看预发布后缀。按 semver：正式版比同号的预发布版新
        boolean preA = !coreA[1].isEmpty();
        boolean preB = !coreB[1].isEmpty();
        if (preA != preB) {
            return preA ? -1 : 1;
        }
        return coreA[1].compareTo(coreB[1]);
    }

    /** 远端那个版本是不是比本机的新。拿不准（解析不出数字）时一律当作「不是」。 */
    public static boolean isNewer(String remote, String local) {
        if (remote == null || local == null) {
            return false;
        }
        if (numbers(splitCore(remote)[0]).length == 0
                || numbers(splitCore(local)[0]).length == 0) {
            // 认不出来的版本号不要冒充有更新 —— 误报会把用户带去装一个来路不明的包
            return false;
        }
        return compare(remote, local) > 0;
    }

    /** 拆成「数字部分」和「预发布后缀」两段，顺便吃掉前导的 v。 */
    private static String[] splitCore(String version) {
        String s = version == null ? "" : version.trim();
        if (s.startsWith("v") || s.startsWith("V")) {
            s = s.substring(1);
        }
        int plus = s.indexOf('+');           // 构建元数据，不参与比较
        if (plus >= 0) {
            s = s.substring(0, plus);
        }
        int dash = s.indexOf('-');
        if (dash < 0) {
            return new String[]{s, ""};
        }
        return new String[]{s.substring(0, dash), s.substring(dash + 1)};
    }

    private static int[] numbers(String core) {
        if (core.isEmpty()) {
            return new int[0];
        }
        String[] parts = core.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return new int[0];   // 有一段不是数字，整个版本号就当作认不出来
            }
        }
        return out;
    }
}
