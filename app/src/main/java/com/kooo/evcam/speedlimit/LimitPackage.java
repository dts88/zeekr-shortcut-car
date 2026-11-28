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
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * 一个国家 / 地区的限速包（ZSLP v1，项目本地文档 speed-limits/spec.md §三）。
 *
 * <p>打开时读头和索引、把能从索引看出来的毛病都查掉（位置、顺序、首尾相接、没有多余的字节）；
 * 瓦片用到时才解（{@link #tile}）。{@link #checkAll} 把每一块都解一遍：下载回来、换上之前跑（校验通过才启用）。
 * 看不懂的一律抛 {@link Invalid}，不猜。</p>
 */
public final class LimitPackage implements Closeable {

    /** 包有毛病（格式、范围、首尾不接……）。 */
    public static final class Invalid extends IOException {
        Invalid(String message) {
            super(message);
        }
    }

    public static final int FORMAT = 1;
    static final int HEADER_BYTES = 40;
    static final int INDEX_BYTES = 12;
    /** 一块解开后最大这么多，再大当坏包。 */
    static final int MAX_TILE_BYTES = 4 * 1024 * 1024;
    static final int MAX_POINTS = 65535;
    static final int MAX_CLASS = 13;
    private static final byte[] MAGIC = {'Z', 'S', 'L', 'P'};

    public final String country;
    /** 数据日期 yyyymmdd。 */
    public final int date;
    public final int minLat;
    public final int minLon;
    public final int maxLat;
    public final int maxLon;

    private final RandomAccessFile file;
    private final long blobsAt;
    /** 按 (x, y) 从小到大的瓦片键（{@link Tiles#key}）。 */
    private final long[] keys;
    private final long[] offsets;
    private final int[] lengths;

    private LimitPackage(RandomAccessFile file, String country, int date, int[] bounds, long blobsAt,
                         long[] keys, long[] offsets, int[] lengths) {
        this.file = file;
        this.country = country;
        this.date = date;
        this.minLat = bounds[0];
        this.minLon = bounds[1];
        this.maxLat = bounds[2];
        this.maxLon = bounds[3];
        this.blobsAt = blobsAt;
        this.keys = keys;
        this.offsets = offsets;
        this.lengths = lengths;
    }

    /** 打开一个包：读头和索引并检查。打不开、看不懂抛 IOException（看不懂的是 {@link Invalid}）。 */
    public static LimitPackage open(File path) throws IOException {
        RandomAccessFile file = new RandomAccessFile(path, "r");
        try {
            return read(file);
        } catch (IOException | RuntimeException e) {
            file.close();
            throw e instanceof IOException ? (IOException) e : new Invalid(String.valueOf(e));
        }
    }

    private static LimitPackage read(RandomAccessFile file) throws IOException {
        long size = file.length();
        if (size < HEADER_BYTES) {
            throw new Invalid("shorter than the header");
        }
        ByteBuffer head = ByteBuffer.wrap(readFully(file, 0, HEADER_BYTES)).order(ByteOrder.LITTLE_ENDIAN);
        for (byte b : MAGIC) {
            if (head.get() != b) {
                throw new Invalid("not a ZSLP file");
            }
        }
        int version = head.get() & 0xFF;
        if (version != FORMAT) {
            throw new Invalid("format " + version);
        }
        int zoom = head.get() & 0xFF;
        if (zoom != Tiles.ZOOM) {
            throw new Invalid("zoom " + zoom);
        }
        byte c0 = head.get();
        byte c1 = head.get();
        if (c0 < 'A' || c0 > 'Z' || c1 < 'A' || c1 > 'Z') {
            throw new Invalid("bad country");
        }
        String country = new String(new byte[]{c0, c1}, StandardCharsets.US_ASCII);
        long date = head.getInt() & 0xFFFFFFFFL;
        if (date < 19700101L || date > 99991231L) {
            throw new Invalid("bad date");
        }
        int[] bounds = {head.getInt(), head.getInt(), head.getInt(), head.getInt()};
        if (!(-9_000_000 <= bounds[0] && bounds[0] <= bounds[2] && bounds[2] <= 9_000_000
                && -18_000_000 <= bounds[1] && bounds[1] <= bounds[3] && bounds[3] <= 18_000_000)) {
            throw new Invalid("bad bounds");
        }
        long count = head.getInt() & 0xFFFFFFFFL;
        long indexAt = head.getInt() & 0xFFFFFFFFL;
        long blobsAt = head.getInt() & 0xFFFFFFFFL;
        if (count < 1 || count > (size - HEADER_BYTES) / INDEX_BYTES || indexAt != HEADER_BYTES
                || blobsAt != HEADER_BYTES + INDEX_BYTES * count || blobsAt > size) {
            throw new Invalid("bad index position");
        }
        int n = (int) count;
        ByteBuffer index = ByteBuffer.wrap(readFully(file, indexAt, INDEX_BYTES * n)).order(ByteOrder.LITTLE_ENDIAN);
        long[] keys = new long[n];
        long[] offsets = new long[n];
        int[] lengths = new int[n];
        long end = 0;
        for (int i = 0; i < n; i++) {
            int x = index.getShort() & 0xFFFF;
            int y = index.getShort() & 0xFFFF;
            long offset = index.getInt() & 0xFFFFFFFFL;
            long length = index.getInt() & 0xFFFFFFFFL;
            if (x >= Tiles.SIZE || y >= Tiles.SIZE) {
                throw new Invalid("tile out of range");
            }
            keys[i] = Tiles.key(x, y);
            if (i > 0 && keys[i] <= keys[i - 1]) {
                throw new Invalid("index not sorted");
            }
            if (length < 1 || offset != end || blobsAt + offset + length > size) {
                throw new Invalid("tile " + x + "/" + y + " outside the file");
            }
            offsets[i] = offset;
            lengths[i] = (int) length;
            end = offset + length;
        }
        if (blobsAt + end != size) {
            throw new Invalid("trailing bytes");
        }
        return new LimitPackage(file, country, (int) date, bounds, blobsAt, keys, offsets, lengths);
    }

    public int tileCount() {
        return keys.length;
    }

    /** 这一块在不在包里。 */
    public boolean has(int x, int y) {
        return java.util.Arrays.binarySearch(keys, Tiles.key(x, y)) >= 0;
    }

    /** 解开一块；包里没有这一块返回 null。 */
    public List<LimitRoad> tile(int x, int y) throws IOException {
        int i = java.util.Arrays.binarySearch(keys, Tiles.key(x, y));
        if (i < 0) {
            return null;
        }
        byte[] blob;
        synchronized (file) {
            blob = readFully(file, blobsAt + offsets[i], lengths[i]);
        }
        return decode(inflate(blob, x, y), x, y);
    }

    /** 每一块都解一遍、查一遍（校验通过才启用）。 */
    public void checkAll() throws IOException {
        for (long key : keys) {
            tile((int) (key >>> 16), (int) (key & 0xFFFF));
        }
    }

    @Override
    public void close() throws IOException {
        file.close();
    }

    // ------------------------------------------------------------------ 解一块

    private static byte[] inflate(byte[] blob, int x, int y) throws Invalid {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(blob);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(Math.max(64, blob.length * 4));
            byte[] chunk = new byte[16 * 1024];
            while (!inflater.finished()) {
                int got = inflater.inflate(chunk);
                if (got == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new Invalid("tile " + x + "/" + y + " is cut short");
                }
                out.write(chunk, 0, got);
                if (out.size() > MAX_TILE_BYTES) {
                    throw new Invalid("tile " + x + "/" + y + " too large");
                }
            }
            if (inflater.getRemaining() != 0) {
                throw new Invalid("tile " + x + "/" + y + " has trailing bytes");
            }
            return out.toByteArray();
        } catch (DataFormatException e) {
            throw new Invalid("tile " + x + "/" + y + " does not inflate: " + e.getMessage());
        } finally {
            inflater.end();
        }
    }

    private List<LimitRoad> decode(byte[] payload, int x, int y) throws Invalid {
        int[] pos = {0};
        long count = varint(payload, pos);
        if (count < 1 || count > payload.length) {
            throw new Invalid("tile " + x + "/" + y + " has no roads");
        }
        List<LimitRoad> roads = new ArrayList<>((int) count);
        boolean first = true;
        long lat = 0;
        long lon = 0;
        for (long r = 0; r < count; r++) {
            if (pos[0] + 4 > payload.length) {
                throw new Invalid("road header runs past the end");
            }
            int kind = payload[pos[0]] & 0xFF;
            int forward = payload[pos[0] + 1] & 0xFF;
            int backward = payload[pos[0] + 2] & 0xFF;
            int level = payload[pos[0] + 3] & 0xFF;
            pos[0] += 4;
            int roadClass = kind & 0x0F;
            int oneway = (kind >> 4) & 0x03;
            if (roadClass > MAX_CLASS || oneway > 2 || (kind & 0x80) != 0 || (level & 0xC0) != 0
                    || badLimit(forward) || badLimit(backward)) {
                throw new Invalid("bad road in tile " + x + "/" + y);
            }
            long n = varint(payload, pos);
            if (n < 2 || n > MAX_POINTS) {
                throw new Invalid("bad point count in tile " + x + "/" + y);
            }
            int[] lats = new int[(int) n];
            int[] lons = new int[(int) n];
            for (int i = 0; i < n; i++) {
                long dLat = unzigzag(varint(payload, pos));
                long dLon = unzigzag(varint(payload, pos));
                if (first) {
                    lat = dLat;
                    lon = dLon;
                    first = false;
                } else {
                    lat += dLat;
                    lon += dLon;
                }
                if (lat < minLat || lat > maxLat || lon < minLon || lon > maxLon) {
                    throw new Invalid("point outside the bounds in tile " + x + "/" + y);
                }
                lats[i] = (int) lat;
                lons[i] = (int) lon;
            }
            roads.add(new LimitRoad(roadClass, oneway, (kind & 0x40) != 0, forward, backward, level, lats, lons));
        }
        if (pos[0] != payload.length) {
            throw new Invalid("trailing bytes in tile " + x + "/" + y);
        }
        return Collections.unmodifiableList(roads);
    }

    private static boolean badLimit(int v) {
        return v > LimitRoad.MAX_LIMIT && v != LimitRoad.NO_LIMIT;
    }

    private static long varint(byte[] data, int[] pos) throws Invalid {
        long value = 0;
        for (int shift = 0; shift <= 35; shift += 7) {
            if (pos[0] >= data.length) {
                throw new Invalid("varint runs past the end");
            }
            int b = data[pos[0]++] & 0xFF;
            value |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) {
                return value;
            }
        }
        throw new Invalid("varint too long");
    }

    private static long unzigzag(long z) {
        return (z >>> 1) ^ -(z & 1);
    }

    private static byte[] readFully(RandomAccessFile file, long at, int length) throws IOException {
        byte[] out = new byte[length];
        file.seek(at);
        file.readFully(out);
        return out;
    }
}
