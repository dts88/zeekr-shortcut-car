package com.kooo.evcam;

import android.app.Application;

import com.kooo.evcam.camera.StallWatch;
import com.kooo.evcam.settings.Languages;

/**
 * 应用入口。
 *
 * <p>在任何界面创建之前把界面语言定下来。放在这里而不是各个 Activity 里，
 * 是因为语言是<b>整个进程</b>的属性 —— 悬浮窗、通知、服务里的提示都要跟着走，
 * 而它们不属于任何一个 Activity。</p>
 *
 * <p>卡顿监测（{@link StallWatch}）也在这里启动，理由一样：它要盯的后视镜是悬浮窗，
 * 主界面没打开过也在跑。</p>
 */
public class ZeekrShortcutApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        Languages.apply(new AppConfig(this).getLanguageMode());
        StallWatch.start(this);
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // 系统在催内存。卡顿报告带着日志尾巴，内存紧张和卡住是不是同时发生，一看便知
        AppLog.w("App", "onTrimMemory level " + level);
    }
}
