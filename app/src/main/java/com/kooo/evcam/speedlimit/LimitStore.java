/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.speedlimit;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 装着的限速包：应用自己的目录 {@code files/speed-limits/<国家>.zslp}（不在 U 盘上，不跟着设置备份走）。
 *
 * <p>换上一个新包只走 {@link #install}：大小、sha256 在下载时对过（{@link LimitDownloads}），这里再把整个包解一遍，
 * 国家、日期也要和清单一致 —— 都过了才把 {@code .part} 改名成正式的文件（校验通过才启用）。没过的删掉。</p>
 */
public final class LimitStore {

    static final String DIR = "speed-limits";
    static final String SUFFIX = ".zslp";
    static final String PART = ".part";

    /** 一个装着的包。 */
    public static final class Installed {
        public final String country;
        public final int date;
        public final long bytes;
        public final File file;

        Installed(String country, int date, long bytes, File file) {
            this.country = country;
            this.date = date;
            this.bytes = bytes;
            this.file = file;
        }
    }

    private final File dir;

    public LimitStore(Context context) {
        this(new File(context.getFilesDir(), DIR));
    }

    LimitStore(File dir) {
        this.dir = dir;
    }

    /** 下载时先写到这里。 */
    File part(String country) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        return new File(dir, country + SUFFIX + PART);
    }

    File file(String country) {
        return new File(dir, country + SUFFIX);
    }

    /** 装着的包，按国家代码排；打不开的不算（也不删：可能只是这一刻读不了）。 */
    public List<Installed> installed() {
        List<Installed> out = new ArrayList<>();
        File[] files = dir.listFiles((d, name) -> name.endsWith(SUFFIX));
        if (files == null) {
            return out;
        }
        for (File f : files) {
            try (LimitPackage p = LimitPackage.open(f)) {
                if (f.getName().equals(p.country + SUFFIX)) {
                    out.add(new Installed(p.country, p.date, f.length(), f));
                }
            } catch (IOException e) {
                // 看不懂的包不用；下一次下载会把它换掉
            }
        }
        Collections.sort(out, (a, b) -> a.country.compareTo(b.country));
        return out;
    }

    public List<File> files() {
        List<File> out = new ArrayList<>();
        for (Installed i : installed()) {
            out.add(i.file);
        }
        return out;
    }

    public Installed find(String country) {
        for (Installed i : installed()) {
            if (i.country.equals(country)) {
                return i;
            }
        }
        return null;
    }

    /**
     * 把下好的 {@code .part} 换上：整个包解一遍、国家和日期对上清单才换；没过就删掉 {@code .part} 再抛。
     */
    void install(LimitCatalog.Entry entry, File part) throws IOException {
        try {
            try (LimitPackage p = LimitPackage.open(part)) {
                if (!p.country.equals(entry.country) || p.date != entry.date) {
                    throw new LimitPackage.Invalid("package is " + p.country + " " + p.date + ", catalog says "
                            + entry.country + " " + entry.date);
                }
                p.checkAll();
            }
            File target = file(entry.country);
            if (!part.renameTo(target)) {
                // 有的文件系统改名不覆盖：先删旧的再改
                if (!target.delete() || !part.renameTo(target)) {
                    throw new IOException("Cannot move " + part.getName() + " into place");
                }
            }
        } catch (IOException e) {
            deleteQuietly(part);
            throw e;
        }
    }

    /** 删掉一个国家的包（和没下完的残件）。 */
    public boolean delete(String country) {
        File f = file(country);
        deleteQuietly(new File(dir, country + SUFFIX + PART));
        return !f.exists() || f.delete();
    }

    static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
