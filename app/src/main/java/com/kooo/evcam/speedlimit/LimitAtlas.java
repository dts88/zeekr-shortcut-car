/*
 * Zeekr Shortcut - https://github.com/dts88/zeekr-shortcut-car
 * Copyright (C) 2026 dts88
 * SPDX-License-Identifier: GPL-3.0-only
 * Additional terms under GPL-3.0 section 7 apply to dts88's code; see NOTICE.md.
 */

package com.kooo.evcam.speedlimit;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 装着的几个包合在一起查：一块瓦片的路 = 每个有这一块的包里的路（国界上两个包都有）。
 * 解开的瓦片留最近用过的几块，开车时一般只在当前这块和旁边几块之间来回。只在一个线程上用（{@link LimitDisplay}）。
 */
public final class LimitAtlas implements Closeable {

    /** 留几块解开的瓦片。 */
    private static final int CACHE_TILES = 16;

    private final List<LimitPackage> packages;
    private final Map<Long, List<LimitRoad>> cache = new LinkedHashMap<Long, List<LimitRoad>>(CACHE_TILES, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, List<LimitRoad>> eldest) {
            return size() > CACHE_TILES;
        }
    };

    public LimitAtlas(List<LimitPackage> packages) {
        this.packages = new ArrayList<>(packages);
    }

    /** 打开这几个文件；打不开的跳过（{@code skipped} 收它们的名字，可以是 null）。 */
    public static LimitAtlas open(List<File> files, List<String> skipped) {
        List<LimitPackage> opened = new ArrayList<>();
        for (File f : files) {
            try {
                opened.add(LimitPackage.open(f));
            } catch (IOException e) {
                if (skipped != null) {
                    skipped.add(f.getName());
                }
            }
        }
        return new LimitAtlas(opened);
    }

    public boolean isEmpty() {
        return packages.isEmpty();
    }

    /** 一块瓦片里的路；哪个包都没有这一块就是空的。 */
    public List<LimitRoad> roads(int x, int y) throws IOException {
        long key = Tiles.key(x, y);
        List<LimitRoad> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        List<LimitRoad> roads = null;
        for (LimitPackage p : packages) {
            List<LimitRoad> part = p.tile(x, y);
            if (part == null) {
                continue;
            }
            if (roads == null) {
                roads = part;
            } else {
                List<LimitRoad> merged = new ArrayList<>(roads);
                merged.addAll(part);
                roads = merged;
            }
        }
        if (roads == null) {
            roads = Collections.emptyList();
        }
        cache.put(key, roads);
        return roads;
    }

    @Override
    public void close() {
        cache.clear();
        for (LimitPackage p : packages) {
            try {
                p.close();
            } catch (IOException ignored) {
                // 只读的文件，关不上也没有要收拾的
            }
        }
        packages.clear();
    }
}
