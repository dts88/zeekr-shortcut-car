package com.kooo.evcam.zeekr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「环视 + 座舱 3 路」模式的槽位分配。
 *
 * <p>把三个槽位（环视 / 座舱1 / 座舱2）分给具体的相机 id。前台与后台两条初始化路径
 * 以前各写了一份，规则还不一致 —— 后台那份根本不看手动指定。分配规则是纯逻辑，
 * 抽到这里只写一遍，也能直接跑单元测试。</p>
 *
 * <p><b>不负责</b>决定分辨率。三路同开时该用什么尺寸是另一回事，见
 * {@code MainActivity#initCamerasForZeekrMulti} 里的说明。</p>
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

    private ZeekrMultiPlan(String compositeId, String cabin1Id, String cabin2Id,
                           boolean compositeIsReal, String explanation) {
        this.compositeId = compositeId;
        this.cabin1Id = cabin1Id;
        this.cabin2Id = cabin2Id;
        this.compositeIsReal = compositeIsReal;
        this.explanation = explanation;
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

        ZeekrMultiPlan plan = new ZeekrMultiPlan(composite, cabin1, cabin2,
                compositeIsReal, why.toString());
        return plan;
    }

    private static boolean isUsable(String override, List<String> available) {
        return override != null && available.contains(override);
    }
}
