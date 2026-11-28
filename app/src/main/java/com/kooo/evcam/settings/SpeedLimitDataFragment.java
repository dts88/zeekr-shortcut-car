/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.settings;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceScreen;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.R;
import com.kooo.evcam.speedlimit.LimitCatalog;
import com.kooo.evcam.speedlimit.LimitDisplay;
import com.kooo.evcam.speedlimit.LimitDownloads;
import com.kooo.evcam.speedlimit.LimitStore;
import com.kooo.evcam.speedlimit.WifiGate;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置 → 按键 → 悬浮按钮显示限速 → 限速数据（项目所有者 2026-11-28 加在开发者选项，同日实测后移到「按键」；
 * 项目本地文档 speed-limits/spec.md）。
 *
 * <p>打开这一页先看网络（{@link WifiGate}）：是 Wi-Fi 才去 zeekr.autos 取清单；国家的包只在点那一行时下，
 * 下之前再看一次网络。下好、大小和 sha256 对上清单、整个包解一遍都过了才换上（校验通过才启用），
 * 然后叫正在显示的悬浮按钮重开数据（{@link LimitDisplay#packagesChanged}）。装着的包不管清单取没取到都列出来，点了能更新、删除。</p>
 *
 * <p>整页在代码里搭（同权限设置）：每一行的状态是算出来的。网络、下载都在后台的一个线程上，一次一件。</p>
 */
public class SpeedLimitDataFragment extends PreferenceFragmentCompat {

    private enum CatalogState { LOADING, OFFLINE, UNCONFIRMED, FAILED, READY }

    /** 出网的三件事（看网络、取清单、下包）；测试换成假的，不真去 zeekr.autos。 */
    interface Source {
        WifiGate.Result network(WifiGate gate);

        LimitCatalog catalog() throws IOException;

        void download(LimitCatalog.Entry entry, LimitStore store, LimitDownloads.Progress progress) throws IOException;
    }

    static Source source = new Source() {
        @Override
        public WifiGate.Result network(WifiGate gate) {
            return LimitDownloads.network(gate);
        }

        @Override
        public LimitCatalog catalog() throws IOException {
            return LimitDownloads.catalog();
        }

        @Override
        public void download(LimitCatalog.Entry entry, LimitStore store, LimitDownloads.Progress progress)
                throws IOException {
            LimitDownloads.download(entry, store, progress);
        }
    };

    /** 后台那一个线程；测试换成当场跑的。 */
    static java.util.function.Supplier<ExecutorService> executor = Executors::newSingleThreadExecutor;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = executor.get();
    private WifiGate gate;
    private LimitStore store;
    /** null = 正在检查。 */
    private WifiGate.Result network;
    private CatalogState catalogState = CatalogState.LOADING;
    private LimitCatalog catalog;
    /** 国家 → 正在做什么（「正在下载 42%」「正在校验…」）。 */
    private final Map<String, String> busy = new HashMap<>();
    /** 国家 → 上一次为什么没成。 */
    private final Map<String, Integer> failed = new HashMap<>();
    /** 没见过的网络这一页已经自己问过一次了（不追着问）。 */
    private boolean askedThisTime;

    /** 「悬浮按钮显示限速」那一行的行尾写什么：装了哪几个，没有就「未下载」。 */
    public static String summary(Context context) {
        List<LimitStore.Installed> installed = new LimitStore(context).installed();
        if (installed.isEmpty()) {
            return context.getString(R.string.set_speed_limit_data_none);
        }
        List<String> names = new ArrayList<>();
        for (LimitStore.Installed i : installed) {
            names.add(countryName(context, i.country));
        }
        return context.getString(R.string.set_speed_limit_data_some,
                android.text.TextUtils.join(context.getString(R.string.auto_list_sep), names));
    }

    @Override
    public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
        Context context = requireContext();
        gate = new WifiGate(context);
        store = new LimitStore(context);
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(context));
        rebuild();
        check();
    }

    @Override
    public void onViewCreated(@NonNull android.view.View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        PreferenceRows.styleList(this, 28, 16);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 在下的那一个下完再停（关掉这一页不打断下载）；结果回来时这一页已经不在了就不画
        io.shutdown();
    }

    // ------------------------------------------------------------------ 网络和清单

    /** 看网络；是 Wi-Fi 再取清单。 */
    private void check() {
        network = null;
        catalogState = CatalogState.LOADING;
        rebuild();
        io.execute(() -> {
            WifiGate.Result result = source.network(gate);
            LimitCatalog loaded = null;
            CatalogState state;
            if (result.kind == WifiGate.Kind.UNCONFIRMED) {
                state = CatalogState.UNCONFIRMED;
            } else if (result.kind != WifiGate.Kind.WIFI) {
                state = CatalogState.OFFLINE;
            } else {
                try {
                    loaded = source.catalog();
                    state = CatalogState.READY;
                } catch (IOException e) {
                    state = CatalogState.FAILED;
                }
            }
            LimitCatalog finalCatalog = loaded;
            CatalogState finalState = state;
            onMain(() -> {
                network = result;
                catalogState = finalState;
                if (finalCatalog != null) {
                    catalog = finalCatalog;
                }
                rebuild();
                if (result.kind == WifiGate.Kind.UNCONFIRMED && !askedThisTime) {
                    // 没见过的网络：要先知道是不是 Wi-Fi 才能往下走，这一页打开时问一次
                    askedThisTime = true;
                    onNetworkTapped();
                }
            });
        });
    }

    private void download(LimitCatalog.Entry entry) {
        failed.remove(entry.country);
        busy.put(entry.country, getString(R.string.sl_downloading, 0));
        rebuild();
        io.execute(() -> {
            WifiGate.Result result = source.network(gate);
            if (result.kind != WifiGate.Kind.WIFI) {
                onMain(() -> {
                    network = result;
                    finish(entry.country, R.string.sl_failed_not_wifi);
                });
                return;
            }
            int[] shown = {-1};
            try {
                source.download(entry, store, (done, total) -> {
                    int percent = total > 0 ? (int) Math.min(99, done * 100 / total) : 0;
                    if (percent != shown[0]) {
                        shown[0] = percent;
                        onMain(() -> {
                            busy.put(entry.country, getString(R.string.sl_downloading, percent));
                            rebuild();
                        });
                    }
                });
                com.kooo.evcam.blackbox.BlackBox.noteImportant("限速数据：" + entry.country + " 已下载并校验（" + entry.date + "）");
                LimitDisplay.packagesChanged();
                onMain(() -> finish(entry.country, 0));
            } catch (LimitDownloads.Unverified e) {
                com.kooo.evcam.blackbox.BlackBox.noteImportant("限速数据：" + entry.country + " 校验未通过，未启用");
                onMain(() -> finish(entry.country, R.string.sl_failed_verify));
            } catch (IOException e) {
                com.kooo.evcam.blackbox.BlackBox.noteImportant("限速数据：" + entry.country + " 下载失败 "
                        + e.getClass().getSimpleName());
                onMain(() -> finish(entry.country, R.string.sl_failed_download));
            }
        });
    }

    private void finish(String country, @StringRes int failure) {
        busy.remove(country);
        if (failure != 0) {
            failed.put(country, failure);
        }
        rebuild();
    }

    private void delete(String country) {
        store.delete(country);
        failed.remove(country);
        com.kooo.evcam.blackbox.BlackBox.noteImportant("限速数据：" + country + " 已删除");
        LimitDisplay.packagesChanged();
        rebuild();
    }

    /** 回到主线程；这一页已经不在了就不做。 */
    private void onMain(Runnable r) {
        main.post(() -> {
            if (isAdded() && getPreferenceScreen() != null) {
                r.run();
            }
        });
    }

    // ------------------------------------------------------------------ 画

    private void rebuild() {
        PreferenceScreen screen = getPreferenceScreen();
        Context context = getContext();
        if (screen == null || context == null) {
            return;
        }
        screen.removeAll();

        Preference net = new Preference(context);
        net.setKey("sl_network");
        net.setTitle(R.string.sl_network_title);
        net.setSummary(network == null ? getString(R.string.sl_network_checking)
                : network.kind == WifiGate.Kind.WIFI ? getString(R.string.sl_network_wifi)
                : network.kind == WifiGate.Kind.MOBILE ? getString(R.string.sl_network_mobile)
                : network.kind == WifiGate.Kind.UNCONFIRMED ? getString(R.string.sl_network_unconfirmed)
                : getString(R.string.sl_network_unknown));
        net.setSelectable(network != null);
        net.setOnPreferenceClickListener(p -> {
            onNetworkTapped();
            return true;
        });
        screen.addPreference(net);

        PreferenceCategory countries = new PreferenceCategory(context);
        countries.setTitle(R.string.sl_group_countries);
        screen.addPreference(countries);
        if (catalogState != CatalogState.READY) {
            Preference state = new Preference(context);
            state.setKey("sl_catalog");
            state.setSummary(catalogState == CatalogState.LOADING ? R.string.sl_catalog_loading
                    : catalogState == CatalogState.OFFLINE ? R.string.sl_catalog_offline
                    : catalogState == CatalogState.UNCONFIRMED ? R.string.sl_catalog_unconfirmed
                    : R.string.sl_catalog_failed);
            state.setSelectable(catalogState == CatalogState.FAILED);
            state.setOnPreferenceClickListener(p -> {
                check();
                return true;
            });
            countries.addPreference(state);
        }
        for (Map.Entry<String, Row> e : rows(context).entrySet()) {
            countries.addPreference(countryRow(context, e.getValue()));
        }

        Preference note = new Preference(context);
        note.setKey("sl_note");
        note.setSelectable(false);
        note.setSummary(R.string.sl_note);
        screen.addPreference(note);
        PreferenceRows.apply(screen);
    }

    /** 一个国家这一行要的：清单里的那一条、装着的那一个（都可能没有，但不会都没有）。 */
    private static final class Row {
        String country;
        LimitCatalog.Entry entry;
        LimitStore.Installed installed;
    }

    /** 清单里的和装着的合在一起，按显示的名字排。 */
    private Map<String, Row> rows(Context context) {
        Map<String, Row> byName = new TreeMap<>(java.text.Collator.getInstance(locale(context)));
        Map<String, Row> byCountry = new HashMap<>();
        if (catalog != null) {
            for (LimitCatalog.Entry e : catalog.entries) {
                row(byCountry, e.country).entry = e;
            }
        }
        for (LimitStore.Installed i : store.installed()) {
            row(byCountry, i.country).installed = i;
        }
        for (Row r : byCountry.values()) {
            byName.put(countryName(context, r.country) + " " + r.country, r);
        }
        return byName;
    }

    private static Row row(Map<String, Row> rows, String country) {
        Row r = rows.get(country);
        if (r == null) {
            r = new Row();
            r.country = country;
            rows.put(country, r);
        }
        return r;
    }

    private Preference countryRow(Context context, Row row) {
        Preference p = new Preference(context);
        p.setKey("sl_country_" + row.country);
        p.setTitle(countryName(context, row.country));
        String doing = busy.get(row.country);
        Integer failure = failed.get(row.country);
        boolean newer = row.installed != null && row.entry != null && row.entry.date > row.installed.date;
        String summary;
        if (doing != null) {
            summary = doing;
        } else if (failure != null) {
            summary = getString(failure);
        } else if (newer) {
            summary = getString(R.string.sl_update, date(row.entry.date), size(context, row.entry.bytes));
        } else if (row.installed != null) {
            summary = getString(R.string.sl_installed, date(row.installed.date));
        } else {
            summary = size(context, row.entry.bytes);
        }
        p.setSummary(summary);
        p.setSelectable(doing == null);
        p.setOnPreferenceClickListener(x -> {
            if (row.installed != null) {
                manage(row, newer);
            } else if (row.entry != null) {
                download(row.entry);
            }
            return true;
        });
        return p;
    }

    // ------------------------------------------------------------------ 对话框

    /** 装着的：更新（清单里有更新的时）、删除。 */
    private void manage(Row row, boolean newer) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(countryName(context, row.country))
                .setMessage(getString(R.string.sl_installed, date(row.installed.date)))
                .setNegativeButton(R.string.action_cancel, null);
        if (newer) {
            builder.setPositiveButton(R.string.sl_action_update, (d, w) -> download(row.entry));
            builder.setNeutralButton(R.string.action_delete, (d, w) -> delete(row.country));
            com.kooo.evcam.ui.CamDialogs.show(builder);
        } else {
            builder.setPositiveButton(R.string.action_delete, (d, w) -> delete(row.country));
            com.kooo.evcam.ui.CamDialogs.showDestructive(builder);
        }
    }

    /** 网络那一行：问不到就再查一次；没见过的问是不是 Wi-Fi；记过的可以改记。 */
    private void onNetworkTapped() {
        Context context = getContext();
        if (context == null || network == null) {
            return;
        }
        if (network.prefix == null) {
            check();
            return;
        }
        String prefix = network.prefix;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context, R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle(R.string.sl_network_title);
        if (network.kind == WifiGate.Kind.UNCONFIRMED) {
            builder.setMessage(R.string.sl_network_confirm)
                    .setNegativeButton(R.string.sl_network_not_wifi, (d, w) -> {
                        gate.teach(prefix, false);
                        check();
                    })
                    .setPositiveButton(R.string.sl_network_is_wifi, (d, w) -> {
                        gate.teach(prefix, true);
                        check();
                    });
        } else {
            boolean wifi = network.kind == WifiGate.Kind.WIFI;
            builder.setMessage(wifi ? R.string.sl_network_teach_wifi : R.string.sl_network_teach_mobile)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(wifi ? R.string.sl_network_not_wifi : R.string.sl_network_is_wifi, (d, w) -> {
                        gate.teach(prefix, !wifi);
                        check();
                    });
        }
        com.kooo.evcam.ui.CamDialogs.show(builder);
    }

    // ------------------------------------------------------------------ 字

    static String countryName(Context context, String country) {
        String name = new Locale("", country).getDisplayCountry(locale(context));
        return name == null || name.isEmpty() ? country : name;
    }

    private static Locale locale(Context context) {
        return context.getResources().getConfiguration().getLocales().get(0);
    }

    /** yyyymmdd → 「2026-11-28」（数字一律 0–9）。 */
    static String date(int yyyymmdd) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d", yyyymmdd / 10000, yyyymmdd / 100 % 100, yyyymmdd % 100);
    }

    private static String size(Context context, long bytes) {
        return Formatter.formatShortFileSize(context, bytes);
    }
}
