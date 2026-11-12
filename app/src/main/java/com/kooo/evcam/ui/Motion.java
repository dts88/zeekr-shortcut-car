/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.ui;

import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/**
 * 动效的时长和曲线，只写在这一处（ui-spec §十四）。
 *
 * <p>时长只有三档；别处要用动效就引用这里的名字，不写数字。放不放由 {@link MotionPolicy#decorative} 说了算：
 * 它说不放时，调用的地方直接切到结果，不是把时长改成 0 再放一遍。</p>
 */
public final class Motion {

    /** 值变了：开关、分段的选中块、文字淡换。 */
    public static final long FAST = 150;
    /** 展开、浮起来的框、往前一步和往回一步。 */
    public static final long MEDIUM = 250;
    /** 换界面、网格和单格之间。再长，在车机上就从顺变成卡。 */
    public static final long SLOW = 300;

    /** 收起比展开快一点：要走的东西不用人等。 */
    public static final long COLLAPSE = 200;
    /** 一行一行长出来时，后一行比前一行晚这么多。 */
    public static final long STAGGER = 30;
    /** 被指到的那一行闪一下的时长；闪两下。 */
    public static final long FLASH = 270;

    private static final Interpolator ENTER = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);
    private static final Interpolator EXIT = new PathInterpolator(0.3f, 0f, 0.8f, 0.15f);
    private static final Interpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);

    private Motion() {
    }

    /** 进来的东西：先快后慢，停得稳。 */
    public static Interpolator enter() {
        return ENTER;
    }

    /** 出去的东西：先慢后快，不拖泥带水。 */
    public static Interpolator exit() {
        return EXIT;
    }

    /** 在屏幕上挪位置、变大小的东西。 */
    public static Interpolator standard() {
        return STANDARD;
    }
}
