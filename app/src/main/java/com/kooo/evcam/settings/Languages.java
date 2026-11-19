/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.LocaleList;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.kooo.evcam.AppLog;

import java.text.DecimalFormatSymbols;
import java.util.IllformedLocaleException;
import java.util.Locale;

/**
 * 界面语言：跟随系统 / 中文 / English / Bahasa Melayu / العربية。
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
 * 其余几档留给「系统语言和使用者的偏好不一致」的情况。</p>
 *
 * <h3>界面数字一律 0–9</h3>
 *
 * <p>阿拉伯语默认写 ٠–٩，界面数字一律 0–9（项目所有者 2026-11-18，ui-text-style §4）。只在这里管：
 * 交给系统的语言标签带上 {@code nu-latn}（数字用 0–9）。选了阿拉伯语是 {@code ar-u-nu-latn}；跟随系统、
 * 而系统语言写的不是 0–9（阿拉伯语的车机）时，用系统语言加 {@code nu-latn}，系统语言一变就重新算
 * （{@code ZeekrShortcutApp#onConfigurationChanged}）。资源里的 {@code %d}、代码里不带 Locale 的格式化都照它。</p>
 */
public final class Languages {

    private static final String TAG = "Languages";

    /** 跟随系统。 */
    public static final String AUTO = "auto";
    /** 中文。 */
    public static final String CHINESE = "zh";
    /** 英文。 */
    public static final String ENGLISH = "en";
    /** 马来语。 */
    public static final String MALAY = "ms";
    /** 阿拉伯语：从右往左（ui-spec §十七），数字 0–9。 */
    public static final String ARABIC = "ar";

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
        if (MALAY.equals(mode)) {
            return "ms";
        }
        if (ARABIC.equals(mode)) {
            return "ar-u-nu-latn";
        }
        // 包括 auto 和任何不认识的值：不指定，交回给系统
        return "";
    }

    /**
     * 实际交给系统的语言标签：选了某种语言就是 {@link #tagsFor(String)}；跟随系统时，系统语言写 0–9 的是空
     * （真正跟随），不写 0–9 的是系统语言加 {@code nu-latn}。纯函数，可以单独测。
     */
    public static String tagsFor(String mode, Locale system) {
        String tags = tagsFor(mode);
        if (!tags.isEmpty() || system == null || writesDigits0to9(system)) {
            return tags;
        }
        try {
            return new Locale.Builder().setLocale(system)
                    .setUnicodeLocaleKeyword("nu", "latn").build().toLanguageTag();
        } catch (IllformedLocaleException e) {
            return "";
        }
    }

    /** 这个语言写数字是不是 0–9。 */
    static boolean writesDigits0to9(Locale locale) {
        return DecimalFormatSymbols.getInstance(locale).getZeroDigit() == '0';
    }

    /**
     * 按「应用语言」取字串的 Context，给不在界面上的地方用（录像里的信息条在编码线程上画）。
     * Application 的 Context 在 Android 13 以下给的是系统语言（见 {@code CameraNames}）：
     * 这里按 appcompat 记着的应用语言另起一份配置；跟随系统时就是 Application 的那份。
     */
    public static Context localized(Context context) {
        Context app = context.getApplicationContext();
        LocaleListCompat locales = AppCompatDelegate.getApplicationLocales();
        if (locales.isEmpty()) {
            return app;
        }
        Configuration config = new Configuration(app.getResources().getConfiguration());
        config.setLocales(LocaleList.forLanguageTags(locales.toLanguageTags()));
        return app.createConfigurationContext(config);
    }

    /**
     * 把设置里的选择真正应用到界面上：应用启动时、改设置时、系统语言变了时调。
     *
     * <p>进程的默认语言（代码里不带 Locale 的格式化用它）也设成同一份：界面打开时 appcompat 本来就这么设，
     * 这里先设好，开机只起了服务、还没打开界面时也一样。</p>
     */
    public static void apply(String mode) {
        LocaleList system = Resources.getSystem().getConfiguration().getLocales();
        String tags = tagsFor(mode, system.isEmpty() ? null : system.get(0));
        try {
            AppCompatDelegate.setApplicationLocales(tags.isEmpty()
                    ? LocaleListCompat.getEmptyLocaleList()
                    : LocaleListCompat.forLanguageTags(tags));
            AppLog.d(TAG, "界面语言: " + mode + (tags.isEmpty() ? "（跟随系统）" : " -> " + tags));
        } catch (Throwable t) {
            // 语言没切成不该让应用起不来
            AppLog.w(TAG, "设置界面语言失败: " + t);
        }
        LocaleList defaults = tags.isEmpty() ? system : LocaleList.forLanguageTags(tags);
        if (!defaults.isEmpty()) {
            LocaleList.setDefault(defaults);
        }
    }
}
