package com.kooo.evcam.zeekr;

import android.content.Context;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.view.Display;

import java.util.ArrayList;
import java.util.List;

/**
 * 车机上哪一块屏是 HUD（盲区画面投到 HUD 上，2026-10-21）。
 *
 * <h3>规则</h3>
 *
 * <ul>
 *   <li>不是主屏、真实尺寸 1440×480：7X 的 HUD（诊断报告「显示屏」一节里是 id 3）。</li>
 *   <li>没有这个尺寸的屏：退到 id 3，前提是它不是主屏、也不是 2560×536 那块（仪表盘）—— 宁可找不到，也不往仪表盘上画。</li>
 *   <li>关着的屏不算：HUD 关了，系统还留着这块屏，往上画等于哪里都没有。</li>
 * </ul>
 *
 * <p>来源：FerixzTanz 的 CAMCORDER（HudDisplayLocator，GPL-3.0），他在自己的 7X 上实车用着。规则照搬，代码自己写。
 * 判断是纯 Java，见 {@code HudScreenTest}。</p>
 */
public final class HudScreen {

    /** 找不到。 */
    public static final int NONE = -1;

    static final int DEFAULT_DISPLAY_ID = 0;
    static final int WIDTH = 1440;
    static final int HEIGHT = 480;
    /** 按尺寸找不到时退到这一块（7X 上 HUD 一直是 3）。 */
    static final int FALLBACK_ID = 3;
    /** 仪表盘的尺寸：退到 id 3 时不能是它。 */
    static final int CLUSTER_WIDTH = 2560;
    static final int CLUSTER_HEIGHT = 536;

    private HudScreen() {
    }

    /** 一块屏：id、真实尺寸（像素）、开没开着。 */
    public static final class Candidate {
        final int id;
        final int width;
        final int height;
        final boolean on;

        public Candidate(int id, int width, int height, boolean on) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.on = on;
        }
    }

    /** @return HUD 那块屏的 id，找不到是 {@link #NONE} */
    public static int find(List<Candidate> displays) {
        int bySize = findBySize(displays);
        if (bySize != NONE) {
            return bySize;
        }
        for (Candidate c : displays) {
            if (c.on && c.id == FALLBACK_ID && !(c.width == CLUSTER_WIDTH && c.height == CLUSTER_HEIGHT)) {
                return c.id;
            }
        }
        return NONE;
    }

    /** 只按尺寸找（诊断报告里写明是按尺寸找到的，还是退到 id 3 的）。 */
    public static int findBySize(List<Candidate> displays) {
        for (Candidate c : displays) {
            if (c.on && c.id != DEFAULT_DISPLAY_ID && c.width == WIDTH && c.height == HEIGHT) {
                return c.id;
            }
        }
        return NONE;
    }

    /** 此刻的 HUD 那块屏；找不到是 null。要一次 DisplayManager 调用，调用方自己控制多久问一次。 */
    public static Display find(Context context) {
        Display[] displays = displays(context);
        int id = find(candidates(displays));
        if (id == NONE) {
            return null;
        }
        for (Display d : displays) {
            if (d.getDisplayId() == id) {
                return d;
            }
        }
        return null;
    }

    /** 此刻的几块屏（诊断报告照它写 HUD 是哪一块、怎么找到的）；拿不到是空的。 */
    public static List<Candidate> candidatesNow(Context context) {
        return candidates(displays(context));
    }

    private static Display[] displays(Context context) {
        DisplayManager dm = (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
        if (dm == null) {
            return new Display[0];
        }
        try {
            return dm.getDisplays();
        } catch (RuntimeException e) {
            return new Display[0];
        }
    }

    private static List<Candidate> candidates(Display[] displays) {
        List<Candidate> list = new ArrayList<>();
        for (Display d : displays) {
            Point size = new Point();
            try {
                d.getRealSize(size);
            } catch (RuntimeException ignored) {
                // 拿不到尺寸就当 0×0，不会被认成 HUD
            }
            list.add(new Candidate(d.getDisplayId(), size.x, size.y, d.getState() == Display.STATE_ON));
        }
        return list;
    }
}
