package com.kooo.evcam.settings;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.kooo.evcam.AppLog;

/**
 * 界面语言：跟随系统 / 中文 / English。
 *
 * <h3>用系统自己的「按应用设定语言」</h3>
 *
 * <p>走 {@link AppCompatDelegate#setApplicationLocales}，也就是 Android 13 起系统设置里
 * 那个「应用语言」。系统负责重新加载资源并重建界面，<b>不需要自己写一套「重启后生效」</b>——
 * 自己写的那种，最容易变成「设置里已经改了、界面还是旧的」。</p>
 *
 * <p>appcompat 会把这套能力向下兼容到 Android 13 之前的版本，所以这台车机上同样有效。</p>
 *
 * <h3>为什么默认跟随系统</h3>
 *
 * <p>车机本来是什么语言，这个应用就该是什么语言 —— 这是不需要任何人做决定的默认。
 * 另外两档留给「系统语言和使用者的偏好不一致」的情况。</p>
 */
public final class Languages {

    private static final String TAG = "Languages";

    /** 跟随系统。 */
    public static final String AUTO = "auto";
    /** 中文。 */
    public static final String CHINESE = "zh";
    /** 英文。 */
    public static final String ENGLISH = "en";

    private Languages() {
    }

    /**
     * 一个模式对应的 BCP-47 语言标签。
     *
     * <p>「跟随系统」是<b>空字符串</b>，不是某个具体语言 —— 空表示「不指定」，
     * 系统于是回到自己的语言。填一个具体语言就等于永远锁死，那不是跟随。</p>
     *
     * <p>纯函数，不碰 Android，可以单独测。</p>
     */
    public static String tagsFor(String mode) {
        if (CHINESE.equals(mode)) {
            return "zh-CN";
        }
        if (ENGLISH.equals(mode)) {
            return "en";
        }
        // 包括 auto 和任何不认识的值：不指定，交回给系统
        return "";
    }

    /** 把设置里的选择真正应用到界面上。 */
    public static void apply(String mode) {
        String tags = tagsFor(mode);
        try {
            AppCompatDelegate.setApplicationLocales(tags.isEmpty()
                    ? LocaleListCompat.getEmptyLocaleList()
                    : LocaleListCompat.forLanguageTags(tags));
            AppLog.d(TAG, "界面语言: " + mode + (tags.isEmpty() ? "（跟随系统）" : " -> " + tags));
        } catch (Throwable t) {
            // 语言没切成不该让应用起不来
            AppLog.w(TAG, "设置界面语言失败: " + t);
        }
    }
}
