/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.automation;

/** 一条自动化的「就」里的一个动作（ui-spec §十八「就（动作）」）：把某项设置设成某个值。不可变。 */
public final class Change {

    public final Setting setting;
    public final String value;

    private Change(Setting setting, String value) {
        this.setting = setting;
        this.value = value;
    }

    /** 把 {@code setting} 设成 {@code value}；只读的、值不是它的是 null。 */
    public static Change of(Setting setting, String value) {
        return setting != null && !setting.readOnly() && setting.code(value) != null ? new Change(setting, value) : null;
    }

    /** 写车的码。 */
    public int code() {
        return setting.code(value);
    }

    /** 这是不是关掉安全功能（必须结束时恢复）。 */
    public boolean safetyOff() {
        return setting.isSafetyOff(value);
    }

    /** 黑匣子里的写法。 */
    public String describe() {
        return setting.key + "=" + value;
    }
}
