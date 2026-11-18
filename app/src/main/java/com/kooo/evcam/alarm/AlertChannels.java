/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Portions Copyright (C) 2026 Fab (Fabbbrrr) - https://github.com/Fabbbrrr
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.alarm;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.alarm.ntfy.NtfyChannel;
import com.kooo.evcam.alarm.ntfy.NtfyRisk;

/**
 * 出口的工厂：设置进，{@link AlertChannel} 出。整个应用只有这一处认得出「谁是谁」。
 *
 * <h3>为什么单独一个类</h3>
 *
 * <p>这段判断原来是 {@code SentryAlerts} 里的一个私有方法。挪出来只有一个理由：它是<b>唯一</b>可以
 * 出现具体出口名字的地方，而这件事要能用一个测试钉住 —— 测试读不了一个私有方法，能读一个类。
 * {@code ProviderWiringTest} 就是那条钉子：除了这里，{@code alarm} 包里谁都不许直接点名
 * {@link NtfyChannel}。</p>
 *
 * <h3>加第三条出口要动哪里</h3>
 *
 * <p>一个包、一个 {@code implements AlertChannel} 的类、这里加一行、设置里加一个引导对话框。
 * {@code SentryAlerts} 和 {@link com.kooo.evcam.alarm.policy.AlertPolicy} 一行都不用改 ——
 * 要改，说明这一层没搭对，先改这一层。</p>
 *
 * <h3>Why this is its own class</h3>
 *
 * <p>This decision used to be a private method inside {@code SentryAlerts}. It moved for one reason: it
 * is the <b>only</b> place allowed to name a concrete exit, and that has to be pinned by a test — a test
 * cannot read a private method, it can read a class. {@code ProviderWiringTest} is the pin: outside this
 * file nothing in the {@code alarm} package may name {@link NtfyChannel}.</p>
 *
 * <h3>What a third exit costs</h3>
 *
 * <p>One package, one {@code implements AlertChannel} class, one line here, one setup dialog in settings.
 * {@code SentryAlerts} and {@link com.kooo.evcam.alarm.policy.AlertPolicy} change by not one line — and if
 * they have to, this layer is wrong and gets fixed first.</p>
 */
public final class AlertChannels {

    private AlertChannels() {
    }

    /**
     * 现在的出口。ntfy 免费话题的风险提示没被接受，交出来的出口会拒绝发送
     * （{@link NtfyRisk}），由 {@code SentryAlerts.blockedChannel} 说清楚。
     *
     * The exit to use now. An unaccepted free-topic warning yields an exit that refuses to send
     * ({@link NtfyRisk}), which {@code SentryAlerts.blockedChannel} explains.
     */
    public static AlertChannel of(AppConfig config) {
        return NtfyChannel.of(config);
    }
}
