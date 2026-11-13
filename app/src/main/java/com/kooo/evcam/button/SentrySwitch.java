/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.button;

import android.content.Context;
import android.widget.Toast;

import com.kooo.evcam.R;
import com.kooo.evcam.blackbox.BlackBox;

/**
 * 打开、关掉哨兵模式：主界面录制键旁那个键用（{@code ui.RecordButtonUi}，项目所有者 2026-11-13）。点一下是开；
 * 开上之后、键收起之前再点一下是反悔，关掉（2026-11-14）。
 *
 * <p>和灵动贴的「哨兵模式」动作是同一条写车的路（{@link CarMoves}：同一个线程、同样读回、同样记黑匣子），差别只在这里说清
 * 要开还是要关（{@link CarMoves#sentryOn}、{@link CarMoves#sentryOff}），不是互换。没做成时提示的也是那几句。</p>
 */
public final class SentrySwitch {

    /** 写完、读回之后（主线程）。 */
    public interface Done {
        /** @param done 读回是要的那个样子（本来就是也算） */
        void onResult(boolean done);
    }

    private SentrySwitch() {
    }

    /**
     * 主线程上叫。车辆接口没连上、车机不受理、写了没变都算没做成：提示一句，{@code done} 收到 false。
     *
     * @param on true 打开，false 关掉
     */
    public static void turn(Context context, boolean on, Done done) {
        Context app = context.getApplicationContext();
        BlackBox.noteImportant(on ? "主界面：点了「开启哨兵模式」" : "主界面：哨兵模式那个键收起之前又点了一下，关掉");
        CarMoves.run(app, on ? "sentry_on" : "sentry_off", on ? CarMoves.sentryOn() : CarMoves.sentryOff(), result -> {
            String text = CarMoves.failureText(app, app.getString(R.string.smart_button_action_sentry), result);
            if (text != null) {
                Toast.makeText(app, text, Toast.LENGTH_SHORT).show();
            }
            done.onResult(result == CarMoves.Result.DONE);
        });
    }
}
