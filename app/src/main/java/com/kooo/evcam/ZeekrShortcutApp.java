package com.kooo.evcam;

import android.app.Application;

import com.kooo.evcam.settings.Languages;

/**
 * 应用入口。
 *
 * <p>只做一件事：在任何界面创建之前把界面语言定下来。放在这里而不是各个 Activity 里，
 * 是因为语言是<b>整个进程</b>的属性 —— 悬浮窗、通知、服务里的提示都要跟着走，
 * 而它们不属于任何一个 Activity。</p>
 */
public class ZeekrShortcutApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Languages.apply(new AppConfig(this).getLanguageMode());
    }
}
