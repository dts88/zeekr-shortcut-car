package com.kooo.evcam.zeekr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 「环视 + 座舱 3 路」模式的槽位分配。
 *
 * <p>把三个槽位（环视 / 座舱1 / 座舱2）分给具体的相机 id。前台与后台两条初始化路径
 * 以前各写了一份，规则还不一致 —— 后台那份根本不看手动指定。分配规则是纯逻辑，
 * 抽到这里只写一遍，也能直接跑单元测试。</p>
 *
 * <p><b>不负责</b>决定分辨率。三路同开时该用什么尺寸是另一回事，见 {@code CameraManagerHolder} 里的说明。</p>
 *
 * <h3>照存下的映射建（2.11，大纲 §2 阶段 6）</h3>
 *
 * <p>照当时的相机列表分槽位有一个坑：车机拿着后座舱（相机 1）时它不在列表里（platform-notes §3.6），
 * 这时冷启动，后座舱这一格就没有 {@code SingleCamera}，以后既认不出它被拿着、也开不了它。所以列表完整时把分好的映射存下来
 * （{@link Stored}，按车型存在 AppConfig 里），列表不完整时照存下的建（{@link #choose}）：列表里少的那一路照样建出来，
 * 交给通道调度判「不在」（被别的程序拿着）。手动指定变了，存的那一份作废，按当时的列表重算。</p>
 */
public final class ZeekrMultiPlan {

    /** 环视槽位使用的相机 id；没有可用相机时为 null。 */
    public final String compositeId;
    /** 座舱槽位 1；不足时为 null。 */
    public final String cabin1Id;
    /** 座舱槽位 2；不足时为 null。 */
    public final String cabin2Id;
    /**
     * 环视槽里的相机是否真的是探测到的合成流。
     *
     * <p>为 false 时说明没找到合成流，是拿一路普通相机顶上来占位的 ——
     * 这种情况下不该按四联合成流去拆分画面。</p>
     */
    public final boolean compositeIsReal;
    /** 分配过程的可读说明，写进日志与诊断报告。 */
    public final String explanation;
    /** 这一份是照存下的映射建的（相机列表不完整，{@link #choose}）。 */
    public final boolean fromStored;
    /** 照存下的映射建时，此刻相机列表里没有的那几个相机 id：照样建出来，交给通道调度判「不在」。照列表建的是空的。 */
    public final List<String> missing;

    private ZeekrMultiPlan(String compositeId, String cabin1Id, String cabin2Id,
                           boolean compositeIsReal, String explanation) {
        this(compositeId, cabin1Id, cabin2Id, compositeIsReal, explanation, false, new ArrayList<>());
    }

    private ZeekrMultiPlan(String compositeId, String cabin1Id, String cabin2Id, boolean compositeIsReal,
                           String explanation, boolean fromStored, List<String> missing) {
        this.compositeId = compositeId;
        this.cabin1Id = cabin1Id;
        this.cabin2Id = cabin2Id;
        this.compositeIsReal = compositeIsReal;
        this.explanation = explanation;
        this.fromStored = fromStored;
        this.missing = Collections.unmodifiableList(new ArrayList<>(missing));
    }

    /**
     * 照相机列表建的、三个槽位都分到了（列表里都有它们）：可以存下来（{@link Stored#of}，存不存见 {@link #worthStoring}），
     * 下次列表不完整时照它建。照存下的建的不算 —— 它说明这一次列表不完整。
     */
    public boolean complete() {
        return !fromStored && compositeId != null && cabin1Id != null && cabin2Id != null;
    }

    /** Apply canonical manual/Unassigned choices even when temporarily absent from the advertised list. */
    public ZeekrMultiPlan withSourceChoices(String[] listed, String detected,
                                            String surround, String frontCabin, String rearCabin) {
        String c = surround == null ? compositeId : surround.isEmpty() ? null : surround;
        String f = frontCabin == null ? cabin1Id : frontCabin.isEmpty() ? null : frontCabin;
        String r = rearCabin == null ? cabin2Id : rearCabin.isEmpty() ? null : rearCabin;
        if (f != null && f.equals(c)) f = null;
        if (r != null && (r.equals(c) || r.equals(f))) r = null;
        boolean real = c != null && (c.equals(detected) || c.equals(compositeId) && compositeIsReal);
        List<String> absent = new ArrayList<>();
        java.util.Set<String> available = new java.util.HashSet<>(java.util.Arrays.asList(listed));
        for (String id : new String[]{c,f,r}) if (id != null && !available.contains(id)) absent.add(id);
        return new ZeekrMultiPlan(c,f,r,real,explanation,fromStored,absent);
    }


    /**
     * 照列表建的这一份该不该存下来、替掉存着的那一份（CameraManagerHolder 照列表建完时问）。
     *
     * <p>要完整（{@link #complete}），还要环视是探测到的合成流（{@link #compositeIsReal}）—— 合成流这一次没探测到
     * （相机服务忙，读某一路的参数出错了一次），列表却是完整的时候，照列表分出来的是「拿第一路普通相机顶替环视、座舱顺次挪位」，
     * 存下来就把好的那一份盖掉了，下一次列表不完整时照它建：环视开成普通相机、四宫格不拆、座舱错位（审查 2026-10-11）。
     * 只有还没存过能用的（没存过、存的不完整、手动指定变了）时，才把不是合成流的这一份存下来 —— 总比没有强。</p>
     *
     * @param stored    存着的那一份（{@link Stored#decode}）；没存过是 null
     * @param overrides 这一次的手动指定（{@link Stored#overridesKey}）
     */
    public boolean worthStoring(Stored stored, String overrides) {
        if (!complete()) {
            return false;
        }
        if (compositeIsReal) {
            return true;
        }
        return stored == null || !stored.complete() || !stored.overrides.equals(overrides);
    }

    /** 实际分到了几路。 */
    public int assignedCount() {
        int n = 0;
        if (compositeId != null) {
            n++;
        }
        if (cabin1Id != null) {
            n++;
        }
        if (cabin2Id != null) {
            n++;
        }
        return n;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "环视=%s%s, 座舱1=%s, 座舱2=%s",
                compositeId, compositeIsReal ? "(合成流)" : "(普通相机顶替)",
                cabin1Id, cabin2Id);
    }

    /**
     * 分配三个槽位。
     *
     * @param cameraIds           车机上可用的全部相机 id
     * @param detectedCompositeId 探测到的合成流 id；没找到传 null
     * @param overrideComposite   手动指定的环视相机；不指定传 null
     * @param overrideCabin1      手动指定的座舱 1；不指定传 null
     * @param overrideCabin2      手动指定的座舱 2；不指定传 null
     */
    public static ZeekrMultiPlan build(String[] cameraIds, String detectedCompositeId,
                                       String overrideComposite, String overrideCabin1,
                                       String overrideCabin2) {
        StringBuilder why = new StringBuilder();
        List<String> available = new ArrayList<>();
        if (cameraIds != null) {
            for (String id : cameraIds) {
                if (id != null && !available.contains(id)) {
                    available.add(id);
                }
            }
        }
        why.append("可用相机: ").append(available).append('\n');

        String composite = detectedCompositeId;
        boolean compositeIsReal = composite != null && available.contains(composite);
        if (composite != null && !compositeIsReal) {
            why.append("探测到的合成流 ").append(composite)
                    .append(" 不在可用列表里，忽略\n");
            composite = null;
        }
        why.append(compositeIsReal
                ? "合成流: " + composite + "\n"
                : "未探测到合成流\n");

        // 其余相机按 id 顺序补进座舱槽位
        List<String> others = new ArrayList<>();
        for (String id : available) {
            if (!id.equals(composite)) {
                others.add(id);
            }
        }
        if (composite == null && !others.isEmpty()) {
            // 没有合成流也别让主画面空着，拿第一路顶上
            composite = others.remove(0);
            why.append("用 ").append(composite).append(" 顶替环视槽位，避免主画面全黑\n");
        }

        String cabin1 = others.size() > 0 ? others.get(0) : null;
        String cabin2 = others.size() > 1 ? others.get(1) : null;

        // 手动指定优先。Camera2 分不出后排/驾驶位，自动分配只是按 id 顺序猜，
        // 所以允许直接指定 —— 这也是排查三路黑屏最直接的手段。
        boolean manual = false;
        if (isUsable(overrideComposite, available)) {
            composite = overrideComposite;
            compositeIsReal = composite.equals(detectedCompositeId);
            manual = true;
        }
        if (isUsable(overrideCabin1, available)) {
            cabin1 = overrideCabin1;
            manual = true;
        }
        if (isUsable(overrideCabin2, available)) {
            cabin2 = overrideCabin2;
            manual = true;
        }
        if (manual) {
            why.append("应用了手动指定的相机映射\n");
        }
        // 两个槽位指到同一路：后指定的作废。同一路相机只建一份，不再有「从实例」
        if (cabin1 != null && cabin1.equals(composite)) {
            why.append("座舱 1 指定的 ").append(cabin1).append(" 已经是环视，忽略\n");
            cabin1 = null;
        }
        if (cabin2 != null && (cabin2.equals(composite) || cabin2.equals(cabin1))) {
            why.append("座舱 2 指定的 ").append(cabin2).append(" 已经用在别的槽位，忽略\n");
            cabin2 = null;
        }

        ZeekrMultiPlan plan = new ZeekrMultiPlan(composite, cabin1, cabin2,
                compositeIsReal, why.toString());
        return plan;
    }

    private static boolean isUsable(String override, List<String> available) {
        return override != null && available.contains(override);
    }

    /**
     * 这一次照什么建（大纲 §2 阶段 6）。
     *
     * <ul>
     *   <li>照相机列表分得齐三个槽位（{@link #complete}）→ 照列表（{@link #build}）；调用方照 {@link #worthStoring} 存下来，和存着的不一样才写；</li>
     *   <li>分不齐，而存过完整的映射、手动指定也没变 → 照存下的建：列表里少的那一路照样建出来，交给通道调度判「不在」 ——
     *       车机拿着后座舱时它就不在列表里，照列表分的话后座舱这一格就没了；</li>
     *   <li>都不是（第一次装、手动指定改过）→ 照列表，少一路就少一路，等下一次列表完整时补上（大纲 §6 风险 13）。</li>
     * </ul>
     *
     * <p>列表完整时照列表、不照存下的：存下的是上一次完整时的样子，这一次完整的列表更新它（车机换过相机编号也跟得上）。</p>
     *
     * @param stored 存下的映射（{@link Stored#decode}）；没存过传 null
     */
    public static ZeekrMultiPlan choose(String[] cameraIds, String detectedCompositeId,
                                        String overrideComposite, String overrideCabin1, String overrideCabin2,
                                        Stored stored) {
        ZeekrMultiPlan listed = build(cameraIds, detectedCompositeId, overrideComposite, overrideCabin1, overrideCabin2);
        if (listed.complete() || stored == null || !stored.complete()
                || !stored.overrides.equals(Stored.overridesKey(overrideComposite, overrideCabin1, overrideCabin2))) {
            return listed;
        }
        List<String> available = new ArrayList<>();
        if (cameraIds != null) {
            for (String id : cameraIds) {
                if (id != null) {
                    available.add(id);
                }
            }
        }
        List<String> missing = new ArrayList<>();
        for (String id : new String[]{stored.compositeId, stored.cabin1Id, stored.cabin2Id}) {
            if (!available.contains(id)) {
                missing.add(id);
            }
        }
        String why = listed.explanation + "相机列表分不齐三个槽位，照存下的映射建: " + stored
                + (missing.isEmpty() ? "" : "；列表里现在没有 " + missing + "，照样建出来，交给通道调度判「不在」") + '\n';
        return new ZeekrMultiPlan(stored.compositeId, stored.cabin1Id, stored.cabin2Id, stored.compositeIsReal,
                why, true, missing);
    }

    /**
     * 存下的完整映射（按车型存在 AppConfig 里，大纲 §1 CameraManagerHolder）：三路的相机 id、环视是不是真合成流、合成流尺寸，
     * 外加存的时候手动指定是什么 —— 手动指定变了，这一份作废（{@link #choose}）。纯数据，编成一行字存、读回来（{@link #encode}）。
     */
    public static final class Stored {
        /** 编码的版本：格式改了就换一个，旧的读回来是 null，当没存过。 */
        private static final String VERSION = "v1";

        public final String compositeId;
        public final String cabin1Id;
        public final String cabin2Id;
        public final boolean compositeIsReal;
        /** 合成流尺寸；0 = 不知道（环视不是合成流，或者存的时候没探测到尺寸）。 */
        public final int compositeWidth;
        public final int compositeHeight;
        /** 存的时候的手动指定（{@link #overridesKey}）。 */
        public final String overrides;

        public Stored(String compositeId, String cabin1Id, String cabin2Id, boolean compositeIsReal,
                      int compositeWidth, int compositeHeight, String overrides) {
            this.compositeId = compositeId;
            this.cabin1Id = cabin1Id;
            this.cabin2Id = cabin2Id;
            this.compositeIsReal = compositeIsReal;
            this.compositeWidth = Math.max(0, compositeWidth);
            this.compositeHeight = Math.max(0, compositeHeight);
            this.overrides = overrides == null ? overridesKey(null, null, null) : overrides;
        }

        /**
         * 照列表建出来的完整映射（{@link ZeekrMultiPlan#complete}）存成这一份。
         *
         * @param overrides 建的时候的手动指定（{@link #overridesKey}）
         */
        public static Stored of(ZeekrMultiPlan plan, int compositeWidth, int compositeHeight, String overrides) {
            return new Stored(plan.compositeId, plan.cabin1Id, plan.cabin2Id, plan.compositeIsReal,
                    compositeWidth, compositeHeight, overrides);
        }

        /** 三路都有、互不相同：照它建得出三个槽位。 */
        public boolean complete() {
            return compositeId != null && cabin1Id != null && cabin2Id != null
                    && !compositeId.equals(cabin1Id) && !compositeId.equals(cabin2Id) && !cabin1Id.equals(cabin2Id);
        }

        /** 手动指定编成一个比较用的键：按环视、座舱 1、座舱 2 的次序，没指定的是空。 */
        public static String overridesKey(String overrideComposite, String overrideCabin1, String overrideCabin2) {
            return orEmpty(overrideComposite) + "|" + orEmpty(overrideCabin1) + "|" + orEmpty(overrideCabin2);
        }

        /** 编成一行存下来：{@code v1;环视;座舱1;座舱2;1 或 0;宽x高;手动指定}。 */
        public String encode() {
            return VERSION + ";" + orEmpty(compositeId) + ";" + orEmpty(cabin1Id) + ";" + orEmpty(cabin2Id) + ";"
                    + (compositeIsReal ? "1" : "0") + ";" + compositeWidth + "x" + compositeHeight + ";" + overrides;
        }

        /** 读回来；没存过、格式不对（换过格式的旧值、被改坏了）都是 null，当没存过。 */
        public static Stored decode(String text) {
            if (text == null) {
                return null;
            }
            String[] parts = text.split(";", -1);
            if (parts.length != 7 || !VERSION.equals(parts[0])) {
                return null;
            }
            String[] size = parts[5].split("x", -1);
            if (size.length != 2) {
                return null;
            }
            try {
                return new Stored(orNull(parts[1]), orNull(parts[2]), orNull(parts[3]), "1".equals(parts[4]),
                        Integer.parseInt(size[0]), Integer.parseInt(size[1]), parts[6]);
            } catch (NumberFormatException e) {
                return null;
            }
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "环视=%s%s, 座舱1=%s, 座舱2=%s", compositeId,
                    compositeIsReal ? "(合成流 " + compositeWidth + "x" + compositeHeight + ")" : "(普通相机顶替)",
                    cabin1Id, cabin2Id);
        }

        private static String orEmpty(String value) {
            return value == null ? "" : value;
        }

        private static String orNull(String value) {
            return value.isEmpty() ? null : value;
        }
    }
}
