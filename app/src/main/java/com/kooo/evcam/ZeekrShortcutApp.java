package com.kooo.evcam;

import android.app.Application;
import android.content.res.Configuration;

import androidx.annotation.NonNull;

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

    /** 上一次看到的配置，拿来和新的比，看变的到底是哪一项。 */
    private Configuration lastConfig;

    @Override
    public void onCreate() {
        super.onCreate();
        com.kooo.evcam.settings.DeveloperMode.init(this);
        lastConfig = new Configuration(getResources().getConfiguration());
        // 黑匣子尽早接上。ContentProvider 比这里还早，那边也会接一次，谁先谁算
        com.kooo.evcam.blackbox.BlackBox.attach(this, "Application");
        // U 盘挂上、卸下、异常掉线进黑匣子（2026-09-26 录像盘掉线，系统那边发生了什么一行都没记下）
        com.kooo.evcam.blackbox.VolumeEvents.register(this);
        Languages.apply(new AppConfig(this).getLanguageMode());
        StallWatch.start(this);
    }

    /**
     * 配置一变，界面就被系统重建。2026-09-26 那次车机卡死、恢复之后，主界面被重建过一次，
     * 当时看不出变的是什么 —— 现在把变了的那几项记进黑匣子。
     */
    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Configuration old = lastConfig;
        lastConfig = new Configuration(newConfig);
        if (old != null) {
            com.kooo.evcam.blackbox.BlackBox.noteImportant("配置变化：" + describeChange(old, newConfig));
        }
    }

    /** 变了哪几项、从什么变成什么。只给维护者看，写英文字段名。 */
    static String describeChange(Configuration a, Configuration b) {
        StringBuilder sb = new StringBuilder();
        int nightA = a.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        int nightB = b.uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (nightA != nightB) {
            sb.append(" night ").append(nightA == Configuration.UI_MODE_NIGHT_YES ? "on" : "off")
                    .append("->").append(nightB == Configuration.UI_MODE_NIGHT_YES ? "on" : "off");
        }
        if (a.screenWidthDp != b.screenWidthDp || a.screenHeightDp != b.screenHeightDp) {
            sb.append(" screen ").append(a.screenWidthDp).append('x').append(a.screenHeightDp)
                    .append("dp->").append(b.screenWidthDp).append('x').append(b.screenHeightDp).append("dp");
        }
        if (a.densityDpi != b.densityDpi) {
            sb.append(" density ").append(a.densityDpi).append("->").append(b.densityDpi);
        }
        if (a.orientation != b.orientation) {
            sb.append(" orientation ").append(a.orientation).append("->").append(b.orientation);
        }
        if (a.fontScale != b.fontScale) {
            sb.append(" fontScale ").append(a.fontScale).append("->").append(b.fontScale);
        }
        if (!a.getLocales().equals(b.getLocales())) {
            sb.append(" locale ").append(a.getLocales().toLanguageTags())
                    .append("->").append(b.getLocales().toLanguageTags());
        }
        // 上面没列到的也不漏：系统给的差异位原样写上
        sb.append(" (diff=0x").append(Integer.toHexString(a.diff(b))).append(')');
        return sb.toString().trim();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        // 系统在催内存。卡顿报告带着日志尾巴，内存紧张和卡住是不是同时发生，一看便知
        AppLog.w("App", "onTrimMemory level " + level);
    }
}
